package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.HttpConnectStrategy.ClientConfigurer;
import com.launchdarkly.eventsource.HttpConnectStrategy.RequestTransformer;
import com.launchdarkly.testhelpers.httptest.Handler;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;
import com.launchdarkly.testhelpers.tcptest.TcpHandlers;
import com.launchdarkly.testhelpers.tcptest.TcpServer;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.fail;

import okhttp3.Authenticator;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;

/**
 * Tests of the basic client/request configuration methods and HTTP functionality
 * in HttpConnectStrategy, using an embedded HTTP server as a target, but without
 * using EventSource. 
 */
@SuppressWarnings("javadoc")
public class HttpConnectStrategyTest {
  private static final URI TEST_URI = URI.create("http://test/uri");

  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();

  @Test
  public void createWithUri() {
    assertThat(ConnectStrategy.http(TEST_URI).uri, equalTo(TEST_URI));
  }
  
  @Test
  public void createWithUrl() throws Exception {
    assertThat(ConnectStrategy.http(TEST_URI.toURL()).uri, equalTo(TEST_URI));
  }
  
  @Test
  public void createWithHttpUrl() {
    assertThat(ConnectStrategy.http(HttpUrl.get(TEST_URI)).uri, equalTo(TEST_URI));
  }

  @Test(expected = IllegalArgumentException.class)
  public void createWithInvalidUriScheme() {
    ConnectStrategy.http(URI.create("ftp://no"));
  }
  
  @Test
  public void defaultClientProperties() throws Exception {
    OkHttpClient client = makeClientFrom(baseHttp());
    
    assertThat(client.connectTimeoutMillis(),
        equalTo((int)HttpConnectStrategy.DEFAULT_CONNECT_TIMEOUT_MILLIS));
    assertThat(client.readTimeoutMillis(),
        equalTo((int)HttpConnectStrategy.DEFAULT_READ_TIMEOUT_MILLIS));
    assertThat(client.writeTimeoutMillis(),
        equalTo((int)HttpConnectStrategy.DEFAULT_WRITE_TIMEOUT_MILLIS));
    assertThat(client.proxy(), nullValue());
  }

  @Test
  public void clientTimeouts() throws Exception {
    OkHttpClient client = makeClientFrom(baseHttp()
        .connectTimeout(100, TimeUnit.MILLISECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS));

    assertThat(client.connectTimeoutMillis(), equalTo(100));
    assertThat(client.readTimeoutMillis(), equalTo(1000));
    assertThat(client.writeTimeoutMillis(), equalTo(10000));
  }
  
  @Test
  public void clientProxy() throws Exception {
    Proxy proxy = new Proxy(Type.HTTP, new InetSocketAddress("http://proxy.example.com", 8080));
    OkHttpClient client = makeClientFrom(baseHttp().proxy(proxy));

    assertThat(client.proxy(), sameInstance(proxy));
  }

  @Test
  public void clientProxyHostAndPort() throws Exception {
    String proxyHost = "https://launchdarkly.com";
    int proxyPort = 8080;
    OkHttpClient client = makeClientFrom(baseHttp().proxy(proxyHost, proxyPort));

    assertThat(client.proxy(), Matchers.notNullValue());
    assertThat(((InetSocketAddress)client.proxy().address()).getHostName(), equalTo(proxyHost));
    assertThat(((InetSocketAddress)client.proxy().address()).getPort(), equalTo(proxyPort));
  }

  @Test
  public void clientProxyAuthenticator() throws Exception {
    Proxy proxy = new Proxy(Type.HTTP, new InetSocketAddress("http://proxy.example.com", 8080));
    Authenticator auth = new Authenticator() {
      public Request authenticate(Route arg0, Response arg1) throws IOException {
        return null;
      }
    };
    OkHttpClient client = makeClientFrom(baseHttp().proxy(proxy).proxyAuthenticator(auth));
    
    assertThat(client.proxyAuthenticator(), sameInstance(auth));
  }

  @Test
  public void clientBuilderActions() throws Exception {
    ClientConfigurer configurer1 = new ClientConfigurer() {
      @Override
      public void configure(Builder builder) {
        builder.connectTimeout(21, TimeUnit.SECONDS);
      }
    };
    ClientConfigurer configurer2 = new ClientConfigurer() {
      @Override
      public void configure(Builder builder) {
        builder.readTimeout(22, TimeUnit.SECONDS);
      }
    };
    OkHttpClient client = makeClientFrom(baseHttp()
        .clientBuilderActions(configurer1)
        .clientBuilderActions(null) // no-op
        .clientBuilderActions(configurer2));

    assertThat(client.connectTimeoutMillis(), equalTo(21000));
    assertThat(client.readTimeoutMillis(), equalTo(22000));
  }
  
  @Test
  public void requestDefaultProperties() throws Exception {
    RequestInfo r = doRequestFrom(baseHttp(), null);
    
    assertThat(r.getMethod(), equalTo("GET"));
    assertThat(r.getBody(), isEmptyOrNullString());
    assertThat(r.getHeader("Accept"), equalTo("text/event-stream"));
    assertThat(r.getHeader("Cache-Control"), equalTo("no-cache"));
    assertThat(r.getHeader("Last-Event-Id"), nullValue());
  }

  @Test
  public void requestCustomHeaders() throws Exception {
    Headers headers = new Headers.Builder().add("header1", "value1").add("header2", "value2").build();
    HttpConnectStrategy hcs = baseHttp().headers(headers)
        .header("header3", "value3");
    RequestInfo r = doRequestFrom(hcs, null);
    
    assertThat(r.getHeader("Accept"), equalTo("text/event-stream"));
    assertThat(r.getHeader("Cache-Control"), equalTo("no-cache"));
    assertThat(r.getHeader("header1"), equalTo("value1"));
    assertThat(r.getHeader("header2"), equalTo("value2"));
    assertThat(r.getHeader("header3"), equalTo("value3"));
  }

  @Test
  public void requestLastEventIdHeader() throws Exception {
    String lastId = "123";
    RequestInfo r = doRequestFrom(baseHttp(), lastId);
    
    assertThat(r.getHeader("Last-Event-Id"), equalTo(lastId));
  }

  @Test
  public void requestLastEventIdHeaderOmittedIfEmpty() throws Exception {
    RequestInfo r = doRequestFrom(baseHttp(), "");
    
    assertThat(r.getHeader("Last-Event-Id"), nullValue());
  }
  
  @Test
  public void requestCustomMethodWithBody() throws Exception {
    // Unlike the other request tests, here we'll do more than one request, to
    // verify that the request body is correctly sent each time.
    String content = "hello world";
    RequestInfo r = doRequestFrom(baseHttp().methodAndBody("report",
        RequestBody.create(content, MediaType.parse("text/plain; charset=utf-8"))), null);
    
    assertThat(r.getMethod(), equalTo("REPORT"));
    assertThat(r.getBody(), equalTo(content));
  }
  
  @Test
  public void requestTransformer() throws Exception {
    RequestTransformer transform1 = new RequestTransformer() {
      @Override
      public Request transformRequest(Request r) {
        return r.newBuilder().addHeader("header1", "value1").build();
      }
    };
    RequestTransformer transform2 = new RequestTransformer() {
      @Override
      public Request transformRequest(Request r) {
        return r.newBuilder().addHeader("header2", "value2").build();
      }
    };
    RequestInfo r = doRequestFrom(baseHttp()
        .requestTransformer(transform1)
        .requestTransformer(null) // no-op
        .requestTransformer(transform2),
        null);
    
    assertThat(r.getHeader("header1"), equalTo("value1"));
    assertThat(r.getHeader("header2"), equalTo("value2"));
  }
  
  @Test
  public void canReadFromChunkedResponseStream() throws Exception {
    Handler response = Handlers.all(
        Handlers.startChunks("text/plain", Charset.forName("UTF-8")),
        Handlers.writeChunkString("hello "),
        Handlers.writeChunkString("world"),
        Handlers.hang()
        );
    try (HttpServer server = HttpServer.start(response)) {
      try (ConnectStrategy.Client client = ConnectStrategy.http(server.getUri())
          .createClient(testLogger.getLogger())) {
        ConnectStrategy.Client.Result result = client.connect(null);
        InputStream stream = result.getInputStream();
        
        byte[] b = new byte[100];
        int n = stream.read(b, 0, 6);
        assertThat(n, equalTo(6));
        n = stream.read(b, 6, 5);
        assertThat(n, equalTo(5));
        assertThat(new String(b, 0, 11), equalTo("hello world"));
      }
    }
  }

  @Test
  public void requestFailsWithHttpError() throws Exception {
    Handler errorResponse = Handlers.status(400);
    try (HttpServer server = HttpServer.start(errorResponse)) {
      try (ConnectStrategy.Client client = ConnectStrategy.http(server.getUri())
          .createClient(testLogger.getLogger())) {
        try {
          client.connect(null);
          fail("expected exception");
        } catch (StreamHttpErrorException e) {
          assertThat(e.getCode(), equalTo(400));
        }
      }
    }
  }

  @Test
  public void requestFailsWithIOException() throws Exception {
    try (TcpServer server = TcpServer.start(TcpHandlers.noResponse())) {
      try (ConnectStrategy.Client client = ConnectStrategy.http(server.getHttpUri())
          .createClient(testLogger.getLogger())) {
        try {
          client.connect(null);
          fail("expected exception");
        } catch (StreamIOException e) {}
      }
    }
  }
  
  @Test
  public void streamEndsWhenClosedByServer() throws Exception {
    Handler response = Handlers.all(
        Handlers.startChunks("text/plain", Charset.forName("UTF-8")),
        Handlers.writeChunkString("hello")
        );
    try (HttpServer server = HttpServer.start(response)) {
      try (ConnectStrategy.Client client = ConnectStrategy.http(server.getUri())
          .createClient(testLogger.getLogger())) {
        ConnectStrategy.Client.Result result = client.connect(null);
        InputStream stream = result.getInputStream();
        
        byte[] b = new byte[100];
        int n = stream.read(b, 0, 5);
        assertThat(n, equalTo(5));
        n = stream.read(b, 5, 1);
        assertThat(n, equalTo(-1));
      }
    }
  }

  @Test
  public void clientCanForceStreamToClose() throws Exception {
    Handler response = Handlers.all(
        Handlers.startChunks("text/plain", Charset.forName("UTF-8")),
        Handlers.writeChunkString("hello"),
        Handlers.hang()
        );
    try (HttpServer server = HttpServer.start(response)) {
      try (ConnectStrategy.Client client = ConnectStrategy.http(server.getUri())
          .createClient(testLogger.getLogger())) {
        ConnectStrategy.Client.Result result = client.connect(null);
        InputStream stream = result.getInputStream();
        
        byte[] b = new byte[100];
        int n = stream.read(b, 0, 5);
        assertThat(n, equalTo(5));
        
        result.getCloser().close();
        // This causes us to call the OkHttp method Call.cancel(). The InputStream is
        // expected to throw an IOException on the next read, but it would also be
        // acceptable for it to return EOF (-1).
        try {
          n = stream.read(b, 5, 1);
          assertThat("stream should have been closed but was not", n, equalTo(-1));
        } catch (IOException e) {}
      }
    }
  }
  
  @Test
  public void awaitClosed() throws Exception {
    try (HttpServer server = HttpServer.start(Handlers.hang())) {
      try (ConnectStrategy.Client client = ConnectStrategy.http(server.getUri())
          .createClient(testLogger.getLogger())) {
        client.close();
        assertThat(client.awaitClosed(100), is(true));
      }
    }
  }
  
  @Test
  public void canUseCustomClient() throws Exception {
    OkHttpClient myClient = new OkHttpClient.Builder().build();
    try (ConnectStrategy.Client client = ConnectStrategy.http(TEST_URI)
        .httpClient(myClient)
        .createClient(testLogger.getLogger())) {
      assertThat(((HttpConnectStrategy.Client)client).httpClient, sameInstance(myClient));
    }
  }

  private HttpConnectStrategy baseHttp() {
    return ConnectStrategy.http(TEST_URI);
  }
  
  private OkHttpClient makeClientFrom(
      HttpConnectStrategy hcs
      ) throws Exception {
    try (ConnectStrategy.Client client = hcs.uri(TEST_URI).createClient(testLogger.getLogger())) {
      return ((HttpConnectStrategy.Client)client).httpClient;
    }
  }
  
  private RequestInfo doRequestFrom(
      HttpConnectStrategy hcs,
      String lastEventId
      ) throws Exception {
    try (HttpServer server = HttpServer.start(Handlers.status(200))) {
      try (ConnectStrategy.Client client = hcs.uri(server.getUri()).createClient(testLogger.getLogger())) {
        ConnectStrategy.Client.Result result = client.connect(lastEventId);
        result.getCloser().close();

        return server.getRecorder().requireRequest();
      }
    }
  }
}
