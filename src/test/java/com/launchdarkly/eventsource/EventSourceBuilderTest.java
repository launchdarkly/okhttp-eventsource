package com.launchdarkly.eventsource;

import com.google.common.collect.ImmutableSet;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.logging.Logs;

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
import java.util.concurrent.atomic.AtomicReference;

import static com.launchdarkly.eventsource.EventSource.DEFAULT_BACKOFF_RESET_THRESHOLD_MILLIS;
import static com.launchdarkly.eventsource.EventSource.DEFAULT_CONNECT_TIMEOUT_MILLIS;
import static com.launchdarkly.eventsource.EventSource.DEFAULT_MAX_RECONNECT_TIME_MILLIS;
import static com.launchdarkly.eventsource.EventSource.DEFAULT_READ_TIMEOUT_MILLIS;
import static com.launchdarkly.eventsource.EventSource.DEFAULT_RECONNECT_TIME_MILLIS;
import static com.launchdarkly.eventsource.EventSource.DEFAULT_WRITE_TIMEOUT_MILLIS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
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
      assertEquals(DEFAULT_RECONNECT_TIME_MILLIS, es.reconnectTimeMillis);
      assertEquals(DEFAULT_MAX_RECONNECT_TIME_MILLIS, es.maxReconnectTimeMillis);
      assertEquals(DEFAULT_BACKOFF_RESET_THRESHOLD_MILLIS, es.backoffResetThresholdMillis);
    }
  }

  @Test
  public void customReconnectProperties() {
    builder.reconnectTime(3, TimeUnit.SECONDS)
      .maxReconnectTime(4, TimeUnit.SECONDS)
      .backoffResetThreshold(5, TimeUnit.SECONDS);
    try (EventSource es = builder.build()) {
      assertEquals(3000, es.reconnectTimeMillis);
      assertEquals(4000, es.maxReconnectTimeMillis);
      assertEquals(5000, es.backoffResetThresholdMillis);
    }
  }
  
  @SuppressWarnings("deprecation")
  @Test
  public void customReconnectPropertiesWithDeprecatedDurationSetters() {
    builder.reconnectTime(Duration.ofSeconds(3))
      .maxReconnectTime(Duration.ofSeconds(4))
      .backoffResetThreshold(Duration.ofSeconds(5));

    try (EventSource es = builder.build()) {
      assertEquals(3000, es.reconnectTimeMillis);
      assertEquals(4000, es.maxReconnectTimeMillis);
      assertEquals(5000, es.backoffResetThresholdMillis);
    }

    builder.reconnectTime(null)
      .maxReconnectTime(null)
      .backoffResetThreshold(null);

    try (EventSource es = builder.build()) {
      assertEquals(DEFAULT_RECONNECT_TIME_MILLIS, es.reconnectTimeMillis);
      assertEquals(DEFAULT_MAX_RECONNECT_TIME_MILLIS, es.maxReconnectTimeMillis);
      assertEquals(DEFAULT_BACKOFF_RESET_THRESHOLD_MILLIS, es.backoffResetThresholdMillis);
    }
  }
  
  @Test
  public void respectsDefaultMaximumBackoffTime() {
    builder.reconnectTime(DEFAULT_MAX_RECONNECT_TIME_MILLIS - 1, null);
    try (EventSource es = builder.build()) {
      assertThat(es.backoffWithJitterMillis(100), lessThanOrEqualTo(EventSource.DEFAULT_MAX_RECONNECT_TIME_MILLIS));
    }
  }

  @Test
  public void respectsCustomMaximumBackoffTime() {
    builder.reconnectTime(2000, null).maxReconnectTime(5000, null);
    try (EventSource es = builder.build()) {
      assertThat(es.backoffWithJitterMillis(100), lessThanOrEqualTo(5000L));
    }
  }

  @Test
  public void defaultClient() {
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();
    assertEquals(DEFAULT_CONNECT_TIMEOUT_MILLIS, client.connectTimeoutMillis());
    assertEquals(DEFAULT_READ_TIMEOUT_MILLIS, client.readTimeoutMillis());
    assertEquals(DEFAULT_WRITE_TIMEOUT_MILLIS, client.writeTimeoutMillis());
    assertNull(client.proxy());
  }

  @Test
  public void defaultClientWithProxyHostAndPort() {
    String proxyHost = "https://launchdarkly.com";
    int proxyPort = 8080;
    builder.proxy(proxyHost, proxyPort);
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();

    assertEquals(DEFAULT_CONNECT_TIMEOUT_MILLIS, client.connectTimeoutMillis());
    assertEquals(DEFAULT_READ_TIMEOUT_MILLIS, client.readTimeoutMillis());
    assertEquals(DEFAULT_WRITE_TIMEOUT_MILLIS, client.writeTimeoutMillis());
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

    assertEquals(DEFAULT_CONNECT_TIMEOUT_MILLIS, client.connectTimeoutMillis());
    assertEquals(DEFAULT_READ_TIMEOUT_MILLIS, client.readTimeoutMillis());
    assertEquals(DEFAULT_WRITE_TIMEOUT_MILLIS, client.writeTimeoutMillis());
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
    builder.connectTimeout(100, TimeUnit.MILLISECONDS);
    builder.readTimeout(1, TimeUnit.SECONDS);
    builder.writeTimeout(10, TimeUnit.SECONDS);
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();

    assertEquals(100, client.connectTimeoutMillis());
    assertEquals(1000, client.readTimeoutMillis());
    assertEquals(10000, client.writeTimeoutMillis());
  }

  @SuppressWarnings("deprecation")
  @Test
  public void defaultClientWithCustomTimeoutsWithDeprecatedDurationSetters() {
    builder.connectTimeout(Duration.ofMillis(100));
    builder.readTimeout(Duration.ofSeconds(1));
    builder.writeTimeout(Duration.ofSeconds(10));
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();

    assertEquals(100, client.connectTimeoutMillis());
    assertEquals(1000, client.readTimeoutMillis());
    assertEquals(10000, client.writeTimeoutMillis());
    
    builder.connectTimeout(null);
    assertEquals(DEFAULT_CONNECT_TIMEOUT_MILLIS, builder.getClientBuilder().build().connectTimeoutMillis());
    
    builder.readTimeout(null);
    assertEquals(DEFAULT_READ_TIMEOUT_MILLIS, builder.getClientBuilder().build().readTimeoutMillis());
    
    builder.writeTimeout(null);
    assertEquals(DEFAULT_WRITE_TIMEOUT_MILLIS, builder.getClientBuilder().build().writeTimeoutMillis());
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
  public void hasDefaultLogger() {
    try (EventSource es = builder.build()) {
      assertThat(es.logger, notNullValue());
    }
  }

  @Test
  public void logger() {
    LogCapture logCapture = Logs.capture();
    LDLogger myLogger = LDLogger.withAdapter(logCapture, "logname");
    try (EventSource es = builder.logger(myLogger).build()) {
      es.logger.warn("hello");
      assertThat(logCapture.getMessages(), iterableWithSize(1));
      assertEquals("hello", logCapture.getMessages().get(0).getText());
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  public void customLogger() {
    final AtomicReference<String> receivedMessage = new AtomicReference<String>();
    Logger myLogger = new Logger() {
      public void warn(String message) {
        receivedMessage.set(message);
      }
      
      public void info(String message) {}
      
      public void error(String message) {}
      
      public void debug(String format, Object param1, Object param2) {}
      
      public void debug(String format, Object param) {}
    };
    try (EventSource es = builder.logger(myLogger).build()) {
      es.logger.warn("hello");
      assertEquals("hello", receivedMessage.get());
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
  
  @Test
  public void streamingData() {
    try (EventSource es = builder.streamEventData(true).build()) {
      assertTrue(es.streamEventData);
    }
  }
  
  @Test
  public void expectFields() {
    try (EventSource es = builder
        .expectFields("a")
        .expectFields() // should overwrite previous setting
        .expectFields("b", "c") // should overwrite previous setting
        .build()) {
      assertEquals(ImmutableSet.of("b", "c"), es.expectFields);
    }
  }
}
