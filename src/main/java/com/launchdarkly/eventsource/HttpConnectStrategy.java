package com.launchdarkly.eventsource;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import static com.launchdarkly.eventsource.Helpers.timeUnitOrDefault;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Allows configuration of HTTP request behavior for {@link EventSource}.
 * <p>
 * EventSource uses this as its default implementation of how to connect to a
 * stream. The underlying implementation is OkHttp.
 * <p>
 * If you do not need to specify any options other than the stream URI, then you
 * do not need to reference this class directly; you can just call one of the
 * {@link EventSource.Builder#Builder(URI)}.
 * <p>
 * To configure additional options, obtain an {@link HttpConnectStrategy}
 * instance by calling {@link ConnectStrategy#http(URI)}, and then call any
 * of the methods of this class to specify your options. The class is
 * immutable, so each of these methods returns a new modified instance,
 * and an EventSource created with this configuration will not be affected
 * by any subsequent changes you make.
 * <p>
 * Once you have configured all desired options, pass the object to the
 * {@link EventSource.Builder#Builder(ConnectStrategy)} constructor:
 * <pre><code>
 *   EventSource es = new EventSource.Builder(
 *     ConnectStrategy.http()
 *       .addHeader("Authorization", "xyz")
 *       .connectTimeout(3, TimeUnit.SECONDS)
 *     )
 *     .build();
 * </code></pre>
 *
 * @since 4.0.0
 */
public class HttpConnectStrategy extends ConnectStrategy {
  /**
   * The default value for {@link #connectTimeout(long, TimeUnit)}: 10 seconds.
   */
  public static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 10000;
  /**
   * The default value for {@link #writeTimeout(long, TimeUnit)}: 5 seconds.
   */
  public static final long DEFAULT_WRITE_TIMEOUT_MILLIS = 5000;
  /**
   * The default value for {@link #readTimeout(long, TimeUnit)}: 5 minutes.
   */
  public static final long DEFAULT_READ_TIMEOUT_MILLIS = 5000;
  
  private static final Headers DEFAULT_HEADERS = new Headers.Builder()
      .add("Accept", "text/event-stream")
      .add("Cache-Control", "no-cache")
      .build();
  
  final URI uri; // package-private visibility for tests
  private final ClientConfigurer clientConfigurer;
  private final OkHttpClient httpClient;
  private final RequestTransformer requestTransformer;

  
  /**
   * An interface for use with {@link #clientBuilderActions(ClientConfigurer)}.
   */
  public static interface ClientConfigurer {
    /**
     * This method is called with the OkHttp {@link okhttp3.OkHttpClient.Builder}
     * that will be used for the EventSource, allowing you to call any configuration
     * methods you want.
     * @param builder the client builder
     */
    public void configure(OkHttpClient.Builder builder);
  }

  /**
   * An interface for use with {@link #requestTransformer(RequestTransformer)}.
   */
  public static interface RequestTransformer {
    /**
     * Returns a request that is either the same as the input request or based on it.
     * @param input the original request
     * @return the request that will be used
     */
    public Request transformRequest(Request input);
  }
  
  HttpConnectStrategy(URI uri) {
    this(uri, null, null, null);
  }
  
  private HttpConnectStrategy(
      URI uri,
      ClientConfigurer clientConfigurer,
      OkHttpClient httpClient,
      RequestTransformer requestTransformer
      ) {
    if (uri == null) {
      throw new IllegalArgumentException("URI must not be null");
    }
    if (!uri.getScheme().equals("http") && !uri.getScheme().equals("https")) {
      throw new IllegalArgumentException("URI scheme must be http or https");
    }
    this.uri = uri;
    this.httpClient = httpClient;
    this.clientConfigurer = clientConfigurer;
    this.requestTransformer = requestTransformer;
  }

  @Override
  public Client createClient(LDLogger logger) {
    return new Client(logger);
  }

  // This method is used to chain together all actions that affect the HTTP client
  private HttpConnectStrategy addClientConfigurerAction(final ClientConfigurer addedAction) {
    if (addedAction == null) {
      return this;
    }
    ClientConfigurer compositeAction = clientConfigurer == null ? addedAction :
      new ClientConfigurer() {
        @Override
        public void configure(okhttp3.OkHttpClient.Builder builder) {
          clientConfigurer.configure(builder);
          addedAction.configure(builder);
        }
      };
    return new HttpConnectStrategy(
        this.uri,
        compositeAction,
        this.httpClient,
        this.requestTransformer
        );
  }

  // This method is used to chain together all actions that affect the HTTP request
  private HttpConnectStrategy addRequestTransformerAction(final RequestTransformer addedAction) {
    if (addedAction == null) {
      return this;
    }
    RequestTransformer compositeAction = requestTransformer == null ? addedAction :
      new RequestTransformer() {
        @Override
        public Request transformRequest(Request request) {
          return addedAction.transformRequest(
              requestTransformer.transformRequest(request));
        }
      };
    return new HttpConnectStrategy(
        this.uri,
        this.clientConfigurer,
        this.httpClient,
        compositeAction
        );
  }

  /**
   * Specifies any type of configuration actions you want to perform on the
   * OkHttpClient builder.
   * <p>
   * {@link ClientConfigurer} is an interface with a single method, so you can
   * use a lambda instead. EventSource will call this method when it creates an
   * HTTP client. If you call this method multiple times, or use it in combination
   * with other client configuration methods, the actions are performed in the
   * same order as the calls.
   * <pre><code>
   *   EventSource es = new EventSource.Builder(
   *     ConnectStrategy.http().clientBuilderActions(clientBuilder -&gt; {
   *       clientBuilder.sslSocketFactory(mySocketFactory, myTrustManager);
   *     });
   * </code></pre>
   *
   * @param configurer a ClientConfigurer (or lambda) that will act on the HTTP client
   *   builder
   * @return a new HttpConnectStrategy instance with this property modified
   */
  public HttpConnectStrategy clientBuilderActions(ClientConfigurer configurer) {
    return addClientConfigurerAction(configurer);
  }
  
  /**
   * Specifies the connection timeout.
   *
   * @param connectTimeout the connection timeout, in whatever time unit is specified by
   *   {@code timeUnit}
   * @param timeUnit the time unit, or {@code TimeUnit.MILLISECONDS} if null
   * @return a new HttpConnectStrategy instance with this property modified
   * @see #DEFAULT_CONNECT_TIMEOUT_MILLIS
   * @see #readTimeout(long, TimeUnit)
   * @see #writeTimeout(long, TimeUnit)
   */
  public HttpConnectStrategy connectTimeout(final long connectTimeout, final TimeUnit timeUnit) {
    return addClientConfigurerAction(new ClientConfigurer() {
      @Override
      public void configure(okhttp3.OkHttpClient.Builder builder) {
        builder.connectTimeout(connectTimeout, timeUnitOrDefault(timeUnit));
      }
    });
  }

  /**
   * Sets a custom header to be included in each request.
   * <p>
   * Any existing headers with the same name are overwritten.
   *
   * @param name the header name
   * @param value the header value
   * @return a new HttpConnectStrategy instance with this property modified
   */
  public HttpConnectStrategy header(String name, String value) {
    return addRequestTransformerAction(new RequestTransformer() {
      @Override
      public Request transformRequest(Request request) {
        Request.Builder builder = request.newBuilder();
        builder.header(name, value);
        return builder.build();
      }
    });
  }
  
  /**
   * Sets custom headers to be included in each request.
   * <p>
   * Any existing headers with the same name are overwritten.
   *
   * @param headers headers to be sent with the HTTP request
   * @return a new HttpConnectStrategy instance with this property modified
   */
  public HttpConnectStrategy headers(final Headers headers) {
    return addRequestTransformerAction(new RequestTransformer() {
      @Override
      public Request transformRequest(Request request) {
        Request.Builder builder = request.newBuilder();
        for (String name: headers.names()) {
          // Remove any previous default header with the same names
          builder.removeHeader(name);
          for (String value: headers.values(name)) {
            builder.addHeader(name, value);
          }
        }
        return builder.build();
      }
    });
  }

  /**
   * Set a custom HTTP client that will be used to make the EventSource connection.
   * <p>
   * If you use this method, all other methods that correspond to client configuration
   * properties (timeouts, proxy, etc.) will be ignored, and this client instance will
   * be used exactly as is. Closing the EventSource will not cause this client to be
   * closed; you are responsible for its lifecycle.
   *
   * @param httpClient the HTTP client, or null to allow EventSource to create a client
   * @return a new HttpConnectStrategy instance with this property modified
   */
  public HttpConnectStrategy httpClient(OkHttpClient httpClient) {
    return new HttpConnectStrategy(
        this.uri,
        this.clientConfigurer,
        httpClient,
        this.requestTransformer
        );
  }

  /**
   * Specifies the request method and body to be used for HTTP requests.
   *
   * The default is to use the GET method with no request body. A non-null
   * body is only valid for some request methods, such as POST.
   * 
   * @param method the HTTP method
   * @param body the request body or null
   * @return a new HttpConnectStrategy instance with these properties modified
   */
  public HttpConnectStrategy methodAndBody(final String method, final RequestBody body) {
    return addRequestTransformerAction(new RequestTransformer() {
      @Override
      public Request transformRequest(Request request) {
        return request.newBuilder().method(method, body).build();
      }
    });
  }

  /**
   * Specifies a web proxy to be used for all HTTP connections.
   *
   * @param proxy the proxy
   * @return a new HttpConnectStrategy instance with this property modified
   */
  public HttpConnectStrategy proxy(final Proxy proxy) {
    return addClientConfigurerAction(new ClientConfigurer() {
      @Override
      public void configure(okhttp3.OkHttpClient.Builder builder) {
        builder.proxy(proxy);
      }
    });
  }

  /**
   * Specifies a web proxy to be used for all HTTP connections.
   *
   * @param proxyHost the proxy hostname
   * @param proxyPort the proxy port
   * @return a new HttpConnectStrategy instance with this property modified
   */
  public HttpConnectStrategy proxy(String proxyHost, int proxyPort) {
    return proxy(new Proxy(Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
  }

  /**
   * Specifies a web proxy authenticator to be used for all HTTP connections.
   *
   * @param proxyAuthenticator the proxy authenticator
   * @return a new HttpConnectStrategy instance with this property modified
   */
  public HttpConnectStrategy proxyAuthenticator(final Authenticator proxyAuthenticator) {
    return addClientConfigurerAction(new ClientConfigurer() {
      @Override
      public void configure(okhttp3.OkHttpClient.Builder builder) {
        builder.proxyAuthenticator(proxyAuthenticator);
      }
    });
  }

  /**
   * Sets the read timeout. If a read timeout happens, the {@code EventSource}
   * will restart the connection.
   *
   * @param readTimeout the read timeout, in whatever time unit is specified by {@code timeUnit}
   * @param timeUnit the time unit, or {@code TimeUnit.MILLISECONDS} if null
   * @return a new HttpConnectStrategy instance with this property modified
   * @see #DEFAULT_READ_TIMEOUT_MILLIS
   * @see #connectTimeout(long, TimeUnit)
   * @see #writeTimeout(long, TimeUnit)
   */
  public HttpConnectStrategy readTimeout(final long readTimeout, final TimeUnit timeUnit) {
    return addClientConfigurerAction(new ClientConfigurer() {
      @Override
      public void configure(okhttp3.OkHttpClient.Builder builder) {
        builder.readTimeout(readTimeout, timeUnitOrDefault(timeUnit));
      }
    });
  }

  /**
   * Specifies an object that will be used to customize outgoing requests.
   * <p>
   * Use this if you need to set request properties other than the ones that are
   * already supported by {@link HttpConnectStrategy} methods, or if you need to
   * determine the request properties dynamically rather than setting them to
   * fixed values initially.
   * <pre><code>
   *   EventSource es = new EventSource.Builder(
   *     ConnectStrategy.http().requestTransformer(request -&gt; {
   *       return request.newBuilder().tag("hello").build();
   *     }).build();
   * </code></pre>
   * <p>
   * If you call this method multiple times, the transformations are applied in
   * the same order as the calls.
   * 
   * @param requestTransformer the transformer object
   * @return a new HttpConnectStrategy instance with this property modified
   */
  public HttpConnectStrategy requestTransformer(RequestTransformer requestTransformer) {
    return addRequestTransformerAction(requestTransformer);
  }

  /**
   * Specifies a different stream URI.
   *
   * @param uri the stream URI
   * @return a new HttpConnectStrategy instance with this property modified
   */
  public HttpConnectStrategy uri(URI uri) {
    return new HttpConnectStrategy(
        uri,
        this.clientConfigurer,
        this.httpClient,
        this.requestTransformer
        );
  }

  /**
   * Sets the write timeout. If a write timeout happens, the {@code EventSource}
   * will restart the connection.
   *
   * @param writeTimeout the write timeout, in whatever time unit is specified by {@code timeUnit}
   * @param timeUnit the time unit, or {@code TimeUnit.MILLISECONDS} if null
   * @return a new HttpConnectStrategy instance with this property modified
   * @see #DEFAULT_READ_TIMEOUT_MILLIS
   * @see #connectTimeout(long, TimeUnit)
   * @see #readTimeout(long, TimeUnit)
   */
  public HttpConnectStrategy writeTimeout(long writeTimeout, TimeUnit timeUnit) {
    return addClientConfigurerAction(new ClientConfigurer() {
      @Override
      public void configure(okhttp3.OkHttpClient.Builder builder) {
        builder.writeTimeout(writeTimeout, timeUnitOrDefault(timeUnit));
      }
    });
  }

  class Client extends ConnectStrategy.Client { // package-private visibility for tests
    final OkHttpClient httpClient; // package-private visibility for tests
    private final LDLogger logger;
    
    // Instead of duplicating the HttpConnectStrategy fields here, we'll just reference
    // them directly via HttpConnectStrategy.this-- that's safe because it's immutable.

    Client(LDLogger logger) {
      this.logger = logger;
      this.httpClient = createHttpClient();
    }
    
    @Override
    public Result connect(String lastEventId) throws StreamException {
      logger.debug("Attempting to connect to SSE stream at {}", uri);
      
      Request request = createRequest(lastEventId);
      Call call = httpClient.newCall(request);
      
      Response response;
      try {
        response = call.execute();
      } catch (IOException e) {
        logger.info("Connection failed: {}", LogValues.exceptionSummary(e));
        throw new StreamIOException(e);
      }
        
      if (!response.isSuccessful()) {
        response.close();
        logger.info("Server returned HTTP error {}", response.code());
        throw new StreamHttpErrorException(response.code());
      }
  
      ResponseBody responseBody = response.body();
      return new Result(
          responseBody.byteStream(),
          uri,
          new RequestCloser(call),
        new ResponseCloser(response)
          );
    }
    
    public void close() {
      // We need to shut down the HTTP client *if* it is one that we created, and not
      // one that the application provided to us.
      OkHttpClient preconfiguredClient = HttpConnectStrategy.this.httpClient;      
      if (preconfiguredClient == null) {
        // COVERAGE: these null guards are here for safety but in practice the values are never null and there
        // is no way to cause them to be null in unit tests
        if (httpClient.connectionPool() != null) {
          httpClient.connectionPool().evictAll();
        }
        if (httpClient.dispatcher() != null) {
          httpClient.dispatcher().cancelAll();
          if (httpClient.dispatcher().executorService() != null) {
            httpClient.dispatcher().executorService().shutdownNow();
          }
        }
      }
    }

    @Override
    public boolean awaitClosed(long timeoutMillis) throws InterruptedException {
      final long deadline = System.currentTimeMillis() + timeoutMillis;
      if (httpClient.dispatcher().executorService() != null) {
        long shutdownTimeoutMills = Math.max(0, deadline - System.currentTimeMillis());
        if (!httpClient.dispatcher().executorService().awaitTermination(shutdownTimeoutMills, TimeUnit.MILLISECONDS)) {
          return false; // COVERAGE: this condition can't be reproduced in unit tests
        }
      }
      return true;
    }

    private OkHttpClient createHttpClient() {
      if (HttpConnectStrategy.this.httpClient != null) {
        // We're configured to use a specific pre-existing client instance
        return HttpConnectStrategy.this.httpClient;
      }
      
      OkHttpClient.Builder builder = new OkHttpClient.Builder()
          .connectionPool(new ConnectionPool(1, 1, TimeUnit.SECONDS))
          .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
          .readTimeout(DEFAULT_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
          .writeTimeout(DEFAULT_WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
          .retryOnConnectionFailure(false);
      try {
        builder.sslSocketFactory(new ModernTLSSocketFactory(), defaultTrustManager());
      } catch (GeneralSecurityException e) {
        // TLS is not available, so don't set up the socket factory, swallow the exception
        // COVERAGE: There is no way to cause this to happen in unit tests
      }
      if (clientConfigurer != null) {
        clientConfigurer.configure(builder);
      }
      return builder.build();
    }
    
    @Override
    public URI getOrigin() {
      return uri;
    }
    
    private Request createRequest(String lastEventId) {
      Request.Builder baseBuilder = new Request.Builder()
          .url(HttpUrl.get(uri))
          .headers(DEFAULT_HEADERS);
      if (lastEventId != null && !lastEventId.isEmpty()) {
        baseBuilder.addHeader("Last-Event-ID", lastEventId);
      }
      return requestTransformer == null ? baseBuilder.build() :
        requestTransformer.transformRequest(baseBuilder.build());
    }
  }

  private static class RequestCloser implements Closeable {
    private final Call call;
    
    RequestCloser(Call call) {
      this.call = call;
    }
    
    @Override
    public void close() throws IOException {
      // EventSource calls this if it is deliberately stopping the stream via stop() or interrupt().
      call.cancel();
    }
  }

  private static class ResponseCloser implements Closeable {
    private final Response response;

    public ResponseCloser(Response response) {
      this.response = response;
    }

    @Override
    public void close() throws IOException {
      this.response.close();
    }
  }

  private static X509TrustManager defaultTrustManager() throws GeneralSecurityException {
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init((KeyStore) null);
    TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
    if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
      // COVERAGE: There is no way to cause this to happen in unit tests
      throw new IllegalStateException("Unexpected default trust managers:"
              + Arrays.toString(trustManagers));
    }
    return (X509TrustManager) trustManagers[0];
  }
}
