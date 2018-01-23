package com.launchdarkly.eventsource;

import okhttp3.*;
import okio.BufferedSource;
import okio.Okio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.launchdarkly.eventsource.ConnectionErrorHandler.Action;

import javax.annotation.Nullable;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.Proxy.Type;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.launchdarkly.eventsource.ReadyState.*;
import static java.lang.String.format;

/**
 * Client for <a href="https://www.w3.org/TR/2015/REC-eventsource-20150203/">Server-Sent Events</a>
 * aka EventSource
 */
public class EventSource implements ConnectionHandler, Closeable {
  private final Logger logger;

  private static final long DEFAULT_RECONNECT_TIME_MS = 1000;
  static final long MAX_RECONNECT_TIME_MS = 30000;
  static final int DEFAULT_CONNECT_TIMEOUT_MS = 10000;
  static final int DEFAULT_WRITE_TIMEOUT_MS = 5000;
  static final int DEFAULT_READ_TIMEOUT_MS = 1000 * 60 * 5;

  private final String name;
  private volatile URI uri;
  private final Headers headers;
  private final String method;
  @Nullable private final RequestBody body;
  private final ExecutorService eventExecutor;
  private final ExecutorService streamExecutor;
  private volatile long reconnectTimeMs = 0;
  private volatile String lastEventId;
  private final EventHandler handler;
  private final ConnectionErrorHandler connectionErrorHandler;
  private final AtomicReference<ReadyState> readyState;
  private final OkHttpClient client;
  private volatile Call call;
  private final Random jitter = new Random();
  private Response response;
  private BufferedSource bufferedSource;

  EventSource(Builder builder) {
    this.name = builder.name;
    this.logger = LoggerFactory.getLogger("okhttp-eventsource-[" + name + "]");
    this.uri = builder.uri;
    this.headers = addDefaultHeaders(builder.headers);
    this.method = builder.method;
    this.body = builder.body;
    this.reconnectTimeMs = builder.reconnectTimeMs;
    ThreadFactory eventsThreadFactory = createThreadFactory("okhttp-eventsource-events");
    this.eventExecutor = Executors.newSingleThreadExecutor(eventsThreadFactory);
    ThreadFactory streamThreadFactory = createThreadFactory("okhttp-eventsource-stream");
    this.streamExecutor = Executors.newSingleThreadExecutor(streamThreadFactory);
    this.handler = new AsyncEventHandler(this.eventExecutor, builder.handler);
    this.connectionErrorHandler = builder.connectionErrorHandler;
    this.readyState = new AtomicReference<>(RAW);
    this.client = builder.clientBuilder.build();
  }

  private ThreadFactory createThreadFactory(final String type) {
    final ThreadFactory backingThreadFactory =
        Executors.defaultThreadFactory();
    final AtomicLong count = new AtomicLong(0);
    return new ThreadFactory() {
      @Override
      public Thread newThread(Runnable runnable) {
        Thread thread = backingThreadFactory.newThread(runnable);
        thread.setName(format(Locale.ROOT, "%s-[%s]-%d", type, name, count.getAndIncrement()));
        return thread;
      }
    };
  }

  public void start() {
    if (!readyState.compareAndSet(RAW, CONNECTING)) {
      logger.info("Start method called on this already-started EventSource object. Doing nothing");
      return;
    }
    logger.debug("readyState change: " + RAW + " -> " + CONNECTING);
    logger.info("Starting EventSource client using URI: " + uri);
    streamExecutor.execute(new Runnable() {
      public void run() {
        connect();
      }
    });
  }

  public ReadyState getState() {
    return readyState.get();
  }

  @Override
  public void close() {
    ReadyState currentState = readyState.getAndSet(SHUTDOWN);
    logger.debug("readyState change: " + currentState + " -> " + SHUTDOWN);
    if (currentState == SHUTDOWN) {
      return;
    }
    if (currentState == ReadyState.OPEN) {
      try {
        handler.onClosed();
      } catch (Exception e) {
        handler.onError(e);
      }
    }

    if (call != null) {
      // The call.cancel() must precede the bufferedSource.close().
      // Otherwise, an IllegalArgumentException "Unbalanced enter/exit" error is thrown by okhttp.
      // https://github.com/google/ExoPlayer/issues/1348
      call.cancel();
      logger.debug("call cancelled");
    }

    eventExecutor.shutdownNow();
    streamExecutor.shutdownNow();

    if (client != null) {
      if (client.connectionPool() != null) {
        client.connectionPool().evictAll();
      }
      if (client.dispatcher() != null) {
        client.dispatcher().cancelAll();
        if (client.dispatcher().executorService() != null) {
          client.dispatcher().executorService().shutdownNow();
        }
      }
    }
  }
  Request buildRequest() {
    Request.Builder builder = new Request.Builder()
        .headers(headers)
        .url(uri.toASCIIString())
        .method(method, body);

    if (lastEventId != null && !lastEventId.isEmpty()) {
      builder.addHeader("Last-Event-ID", lastEventId);
    }
    return builder.build();
  }

  private void connect() {
    response = null;
    bufferedSource = null;

    int reconnectAttempts = 0;
    ConnectionErrorHandler.Action errorHandlerAction = null;
    
    try {
      while (!Thread.currentThread().isInterrupted() && readyState.get() != SHUTDOWN) {
        boolean gotResponse = false, timedOut = false;

        maybeWaitWithBackoff(reconnectAttempts++);
        ReadyState currentState = readyState.getAndSet(CONNECTING);
        logger.debug("readyState change: " + currentState + " -> " + CONNECTING);
        try {
          call = client.newCall(buildRequest());
          response = call.execute();
          if (response.isSuccessful()) {
            gotResponse = true;
            currentState = readyState.getAndSet(OPEN);
            if (currentState != CONNECTING) {
              logger.warn("Unexpected readyState change: " + currentState + " -> " + OPEN);
            } else {
              logger.debug("readyState change: " + currentState + " -> " + OPEN);
            }
            logger.info("Connected to Event Source stream.");
            try {
              handler.onOpen();
            } catch (Exception e) {
              handler.onError(e);
            }
            if (bufferedSource != null) {
              bufferedSource.close();
            }
            bufferedSource = Okio.buffer(response.body().source());
            EventParser parser = new EventParser(uri, handler, EventSource.this);
            for (String line; !Thread.currentThread().isInterrupted() && (line = bufferedSource.readUtf8LineStrict()) != null; ) {
              parser.line(line);
            }
          } else {
            logger.debug("Unsuccessful Response: " + response);
            errorHandlerAction = dispatchError(new UnsuccessfulResponseException(response.code()));
          }
        } catch (EOFException eofe) {
          logger.warn("Connection unexpectedly closed.");
        } catch (IOException ioe) {
          if (readyState.get() != SHUTDOWN) {
        	  	logger.debug("Connection problem.", ioe);
        	  	errorHandlerAction = dispatchError(ioe);
          } else {
        	  	errorHandlerAction = ConnectionErrorHandler.Action.SHUTDOWN;
          }
          if (ioe instanceof SocketTimeoutException) {
            timedOut = true;
          }
        } finally {
          ReadyState nextState = CLOSED;
          if (errorHandlerAction == ConnectionErrorHandler.Action.SHUTDOWN) {
            logger.info("Connection has been explicitly shut down by error handler");
            nextState = SHUTDOWN;
          }
          currentState = readyState.getAndSet(nextState);
          logger.debug("readyState change: " + currentState + " -> " + nextState);

          if (response != null && response.body() != null) {
            response.close();
            logger.debug("response closed");
          }

          if (bufferedSource != null) {
            try {
              bufferedSource.close();
              logger.debug("buffered source closed");
            } catch (IOException e) {
              logger.warn("Exception when closing bufferedSource", e);
            }
          }

          if (currentState == ReadyState.OPEN) {
            try {
              handler.onClosed();
            } catch (Exception e) {
              handler.onError(e);
            }
          }
          // reset the backoff if we had a successful connection that was dropped for non-timeout reasons
          if (gotResponse && !timedOut) {
            reconnectAttempts = 0;
          }
        }
      }
    } catch (RejectedExecutionException ignored) {
      call = null;
      response = null;
      bufferedSource = null;
      logger.debug("Rejected execution exception ignored: ", ignored);
      // During shutdown, we tried to send a message to the event handler
      // Do not reconnect; the executor has been shut down
    }
  }

  private ConnectionErrorHandler.Action dispatchError(Throwable t) {
    ConnectionErrorHandler.Action action = connectionErrorHandler.onConnectionError(t);
    if (action != ConnectionErrorHandler.Action.SHUTDOWN) {
      handler.onError(t);
    }
    return action;
  }
  
  private void maybeWaitWithBackoff(int reconnectAttempts) {
    if (reconnectTimeMs > 0 && reconnectAttempts > 0) {
      try {
        long sleepTimeMs = backoffWithJitter(reconnectAttempts);
        logger.info("Waiting " + sleepTimeMs + " milliseconds before reconnecting...");
        Thread.sleep(sleepTimeMs);
      } catch (InterruptedException ignored) {
      }
    }
  }

  long backoffWithJitter(int reconnectAttempts) {
    long jitterVal = Math.min(MAX_RECONNECT_TIME_MS, reconnectTimeMs * pow2(reconnectAttempts));
    return jitterVal / 2 + nextLong(jitter, jitterVal) / 2;
  }

  // Returns 2**k, or Integer.MAX_VALUE if 2**k would overflow
  private int pow2(int k) {
    return (k < Integer.SIZE - 1) ? (1 << k) : Integer.MAX_VALUE;
  }

  // Adapted from http://stackoverflow.com/questions/2546078/java-random-long-number-in-0-x-n-range
  // Since ThreadLocalRandom.current().nextLong(n) requires Android 5
  private long nextLong(Random rand, long bound) {
    if (bound <= 0) {
      throw new IllegalArgumentException("bound must be positive");
    }

    long r = rand.nextLong() & Long.MAX_VALUE;
    long m = bound - 1L;
    if ((bound & m) == 0) { // i.e., bound is a power of 2
      r = (bound * r) >> (Long.SIZE - 1);
    } else {
      for (long u = r; u - (r = u % bound) + m < 0L; u = rand.nextLong() & Long.MAX_VALUE) ;
    }
    return r;
  }

  private static Headers addDefaultHeaders(Headers custom) {
    Headers.Builder builder = new Headers.Builder();

    builder.add("Accept", "text/event-stream").add("Cache-Control", "no-cache");

    for (Map.Entry<String, List<String>> header : custom.toMultimap().entrySet()) {
      for (String value : header.getValue()) {
        builder.add(header.getKey(), value);
      }
    }

    return builder.build();
  }

  public void setReconnectionTimeMs(long reconnectionTimeMs) {
    this.reconnectTimeMs = reconnectionTimeMs;
  }

  public void setLastEventId(String lastEventId) {
    this.lastEventId = lastEventId;
  }

  public URI getUri() {
    return this.uri;
  }

  public void setUri(URI uri) {
    this.uri = uri;
  }

  public static final class Builder {
    private String name = "";
    private long reconnectTimeMs = DEFAULT_RECONNECT_TIME_MS;
    private final URI uri;
    private final EventHandler handler;
    private ConnectionErrorHandler connectionErrorHandler = ConnectionErrorHandler.DEFAULT;
    private Headers headers = Headers.of();
    private Proxy proxy;
    private Authenticator proxyAuthenticator = null;
    private String method = "GET";
    @Nullable private RequestBody body = null;
    private OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
            .connectionPool(new ConnectionPool(1, 1, TimeUnit.SECONDS))
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DEFAULT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(DEFAULT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true);

    public Builder(EventHandler handler, URI uri) {
      this.uri = uri;
      this.handler = handler;
    }

    /**
     * Set the HTTP method used for this EventSource client to use for requests to establish the EventSource.
     *
     * Defaults to "GET".
     *
     * @param method the HTTP method name
     * @return the builder
     */
    public Builder method(String method) {
      if (method != null && method.length() > 0) {
        this.method = method.toUpperCase();
      }
      return this;
    }

    /**
     * Sets the request body to be used for this EventSource client to use for requests to establish the EventSource.
     * @param body the body to use in HTTP requests
     * @return the builder
     */
    public Builder body(@Nullable RequestBody body) {
      this.body = body;
      return this;
    }

    /**
     * Set the name for this EventSource client to be used when naming the logger and threadpools. This is mainly useful when
     * multiple EventSource clients exist within the same process.
     *
     * @param name the name (without any whitespaces)
     * @return the builder
     */
    public Builder name(String name) {
      if (name != null) {
        this.name = name;
      }
      return this;
    }

    /**
     * Set the reconnect base time for the EventSource connection in milliseconds. Reconnect attempts are computed
     * from this base value with an exponential backoff and jitter.
     *
     * @param reconnectTimeMs the reconnect base time in milliseconds
     * @return the builder
     */
    public Builder reconnectTimeMs(long reconnectTimeMs) {
      this.reconnectTimeMs = reconnectTimeMs;
      return this;
    }

    /**
     * Set the headers to be sent when establishing the EventSource connection.
     *
     * @param headers headers to be sent with the EventSource request
     * @return the builder
     */
    public Builder headers(Headers headers) {
      this.headers = headers;
      return this;
    }

    /**
     * Set a custom HTTP client that will be used to make the EventSource connection.
     * If you're setting this along with other connection-related items (ie timeouts, proxy),
     * you should do this first to avoid overwriting values.
     *
     * @param client the HTTP client
     * @return the builder
     */
    public Builder client(OkHttpClient client) {
      this.clientBuilder = client.newBuilder();
      return this;
    }

    /**
     * Set the HTTP proxy address to be used to make the EventSource connection
     *
     * @param proxyHost the proxy hostname
     * @param proxyPort the proxy port
     * @return the builder
     */
    public Builder proxy(String proxyHost, int proxyPort) {
      proxy = new Proxy(Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
      return this;
    }

    /**
     * Set the {@link Proxy} to be used to make the EventSource connection.
     *
     * @param proxy the proxy
     * @return the builder
     */
    public Builder proxy(Proxy proxy) {
      this.proxy = proxy;
      return this;
    }

    /**
     * Sets the Proxy Authentication mechanism if needed. Defaults to no auth.
     *
     * @param proxyAuthenticator
     * @return
     */
    public Builder proxyAuthenticator(Authenticator proxyAuthenticator) {
      this.proxyAuthenticator = proxyAuthenticator;
      return this;
    }

    /**
     * Sets the connect timeout in milliseconds if needed. Defaults to {@value #DEFAULT_CONNECT_TIMEOUT_MS}
     *
     * @param connectTimeoutMs
     * @return
     */
    public Builder connectTimeoutMs(int connectTimeoutMs) {
      this.clientBuilder.connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS);
      return this;
    }

    /**
     * Sets the write timeout in milliseconds if needed. Defaults to {@value #DEFAULT_WRITE_TIMEOUT_MS}
     *
     * @param writeTimeoutMs
     * @return
     */
    public Builder writeTimeoutMs(int writeTimeoutMs) {
      this.clientBuilder.writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS);
      return this;
    }

    /**
     * Sets the read timeout in milliseconds if needed. If a read timeout happens, the {@code EventSource}
     * will restart the connection. Defaults to {@value #DEFAULT_READ_TIMEOUT_MS}
     *
     * @param readTimeoutMs
     * @return
     */
    public Builder readTimeoutMs(int readTimeoutMs) {
      this.clientBuilder.readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS);
      return this;
    }

    /**
     * Sets the {@link ConnectionErrorHandler} that should process connection errors.
     *
     * @param handler
     * @return
     */
    public Builder connectionErrorHandler(ConnectionErrorHandler handler) {
      if (handler != null) {
        this.connectionErrorHandler = handler;
      }
      return this;
    }

    public EventSource build() {
      if (proxy != null) {
        clientBuilder.proxy(proxy);
      }

      try {
        clientBuilder.sslSocketFactory(new ModernTLSSocketFactory(), defaultTrustManager());
      } catch (GeneralSecurityException e) {
        // TLS is not available, so don't set up the socket factory, swallow the exception
      }

      if (proxyAuthenticator != null) {
        clientBuilder.proxyAuthenticator(proxyAuthenticator);
      }

      return new EventSource(this);
    }

    protected OkHttpClient.Builder getClientBuilder() {
      return clientBuilder;
    }

    private static X509TrustManager defaultTrustManager() throws GeneralSecurityException {
      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
              TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init((KeyStore) null);
      TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
      if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
        throw new IllegalStateException("Unexpected default trust managers:"
                + Arrays.toString(trustManagers));
      }
      return (X509TrustManager) trustManagers[0];
    }
  }
}
