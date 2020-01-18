package com.launchdarkly.eventsource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import static com.launchdarkly.eventsource.ReadyState.CLOSED;
import static com.launchdarkly.eventsource.ReadyState.CONNECTING;
import static com.launchdarkly.eventsource.ReadyState.OPEN;
import static com.launchdarkly.eventsource.ReadyState.RAW;
import static com.launchdarkly.eventsource.ReadyState.SHUTDOWN;
import static java.lang.String.format;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;
import okio.Okio;

/**
 * Client for <a href="https://www.w3.org/TR/2015/REC-eventsource-20150203/">Server-Sent Events</a>
 * aka EventSource
 */
public class EventSource implements ConnectionHandler, Closeable {
  private final Logger logger;

  /**
   * The default value for {@link Builder#reconnectTime(Duration)}: 1 second.
   */
  public static final Duration DEFAULT_RECONNECT_TIME = Duration.ofSeconds(1);
  /**
   * The default value for {@link Builder#maxReconnectTime(Duration)}: 30 seconds.
   */
  public static final Duration DEFAULT_MAX_RECONNECT_TIME = Duration.ofSeconds(30);
  /**
   * The default value for {@link Builder#connectTimeout(Duration)}: 10 seconds.
   */
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
  /**
   * The default value for {@link Builder#writeTimeout(Duration)}: 5 seconds.
   */
  public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ofSeconds(5);
  /**
   * The default value for {@link Builder#readTimeout(Duration)}: 5 minutes.
   */
  public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMinutes(5);
  /**
   * The default value for {@link Builder#backoffResetThreshold(Duration)}: 60 seconds.
   */
  public static final Duration DEFAULT_BACKOFF_RESET_THRESHOLD = Duration.ofSeconds(60);

  private static final Headers defaultHeaders =
      new Headers.Builder().add("Accept", "text/event-stream").add("Cache-Control", "no-cache").build();
  
  private final String name;
  private volatile HttpUrl url;
  private final Headers headers;
  private final String method;
  private final RequestBody body;
  private final RequestTransformer requestTransformer;
  private final ExecutorService eventExecutor;
  private final ExecutorService streamExecutor;
  private Duration reconnectTime;
  private Duration maxReconnectTime;
  private final Duration backoffResetThreshold;
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
    String loggerName = EventSource.class.getCanonicalName() +
        (name == null || name.equals("") ? "" : "." + name);
    this.logger = LoggerFactory.getLogger(loggerName);
    this.url = builder.url;
    this.headers = addDefaultHeaders(builder.headers);
    this.method = builder.method;
    this.body = builder.body;
    this.requestTransformer = builder.requestTransformer;
    this.reconnectTime = builder.reconnectTime;
    this.maxReconnectTime = builder.maxReconnectTime;
    this.backoffResetThreshold = builder.backoffResetThreshold;
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
    return runnable -> {
      Thread thread = backingThreadFactory.newThread(runnable);
      thread.setName(format(Locale.ROOT, "%s-[%s]-%d", type, name, count.getAndIncrement()));
      thread.setDaemon(true);
      return thread;
    };
  }

  /**
   * Attempts to connect to the remote event source if not already connected. This method returns
   * immediately; the connection happens on a worker thread.
   */
  public void start() {
    if (!readyState.compareAndSet(RAW, CONNECTING)) {
      logger.info("Start method called on this already-started EventSource object. Doing nothing");
      return;
    }
    logger.debug("readyState change: " + RAW + " -> " + CONNECTING);
    logger.info("Starting EventSource client using URI: " + url);
    streamExecutor.execute(this::connect);
  }

  /**
   * Returns an enum indicating the current status of the connection.
   * @return a {@link ReadyState} value
   */
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
          if (connectedTime >= 0 && (System.currentTimeMillis() - connectedTime) >= backoffResetThreshold.toMillis()) {
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
    if (!reconnectTime.isZero() && !reconnectTime.isNegative() && reconnectAttempts > 0) {
      try {
        Duration sleepTime = backoffWithJitter(reconnectAttempts);
        logger.info("Waiting " + sleepTime.toMillis() + " milliseconds before reconnecting...");
        Thread.sleep(sleepTime.toMillis());
      } catch (InterruptedException ignored) {
      }
    }
  }

  Duration backoffWithJitter(int reconnectAttempts) {
    long jitterVal = Math.min(maxReconnectTime.toMillis(), reconnectTime.toMillis() * pow2(reconnectAttempts));
    return Duration.ofMillis(jitterVal / 2 + nextLong(jitter, jitterVal) / 2);
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

    for (String name : defaultHeaders.names()) {
      if (!custom.names().contains(name)) { // skip the default if they set any custom values for this key
        for (String value: defaultHeaders.values(name)) {
          builder.add(name, value);         
        }
      }
    }

    for (String name : custom.names()) {
      for (String value : custom.values(name)) {
        builder.add(name, value);
      }
    }

    return builder.build();
  }

  /**
   * Sets the minimum delay between connection attempts. The actual delay may be slightly less or
   * greater, since there is a random jitter. When there is a connection failure, the delay will
   * start at this value and will increase exponentially up to the {@link #setMaxReconnectTime(Duration)}
   * value with each subsequent failure, unless it is reset as described in
   * {@link Builder#backoffResetThreshold(Duration)}.
   * @param reconnectionTime the minimum delay; null to use the default
   * @see #setMaxReconnectTime(Duration)
   * @see Builder#reconnectTime(Duration)
   * @see #DEFAULT_RECONNECT_TIME
   */
  public void setReconnectionTime(Duration reconnectionTime) {
    this.reconnectTime = reconnectionTime == null ? DEFAULT_RECONNECT_TIME : reconnectionTime;
  }

  /**
   * Sets the maximum delay between connection attempts. See {@link #setReconnectionTime(Duration)}.
   * The default value is 30 seconds.
   * @param maxReconnectTime the maximum delay; null to use the default
   * @see #setReconnectionTime(Duration)
   * @see Builder#maxReconnectTime(Duration)
   * @see #DEFAULT_MAX_RECONNECT_TIME
   */
  public void setMaxReconnectTime(Duration maxReconnectTime) {
    this.maxReconnectTime = maxReconnectTime == null ? DEFAULT_MAX_RECONNECT_TIME : maxReconnectTime;
  }

  /**
   * Returns the current maximum reconnect delay as set by {@link #setReconnectionTime(Duration)}.
   * @return the maximum delay
   */
  public Duration getMaxReconnectTime() {
    return this.maxReconnectTime;
  }

  /**
   * Sets the ID value of the last event received. This will be sent to the remote server on the
   * next connection attempt.
   * @param lastEventId the last event identifier
   */
  public void setLastEventId(String lastEventId) {
    this.lastEventId = lastEventId;
  }

  /**
   * Returns the current stream endpoint as an OkHttp HttpUrl.
   * @return the endpoint URL
   * @since 1.9.0
   * @see #getUri()
   * @see #setHttpUrl(HttpUrl)
   */
  public HttpUrl getHttpUrl() {
    return this.url;
  }
  
  /**
   * Returns the current stream endpoint as a java.net.URI.
   * @return the endpoint URI
   * @see #getHttpUrl()
   * @see #setUri(URI)
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
   * @see #getHttpUrl()
   * @see #setUri(URI)
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
   * @param uri the new endpoint, as a java.net.URI
   * @throws IllegalArgumentException if the parameter is null or if the scheme is not HTTP or HTTPS
   * @see #getUri()
   * @see #setHttpUrl(HttpUrl)
   */
  public void setUri(URI uri) {
    setHttpUrl(uri == null ? null : HttpUrl.get(uri));
  }

  private static IllegalArgumentException badUrlException() {
    return new IllegalArgumentException("URI/URL must not be null and must be HTTP or HTTPS");
  }
  
  /**
   * Interface for an object that can modify the network request that the EventSource will make.
   * Use this in conjunction with {@link EventSource.Builder#requestTransformer(EventSource.RequestTransformer)}
   * if you need to set request properties other than the ones that are already supported by the builder (or if,
   * for whatever reason, you need to determine the request properties dynamically rather than setting them
   * to fixed values initially). For example:
   * <pre><code>
   * public class RequestTagger implements EventSource.RequestTransformer {
   *   public Request transformRequest(Request input) {
   *     return input.newBuilder().tag("hello").build();
   *   }
   * }
   * 
   * EventSource es = new EventSource.Builder(handler, uri).requestTransformer(new RequestTagger()).build();
   * </code></pre>
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
    private Duration reconnectTime = DEFAULT_RECONNECT_TIME;
    private Duration maxReconnectTime = DEFAULT_MAX_RECONNECT_TIME;
    private Duration backoffResetThreshold = DEFAULT_BACKOFF_RESET_THRESHOLD;
    private final HttpUrl url;
    private final EventHandler handler;
    private ConnectionErrorHandler connectionErrorHandler = ConnectionErrorHandler.DEFAULT;
    private Headers headers = Headers.of();
    private Proxy proxy;
    private Authenticator proxyAuthenticator = null;
    private String method = "GET";
    private RequestTransformer requestTransformer = null;
    private RequestBody body = null;
    private OkHttpClient.Builder clientBuilder;
    
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
     * @param url the endpoint as an OkHttp HttpUrl
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
      this.clientBuilder = createInitialClientBuilder();
    }
    
    private static OkHttpClient.Builder createInitialClientBuilder() {
      OkHttpClient.Builder b = new OkHttpClient.Builder()
          .connectionPool(new ConnectionPool(1, 1, TimeUnit.SECONDS))
          .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
          .readTimeout(DEFAULT_READ_TIMEOUT)
          .writeTimeout(DEFAULT_WRITE_TIMEOUT)
          .retryOnConnectionFailure(true);
      try {
        b.sslSocketFactory(new ModernTLSSocketFactory(), defaultTrustManager());
      } catch (GeneralSecurityException e) {
        // TLS is not available, so don't set up the socket factory, swallow the exception
      }
      return b;
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
    public Builder body(RequestBody body) {
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
    public Builder requestTransformer(RequestTransformer requestTransformer) {
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
     * Sets the minimum delay between connection attempts. The actual delay may be slightly less or
     * greater, since there is a random jitter. When there is a connection failure, the delay will
     * start at this value and will increase exponentially up to the {@link #setMaxReconnectTime(Duration)}
     * value with each subsequent failure, unless it is reset as described in
     * {@link Builder#backoffResetThreshold(Duration)}.
     * @param reconnectTime the minimum delay; null to use the default
     * @return the builder
     * @see EventSource#DEFAULT_RECONNECT_TIME
     * @see EventSource#setReconnectionTime(Duration)
     */
    public Builder reconnectTime(Duration reconnectTime) {
      this.reconnectTime = reconnectTime == null ? DEFAULT_RECONNECT_TIME : reconnectTime;
      return this;
    }

    /**
     * Sets the maximum delay between connection attempts. See {@link #setReconnectionTime(Duration)}.
     * The default value is 30 seconds.
     * @param maxReconnectTime the maximum delay; null to use the default
     * @return the builder
     * @see EventSource#DEFAULT_MAX_RECONNECT_TIME
     * @see EventSource#setMaxReconnectTime(Duration)
     */
    public Builder maxReconnectTime(Duration maxReconnectTime) {
      this.maxReconnectTime = maxReconnectTime == null ? DEFAULT_MAX_RECONNECT_TIME : maxReconnectTime;
      return this;
    }

    /**
     * Sets the minimum amount of time that a connection must stay open before the EventSource resets its
     * backoff delay. If a connection fails before the threshold has elapsed, the delay before reconnecting
     * will be greater than the last delay; if it fails after the threshold, the delay will start over at
     * the initial minimum value. This prevents long delays from occurring on connections that are only
     * rarely restarted.
     *   
     * @param backoffResetThreshold the minimum time that a connection must stay open to avoid resetting
     *   the delay; null to use the default 
     * @return the builder
     * @see EventSource#DEFAULT_BACKOFF_RESET_THRESHOLD
     */
    public Builder backoffResetThreshold(Duration backoffResetThreshold) {
      this.backoffResetThreshold = backoffResetThreshold == null ? DEFAULT_BACKOFF_RESET_THRESHOLD : backoffResetThreshold;
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
     * @param proxyAuthenticator the authentication mechanism
     * @return the builder
     */
    public Builder proxyAuthenticator(Authenticator proxyAuthenticator) {
      this.proxyAuthenticator = proxyAuthenticator;
      return this;
    }

    /**
     * Sets the connection timeout.
     *
     * @param connectTimeout the connection timeout; null to use the default
     * @return the builder
     * @see EventSource#DEFAULT_CONNECT_TIMEOUT
     */
    public Builder connectTimeout(Duration connectTimeout) {
      this.clientBuilder.connectTimeout(connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout);
      return this;
    }

    /**
     * Sets the write timeout.
     *
     * @param writeTimeout the write timeout; null to use the default
     * @return the builder
     * @see EventSource#DEFAULT_WRITE_TIMEOUT
     */
    public Builder writeTimeout(Duration writeTimeout) {
      this.clientBuilder.writeTimeout(writeTimeout == null ? DEFAULT_WRITE_TIMEOUT : writeTimeout);
      return this;
    }

    /**
     * Sets the read timeout. If a read timeout happens, the {@code EventSource}
     * will restart the connection.
     *
     * @param readTimeout the read timeout; null to use the default
     * @return the builder
     * @see EventSource#DEFAULT_READ_TIMEOUT
     */
    public Builder readTimeout(Duration readTimeout) {
      this.clientBuilder.readTimeout(readTimeout == null ? DEFAULT_READ_TIMEOUT : readTimeout); 
      return this;
    }

    /**
     * Sets the {@link ConnectionErrorHandler} that should process connection errors.
     *
     * @param handler the error handler
     * @return the builder
     */
    public Builder connectionErrorHandler(ConnectionErrorHandler handler) {
      if (handler != null) {
        this.connectionErrorHandler = handler;
      }
      return this;
    }

    /**
     * Specifies any type of configuration actions you want to perform on the OkHttpClient builder.
     * <p>
     * {@link ClientConfigurer} is an interface with a single method, {@link ClientConfigurer#configure(okhttp3.OkHttpClient.Builder)},
     * that will be called with the {@link okhttp3.OkHttpClient.Builder} instance being used by EventSource.
     * In Java 8, this can be a lambda.
     * <p>
     * It is not guaranteed to be called in any particular order relative to other configuration
     * actions specified by this Builder, so if you are using more than one method, do not attempt
     * to overwrite the same setting in two ways.
     * <pre><code>
     *     // Java 8 example (lambda)
     *     eventSourceBuilder.clientBuilderActions(b -&gt; {
     *         b.sslSocketFactory(mySocketFactory, myTrustManager);
     *     });
     * 
     *     // Java 7 example (anonymous class)
     *     eventSourceBuilder.clientBuilderActions(new EventSource.Builder.ClientConfigurer() {
     *         public void configure(OkHttpClient.Builder v) {
     *             b.sslSocketFactory(mySocketFactory, myTrustManager);
     *         }
     *     });
     * </code></pre>
     * @param configurer a ClientConfigurer (or lambda) that will act on the HTTP client builder
     * @return the builder
     * @since 1.10.0
     */
    public Builder clientBuilderActions(ClientConfigurer configurer) {
      configurer.configure(clientBuilder);
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
    
    /**
     * An interface for use with {@link EventSource.Builder#clientBuilderActions(ClientConfigurer)}.
     * @since 1.10.0
     */
    public static interface ClientConfigurer {
      /**
       * This method is called with the OkHttp {@link okhttp3.OkHttpClient.Builder} that will be used for
       * the EventSource, allowing you to call any configuration methods you want.
       * @param builder the client builder
       */
      public void configure(OkHttpClient.Builder builder);
    }
  }
}
