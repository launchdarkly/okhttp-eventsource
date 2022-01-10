package com.launchdarkly.eventsource;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.eventsource.EventSource.DEFAULT_BACKOFF_RESET_THRESHOLD;
import static com.launchdarkly.eventsource.EventSource.DEFAULT_CONNECT_TIMEOUT;
import static com.launchdarkly.eventsource.EventSource.DEFAULT_MAX_RECONNECT_TIME;
import static com.launchdarkly.eventsource.EventSource.DEFAULT_READ_TIMEOUT;
import static com.launchdarkly.eventsource.EventSource.DEFAULT_RECONNECT_TIME;
import static com.launchdarkly.eventsource.EventSource.DEFAULT_WRITE_TIMEOUT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

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
import okio.Buffer;

@SuppressWarnings("javadoc")
public class EventSourceBuilderTest {
  private static final URI STREAM_URI = URI.create("http://www.example.com/");
  private static final HttpUrl STREAM_HTTP_URL = HttpUrl.parse("http://www.example.com/");
  private EventSource.Builder builder;
  private EventHandler mockHandler;

  @Before
  public void setUp() {
    mockHandler = mock(EventHandler.class);
    builder = new EventSource.Builder(mockHandler, STREAM_URI);
  }

  @Test
  public void hasExpectedUri() {
    try (EventSource es = builder.build()) {
      assertEquals(STREAM_URI, es.getUri());      
    }
  }

  @Test
  public void hasExpectedUriWhenInitializedWithHttpUrl() {
    try (EventSource es = new EventSource.Builder(mockHandler, STREAM_HTTP_URL).build()) {
      assertEquals(STREAM_URI, es.getUri());
    }
  }
  
  @Test
  public void hasExpectedHttpUrlWhenInitializedWithUri() {
    try (EventSource es = builder.build()) {
      assertEquals(STREAM_HTTP_URL, es.getHttpUrl());
    }
  }
  
  @Test
  public void hasExpectedHttpUrlWhenInitializedWithHttpUrl() {
    try (EventSource es = new EventSource.Builder(mockHandler, STREAM_HTTP_URL).build()) {
      assertEquals(STREAM_HTTP_URL, es.getHttpUrl());
    }
  }
  
  @Test(expected=IllegalArgumentException.class)
  public void handlerCannotBeNull() {
    new EventSource.Builder(null, STREAM_URI);
  }

  @Test(expected=IllegalArgumentException.class)
  public void uriCannotBeNull() {
    new EventSource.Builder(mock(EventHandler.class), (URI)null);
  }

  @Test(expected=IllegalArgumentException.class)
  public void httpUrlCannotBeNull() {
    new EventSource.Builder(mock(EventHandler.class), (HttpUrl)null);
  }
  
  @Test
  public void defaultReconnectProperties() {
    try (EventSource es = builder.build()) {
      assertEquals(DEFAULT_RECONNECT_TIME, es.reconnectTime);
      assertEquals(DEFAULT_MAX_RECONNECT_TIME, es.maxReconnectTime);
      assertEquals(DEFAULT_BACKOFF_RESET_THRESHOLD, es.backoffResetThreshold);
    }

    builder.reconnectTime(Duration.ofSeconds(3))
      .maxReconnectTime(Duration.ofSeconds(4))
      .backoffResetThreshold(Duration.ofSeconds(5));
    builder.reconnectTime(null)
      .maxReconnectTime(null)
      .backoffResetThreshold(null);

    try (EventSource es = builder.build()) {
      assertEquals(DEFAULT_RECONNECT_TIME, es.reconnectTime);
      assertEquals(DEFAULT_MAX_RECONNECT_TIME, es.maxReconnectTime);
      assertEquals(DEFAULT_BACKOFF_RESET_THRESHOLD, es.backoffResetThreshold);
    }
  }
  
  @Test
  public void customReconnectProperties() {
    builder.reconnectTime(Duration.ofSeconds(3))
      .maxReconnectTime(Duration.ofSeconds(4))
      .backoffResetThreshold(Duration.ofSeconds(5));
    try (EventSource es = builder.build()) {
      assertEquals(Duration.ofSeconds(3), es.reconnectTime);
      assertEquals(Duration.ofSeconds(4), es.maxReconnectTime);
      assertEquals(Duration.ofSeconds(5), es.backoffResetThreshold);
    }
  }
  
  @Test
  public void respectsDefaultMaximumBackoffTime() {
    builder.reconnectTime(DEFAULT_MAX_RECONNECT_TIME.minus(Duration.ofMillis(1)));
    try (EventSource es = builder.build()) {
      assertThat(es.backoffWithJitter(100), lessThanOrEqualTo(EventSource.DEFAULT_MAX_RECONNECT_TIME));
    }
  }

  @Test
  public void respectsCustomMaximumBackoffTime() {
    builder.reconnectTime(Duration.ofMillis(2000)).maxReconnectTime(Duration.ofMillis(5000));
    try (EventSource es = builder.build()) {
      assertThat(es.backoffWithJitter(100), lessThanOrEqualTo(Duration.ofMillis(5000)));
    }
  }

  @Test
  public void defaultClient() {
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();
    assertEquals(DEFAULT_CONNECT_TIMEOUT.toMillis(), client.connectTimeoutMillis());
    assertEquals(DEFAULT_READ_TIMEOUT.toMillis(), client.readTimeoutMillis());
    assertEquals(DEFAULT_WRITE_TIMEOUT.toMillis(), client.writeTimeoutMillis());
    assertNull(client.proxy());
  }

  @Test
  public void defaultClientWithProxyHostAndPort() {
    String proxyHost = "https://launchdarkly.com";
    int proxyPort = 8080;
    builder.proxy(proxyHost, proxyPort);
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();

    assertEquals(DEFAULT_CONNECT_TIMEOUT.toMillis(), client.connectTimeoutMillis());
    assertEquals(DEFAULT_READ_TIMEOUT.toMillis(), client.readTimeoutMillis());
    assertEquals(DEFAULT_WRITE_TIMEOUT.toMillis(), client.writeTimeoutMillis());
    Assert.assertNotNull(client.proxy());
    assertEquals(proxyHost, ((InetSocketAddress)client.proxy().address()).getHostName());
    assertEquals(proxyPort, ((InetSocketAddress)client.proxy().address()).getPort());
  }

  @Test
  public void defaultClientWithProxy() {
    Proxy proxy = new Proxy(Type.HTTP, new InetSocketAddress("http://proxy.example.com", 8080));
    builder.proxy(proxy);
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();

    assertEquals(DEFAULT_CONNECT_TIMEOUT.toMillis(), client.connectTimeoutMillis());
    assertEquals(DEFAULT_READ_TIMEOUT.toMillis(), client.readTimeoutMillis());
    assertEquals(DEFAULT_WRITE_TIMEOUT.toMillis(), client.writeTimeoutMillis());
    assertEquals(proxy, client.proxy());
  }

  @Test
  public void proxyAuthenticator() {
    Proxy proxy = new Proxy(Type.HTTP, new InetSocketAddress("http://proxy.example.com", 8080));
    Authenticator auth = new Authenticator() {
      public Request authenticate(Route arg0, Response arg1) throws IOException {
        return null;
      }
    };
    builder.proxy(proxy);
    builder.proxyAuthenticator(auth);
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();

    assertEquals(proxy, client.proxy());
    assertSame(auth, client.proxyAuthenticator());
  }

  @Test
  public void defaultClientWithCustomTimeouts() {
    int connectTimeout = 100;
    int readTimeout = 1000;
    int writeTimeout = 10000;
    builder.connectTimeout(Duration.ofMillis(connectTimeout));
    builder.readTimeout(Duration.ofMillis(readTimeout));
    builder.writeTimeout(Duration.ofMillis(writeTimeout));
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();

    assertEquals(connectTimeout, client.connectTimeoutMillis());
    assertEquals(readTimeout, client.readTimeoutMillis());
    assertEquals(writeTimeout, client.writeTimeoutMillis());
    
    builder.connectTimeout(null);
    assertEquals(EventSource.DEFAULT_CONNECT_TIMEOUT.toMillis(), builder.getClientBuilder().build().connectTimeoutMillis());
    
    builder.readTimeout(null);
    assertEquals(EventSource.DEFAULT_READ_TIMEOUT.toMillis(), builder.getClientBuilder().build().readTimeoutMillis());
    
    builder.writeTimeout(null);
    assertEquals(EventSource.DEFAULT_WRITE_TIMEOUT.toMillis(), builder.getClientBuilder().build().writeTimeoutMillis());
  }

  @Test
  public void customBuilderActions() {
    final int writeTimeout = 9999;
    builder.clientBuilderActions(new EventSource.Builder.ClientConfigurer() {
      public void configure(Builder b) {
        b.writeTimeout(writeTimeout, TimeUnit.MILLISECONDS);
      }
    });
    OkHttpClient client = builder.getClientBuilder().build();

    assertEquals(writeTimeout, client.writeTimeoutMillis());
  }
  
  @Test
  public void customMethod() throws IOException {
    builder.method("report");
    builder.body(RequestBody.create("hello world", MediaType.parse("text/plain; charset=utf-8")));
    Request req = builder.build().buildRequest();
    assertEquals("REPORT", req.method());
    assertEquals(MediaType.parse("text/plain; charset=utf-8"), req.body().contentType());
    Buffer actualBody = new Buffer();
    req.body().writeTo(actualBody);
    assertEquals("hello world", actualBody.readString(Charset.forName("utf-8")));

    // ensure we can build multiple requests:
    req = builder.build().buildRequest();
    assertEquals("REPORT", req.method());
    assertEquals(MediaType.parse("text/plain; charset=utf-8"), req.body().contentType());
    actualBody = new Buffer();
    req.body().writeTo(actualBody);
    assertEquals("hello world", actualBody.readString(Charset.forName("utf-8")));
  }

  @Test
  public void defaultMethod() {
    Request req = builder.build().buildRequest();
    assertEquals("GET", req.method());
    assertEquals(null, req.body());
  }

  @Test
  public void nullOrEmptyMethodUsesDefault() {
    builder.method("REPORT").method(null);
    Request req1 = builder.build().buildRequest();
    assertEquals("GET", req1.method());

    builder.method("REPORT").method("");
    Request req2 = builder.build().buildRequest();
    assertEquals("GET", req2.method());
  }
  
  @Test
  public void customHeaders() throws IOException {
    Headers headers = new Headers.Builder()
        .add("header1", "value1").add("header1", "value2")
        .add("header2", "value1")
        .build();
    builder.headers(headers);
    Request req = builder.build().buildRequest();
    assertEquals(Arrays.asList("value1", "value2"), req.headers().values("header1"));
    assertEquals(Arrays.asList("value1"), req.headers().values("header2"));
    assertEquals(Arrays.asList("text/event-stream"), req.headers().values("Accept"));
    assertEquals(Arrays.asList("no-cache"), req.headers().values("Cache-Control"));
  }
  
  @Test
  public void customHeadersOverwritingDefaults() throws IOException {
    Headers headers = new Headers.Builder()
        .add("Accept", "text/plain")
        .add("header2", "value1")
        .build();
    builder.headers(headers);
    Request req = builder.build().buildRequest();
    assertEquals(Arrays.asList("text/plain"), req.headers().values("Accept"));
    assertEquals(Arrays.asList("value1"), req.headers().values("header2"));
  }

  @Test
  public void customRequestTransformer() throws IOException {
    builder.requestTransformer(req -> {
      return new Request.Builder(req).addHeader("special", "value").build();
    });
    Request req = builder.build().buildRequest();
    assertEquals(Arrays.asList("value"), req.headers().values("special"));
  }
  
  @Test
  public void customClient() throws IOException {
    OkHttpClient client0 = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(11)).build();
    OkHttpClient client1 = builder.client(client0).getClientBuilder().build();
    assertEquals(client0.connectTimeoutMillis(), client1.connectTimeoutMillis());
  }
  
  @Test
  public void readBufferSize() throws IOException {
    try (EventSource es = builder.build()) {
      assertEquals(EventSource.DEFAULT_READ_BUFFER_SIZE, es.readBufferSize);      
    }
    try (EventSource es = builder.readBufferSize(9999).build()) {
      assertEquals(9999, es.readBufferSize);
    }
    try {
      builder.readBufferSize(0);
      fail("expected exception");
    } catch (IllegalArgumentException e) {}
    try {
      builder.readBufferSize(-1);
      fail("expected exception");
    } catch (IllegalArgumentException e) {}
  }
  
  @Test
  public void defaultLoggerWithDefaultLoggerName() {
    try (EventSource es = builder.build()) {
      assertEquals(SLF4JLogger.class, es.logger.getClass());
      assertEquals("com.launchdarkly.eventsource.EventSource", ((SLF4JLogger)es.logger).name);
    }
  }

  @Test
  public void defaultLoggerWithDefaultLoggerNamePlusCustomStreamName() {
    try (EventSource es = builder.name("mystream").build()) {
      assertEquals(SLF4JLogger.class, es.logger.getClass());
      assertEquals("com.launchdarkly.eventsource.EventSource.mystream", ((SLF4JLogger)es.logger).name);
    }
  }

  @Test
  public void customStreamNameIsIgnoredInLoggerName() {
    try (EventSource es = builder.name("").build()) {
      assertEquals(SLF4JLogger.class, es.logger.getClass());
      assertEquals("com.launchdarkly.eventsource.EventSource", ((SLF4JLogger)es.logger).name);
    }
  }

  @Test
  public void defaultLoggerWithCustomLoggerName() {
    try (EventSource es = builder.loggerBaseName("mylog").build()) {
      assertEquals(SLF4JLogger.class, es.logger.getClass());
      assertEquals("mylog", ((SLF4JLogger)es.logger).name);
    }
  }

  @Test
  public void defaultLoggerWithCustomLoggerNamePlusCustomStreamName() {
    try (EventSource es = builder.loggerBaseName("mylog").name("mystream").build()) {
      assertEquals(SLF4JLogger.class, es.logger.getClass());
      assertEquals("mylog.mystream", ((SLF4JLogger)es.logger).name);
    }
  }

  @Test
  public void customLogger() {
    Logger myLogger = new SLF4JLogger("x");
    try (EventSource es = builder.logger(myLogger).build()) {
      assertSame(myLogger, es.logger);
    }
  }

  @Test
  public void defaultEventThreadWorkQueueCapacity() {
    try (EventSource es = builder.build()) {
      assertNull(es.handler.semaphore);
    }
  }

  @Test
  public void eventThreadWorkQueueCapacity() {
    try (EventSource es = builder.maxEventTasksInFlight(8).build()) {
      assertEquals(8, es.handler.semaphore.availablePermits());
    }
  }
}
