package com.launchdarkly.eventsource;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.eventsource.EventSource.DEFAULT_CONNECT_TIMEOUT;
import static com.launchdarkly.eventsource.EventSource.DEFAULT_MAX_RECONNECT_TIME;
import static com.launchdarkly.eventsource.EventSource.DEFAULT_READ_TIMEOUT;
import static com.launchdarkly.eventsource.EventSource.DEFAULT_WRITE_TIMEOUT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import okhttp3.Request;
import okhttp3.RequestBody;
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
    String proxyHost = "http://proxy.example.com";
    int proxyPort = 8080;
    builder.proxy(proxyHost, proxyPort);
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();

    assertEquals(DEFAULT_CONNECT_TIMEOUT.toMillis(), client.connectTimeoutMillis());
    assertEquals(DEFAULT_READ_TIMEOUT.toMillis(), client.readTimeoutMillis());
    assertEquals(DEFAULT_WRITE_TIMEOUT.toMillis(), client.writeTimeoutMillis());
    Assert.assertNotNull(client.proxy());
    assertEquals(proxyHost + ":" + proxyPort, client.proxy().address().toString());
  }

  @Test
  public void defaultClientWithProxy() {
    Proxy proxy = mock(java.net.Proxy.class);
    builder.proxy(proxy);
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();

    assertEquals(DEFAULT_CONNECT_TIMEOUT.toMillis(), client.connectTimeoutMillis());
    assertEquals(DEFAULT_READ_TIMEOUT.toMillis(), client.readTimeoutMillis());
    assertEquals(DEFAULT_WRITE_TIMEOUT.toMillis(), client.writeTimeoutMillis());
    assertEquals(proxy, client.proxy());
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
  public void customHeaders() throws IOException {
    Headers headers = new Headers.Builder()
        .add("header1", "value1").add("header1", "value2")
        .add("header2", "value1")
        .build();
    builder.headers(headers);
    Request req = builder.build().buildRequest();
    assertEquals(Arrays.<String>asList("value1", "value2"), req.headers().values("header1"));
    assertEquals(Arrays.<String>asList("value1"), req.headers().values("header2"));
    assertEquals(Arrays.<String>asList("text/event-stream"), req.headers().values("Accept"));
    assertEquals(Arrays.<String>asList("no-cache"), req.headers().values("Cache-Control"));
  }
  
  @Test
  public void customHeadersOverwritingDefaults() throws IOException {
    Headers headers = new Headers.Builder()
        .add("Accept", "text/plain")
        .add("header2", "value1")
        .build();
    builder.headers(headers);
    Request req = builder.build().buildRequest();
    assertEquals(Arrays.<String>asList("text/plain"), req.headers().values("Accept"));
    assertEquals(Arrays.<String>asList("value1"), req.headers().values("header2"));
  }
}
