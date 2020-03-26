package com.launchdarkly.eventsource;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.OkHttpClient.Builder;
import okio.Buffer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

@SuppressWarnings("javadoc")
public class EventSourceBuilderTest {
  private static final URI STREAM_URI = URI.create("http://www.example.com/");
  private static final HttpUrl STREAM_HTTP_URL = HttpUrl.parse("http://www.example.com/");
  private EventSource eventSource;
  private EventSource.Builder builder;

  @Before
  public void setUp() {
    EventHandler eventHandler = mock(EventHandler.class);
    builder = new EventSource.Builder(eventHandler, STREAM_URI);
    eventSource = builder.build();
  }

  @Test
  public void hasExpectedUri() {
    assertEquals(STREAM_URI, eventSource.getUri());
  }

  @Test
  public void hasExpectedUriWhenInitializedWithHttpUrl() {
    EventSource es = new EventSource.Builder(mock(EventHandler.class), STREAM_HTTP_URL).build();
    assertEquals(STREAM_URI, es.getUri());
  }
  
  @Test
  public void hasExpectedHttpUrlWhenInitializedWithUri() {
    assertEquals(STREAM_HTTP_URL, eventSource.getHttpUrl());
  }
  
  @Test
  public void hasExpectedHttpUrlWhenInitializedWithHttpUrl() {
    EventSource es = new EventSource.Builder(mock(EventHandler.class), STREAM_HTTP_URL).build();
    assertEquals(STREAM_HTTP_URL, es.getHttpUrl());
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
  public void canSetUri() {
    URI uri = URI.create("http://www.other.com/");
    eventSource.setUri(uri);
    assertEquals(uri, eventSource.getUri());
  }

  @Test(expected=IllegalArgumentException.class)
  public void cannotSetUriToNull() {
    eventSource.setUri(null);
  }

  @Test(expected=IllegalArgumentException.class)
  public void cannotSetUriToInvalidScheme() {
    eventSource.setUri(URI.create("gopher://example.com/"));
  }

  @Test
  public void canSetHttpUrl() {
    HttpUrl url = HttpUrl.parse("http://www.other.com/");
    eventSource.setHttpUrl(url);
    assertEquals(url, eventSource.getHttpUrl());
  }
  
  @Test(expected=IllegalArgumentException.class)
  public void cannotSetHttpUrlToNull() {
    eventSource.setHttpUrl(null);
  }
  
  @Test
  public void respectsDefaultMaximumBackoffTime() {
    eventSource.setReconnectionTimeMs(2000);
    assertEquals(EventSource.DEFAULT_MAX_RECONNECT_TIME_MS, eventSource.getMaxReconnectTimeMs());
    Assert.assertTrue(eventSource.backoffWithJitter(300) < eventSource.getMaxReconnectTimeMs());
  }

  @Test
  public void respectsCustomMaximumBackoffTime() {
    eventSource.setReconnectionTimeMs(2000);
    eventSource.setMaxReconnectTimeMs(5000);
    Assert.assertTrue(eventSource.backoffWithJitter(300) < eventSource.getMaxReconnectTimeMs());
  }

  @Ignore("Useful for inspecting jitter values empirically")
  public void inspectJitter() {
    for (int i = 0; i < 100; i++) {
      System.out.println("With jitter, retry " + i + ": " + eventSource.backoffWithJitter(i));
    }
  }

  @Test
  public void defaultClient() {
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();
    assertEquals(EventSource.DEFAULT_CONNECT_TIMEOUT_MS, client.connectTimeoutMillis());
    assertEquals(EventSource.DEFAULT_READ_TIMEOUT_MS, client.readTimeoutMillis());
    assertEquals(EventSource.DEFAULT_WRITE_TIMEOUT_MS, client.writeTimeoutMillis());
    assertNull(client.proxy());
  }

  @Test
  public void defaultClientWithProxyHostAndPort() {
    String proxyHost = "http://proxy.example.com";
    int proxyPort = 8080;
    builder.proxy(proxyHost, proxyPort);
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();

    assertEquals(EventSource.DEFAULT_CONNECT_TIMEOUT_MS, client.connectTimeoutMillis());
    assertEquals(EventSource.DEFAULT_READ_TIMEOUT_MS, client.readTimeoutMillis());
    assertEquals(EventSource.DEFAULT_WRITE_TIMEOUT_MS, client.writeTimeoutMillis());
    Assert.assertNotNull(client.proxy());
    assertEquals(proxyHost + ":" + proxyPort, client.proxy().address().toString());
  }

  @Test
  public void defaultClientWithProxy() {
    Proxy proxy = mock(java.net.Proxy.class);
    builder.proxy(proxy);
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();

    assertEquals(EventSource.DEFAULT_CONNECT_TIMEOUT_MS, client.connectTimeoutMillis());
    assertEquals(EventSource.DEFAULT_READ_TIMEOUT_MS, client.readTimeoutMillis());
    assertEquals(EventSource.DEFAULT_WRITE_TIMEOUT_MS, client.writeTimeoutMillis());
    assertEquals(proxy, client.proxy());
  }

  @Test
  public void defaultClientWithCustomTimeouts() {
    int connectTimeout = 100;
    int readTimeout = 1000;
    int writeTimeout = 10000;
    builder.connectTimeoutMs(connectTimeout);
    builder.readTimeoutMs(readTimeout);
    builder.writeTimeoutMs(writeTimeout);
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
    builder.body(RequestBody.create(MediaType.parse("text/plain; charset=utf-8"), "hello world"));
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
