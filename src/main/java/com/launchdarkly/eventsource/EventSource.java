package com.launchdarkly.eventsource;

import okhttp3.*;
import okio.BufferedSource;
import okio.Okio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
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
  static final long DEFAULT_MAX_RECONNECT_TIME_MS = 30000;
  static final int DEFAULT_CONNECT_TIMEOUT_MS = 10000;
  static final int DEFAULT_WRITE_TIMEOUT_MS = 5000;
  static final int DEFAULT_READ_TIMEOUT_MS = 1000 * 60 * 5;
  static final int DEFAULT_BACKOFF_RESET_THRESHOLD_MS = 1000 * 60;

  private final String name;
  private volatile HttpUrl url;
  private final Headers headers;
  private final String method;
  @Nullable private final RequestBody body;
  private final RequestTransformer requestTransformer;
  private final ExecutorService eventExecutor;
  private final ExecutorService streamExecutor;
  private long reconnectTimeMs;
  private long maxReconnectTimeMs;
  private final long backoffResetThresholdMs;
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
    this.logger = LoggerFactory.getLogger(EventSource.class.getCanonicalName() + "." + name);
    this.url = builder.url;
    this.headers = addDefaultHeaders(builder.headers);
    this.method = builder.method;
    this.body = builder.body;
    this.requestTransformer = builder.requestTransformer;
    this.reconnectTimeMs = builder.reconnectTimeMs;
    this.maxReconnectTimeMs = builder.maxReconnectTimeMs;
    this.backoffResetThresholdMs = builder.backoffResetThresholdMs;
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
        thread.setDaemon(true);
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
    logger.info("Starting EventSource client using URI: " + url);
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
        .url(url)
        .method(method, body);

    if (lastEventId != null && !lastEventId.isEmpty()) {
      builder.addHeader("Last-Event-ID", lastEventId);
    }
    
    Request request = builder.build();
    return requestTransformer == null ? request : requestTransformer.transformRequest(request);
  }

  private void connect() {
    response = null;
    bufferedSource = null;

    int reconnectAttempts = 0;
    ConnectionErrorHandler.Action errorHandlerAction = null;
    
    try {
      while (!Thread.currentThread().isInterrupted() && readyState.get() != SHUTDOWN) {
        long connectedTime = -1;

        ReadyState currentState = readyState.getAndSet(CONNECTING);
        logger.debug("readyState change: " + currentState + " -> " + CONNECTING);
        try {        	  
          call = client.newCall(buildRequest());
          response = call.execute();
          if (response.isSuccessful()) {
            connectedTime = System.currentTimeMillis();
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
            EventParser parser = new EventParser(url.uri(), handler, EventSource.this);
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
          // Reset the backoff if we had a successful connection that stayed good for at least
          // backoffResetThresholdMs milliseconds.
          if (connectedTime >= 0 && (System.currentTimeMillis() - connectedTime) >= backoffResetThresholdMs) {
            reconnectAttempts = 0;
          }
          maybeWaitWithBackoff(++reconnectAttempts);
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
    long jitterVal = Math.min(maxReconnectTimeMs, reconnectTimeMs * pow2(reconnectAttempts));
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

  public void setMaxReconnectTimeMs(long maxReconnectTimeMs) {
    this.maxReconnectTimeMs = maxReconnectTimeMs;
  }

  public long getMaxReconnectTimeMs() {
    return this.maxReconnectTimeMs;
  }

  public void setLastEventId(String lastEventId) {
    this.lastEventId = lastEventId;
  }

  /**
   * Returns the current stream endpoint as an OkHttp HttpUrl.
   * 
   * @since 1.9.0
   */
  public HttpUrl getHttpUrl() {
    return this.url;
  }
  
  /**
   * Returns the current stream endpoint as a java.net.URI.
   */
  public URI getUri() {
    return this.url.uri();
  }

  /**
   * Changes the stream endpoint. This change will not take effect until the next time the
   * EventSource attempts to make a connection.
   * 
   * @param url the new endpoint, as an OkHttp HttpUrl
   * @throws IllegalArgumentException if the parameter is null or if the scheme is not HTTP or HTTPS
   * 
   * @since 1.9.0
   */
  public void setHttpUrl(HttpUrl url) {
    if (url == null) {
      throw badUrlException();
    }
    this.url = url;
  }
  
  /**
   * Changes the stream endpoint. This change will not take effect until the next time the
   * EventSource attempts to make a connection.
   * 
   * @param url the new endpoint, as a java.net.URI
   * @throws IllegalArgumentException if the parameter is null or if the scheme is not HTTP or HTTPS
   */
  public void setUri(URI uri) {
    setHttpUrl(uri == null ? null : HttpUrl.get(uri));
  }

  private static IllegalArgumentException badUrlException() {
    return new IllegalArgumentException("URI/URL must not be null and must be HTTP or HTTPS");
  }
  
  /**
   * Interface for an object that can modify the network request that the EventSource will make.
   * Use this in conjunction with {@link Builder#requestTransformer} if you need to set request
   * properties other than the ones that are already supported by the builder (or if, for
   * whatever reason, you need to determine the request properties dynamically rather than
   * setting them to fixed values initially). For example:
   * <code>
   * public class RequestTagger implements EventSource.RequestTransformer {
   *   public Request transformRequest(Request input) {
   *     return input.newBuilder().tag("hello").build();
   *   }
   * }
   * 
   * EventSource es = new EventSource.Builder(handler, uri).requestTransformer(new RequestTagger()).build();
   * </code>
   * 
   * @since 1.9.0
   */
  public static interface RequestTransformer {
    /**
     * Returns a request that is either the same as the input request or based on it. When
     * this method is called, EventSource has already set all of its standard properties on
     * the request.
     * 
     * @param input the original request
     * @return the request that will be used
     */
    public Request transformRequest(Request input);
  }
  
  /**
   * Builder for {@link EventSource}.
   */
  public static final class Builder {
    private String name = "";
    private long reconnectTimeMs = DEFAULT_RECONNECT_TIME_MS;
    private long maxReconnectTimeMs = DEFAULT_MAX_RECONNECT_TIME_MS;
    private long backoffResetThresholdMs = DEFAULT_BACKOFF_RESET_THRESHOLD_MS;
    private final HttpUrl url;
    private final EventHandler handler;
    private ConnectionErrorHandler connectionErrorHandler = ConnectionErrorHandler.DEFAULT;
    private Headers headers = Headers.of();
    private Proxy proxy;
    private Authenticator proxyAuthenticator = null;
    private String method = "GET";
    private RequestTransformer requestTransformer = null;
    @Nullable private RequestBody body = null;
    private OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
            .connectionPool(new ConnectionPool(1, 1, TimeUnit.SECONDS))
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DEFAULT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(DEFAULT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true);

    /**
     * Creates a new builder.
     * 
     * @param handler the event handler
     * @param uri the endpoint as a java.net.URI
     * @throws IllegalArgumentException if either argument is null, or if the endpoint is not HTTP or HTTPS
     */
    public Builder(EventHandler handler, URI uri) {
      this(handler, uri == null ? null : HttpUrl.get(uri));
    }

    /**
     * Creates a new builder.
     * 
     * @param handler the event handler
     * @param uri the endpoint as an OkHttp HttpUrl
     * @throws IllegalArgumentException if either argument is null, or if the endpoint is not HTTP or HTTPS
     * 
     * @since 1.9.0
     */
    public Builder(EventHandler handler, HttpUrl url) {
      if (handler == null) {
        throw new IllegalArgumentException("handler must not be null");
      }
      if (url == null) {
        throw badUrlException();
      }
      this.url = url;
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
     * Specifies an object that will be used to customize outgoing requests. See {@link RequestTransformer} for details.
     * 
     * @param requestTransformer the transformer object
     * @return the builder
     * 
     * @since 1.9.0
     */
    public Builder requestTransformer(@Nullable RequestTransformer requestTransformer) {
      this.requestTransformer = requestTransformer;
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
     * Set the max reconnect time for the EventSource connection in milliseconds.  The exponential backoff computed
     * for reconnect attempts will not be larger than this value.  Defaults to 30000 ms (30 seconds).
     *
     * @param maxReconnectTimeMs the maximum reconnect base time in milliseconds
     * @return the builder
     */
    public Builder maxReconnectTimeMs(long maxReconnectTimeMs) {
      this.maxReconnectTimeMs = maxReconnectTimeMs;
      return this;
    }

    /**
     * Sets the minimum amount of time that a connection must stay open before the EventSource resets its
     * backoff delay. If a connection fails before the threshold has elapsed, the delay before reconnecting
     * will be greater than the last delay; if it fails after the threshold, the delay will start over at
     * the initial minimum value. This prevents long delays from occurring on connections that are only
     * rarely restarted.
     *   
     * @param backoffResetThresholdMs the minimum time in milliseconds that a connection must stay open to
     *   avoid resetting the delay 
     * @return the builder
     * 
     * @since 1.9.0
     */
    public Builder backoffResetThresholdMs(long backoffResetThresholdMs) {
      this.backoffResetThresholdMs = backoffResetThresholdMs;
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

    /**
     * Constructs an {@link EventSource} using the builder's current properties.
     * @return the new EventSource instance
     */
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
