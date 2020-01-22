package com.launchdarkly.eventsource;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

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

public class EventSourceTest {
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
    eventSource.setReconnectionTime(Duration.ofMillis(2000));
    assertEquals(EventSource.DEFAULT_MAX_RECONNECT_TIME, eventSource.getMaxReconnectTime());
    assertEquals(eventSource.backoffWithJitter(300).compareTo(eventSource.getMaxReconnectTime()), -1);
  }

  @Test
  public void respectsCustomMaximumBackoffTime() {
    eventSource.setReconnectionTime(Duration.ofMillis(2000));
    eventSource.setMaxReconnectTime(Duration.ofMillis(5000));
    assertEquals(eventSource.backoffWithJitter(300).compareTo(eventSource.getMaxReconnectTime()), -1);
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
    assertEquals(EventSource.DEFAULT_CONNECT_TIMEOUT, Duration.ofMillis(client.connectTimeoutMillis()));
    assertEquals(EventSource.DEFAULT_READ_TIMEOUT, Duration.ofMillis(client.readTimeoutMillis()));
    assertEquals(EventSource.DEFAULT_WRITE_TIMEOUT, Duration.ofMillis(client.writeTimeoutMillis()));
    assertNull(client.proxy());
  }

  @Test
  public void defaultClientWithProxyHostAndPort() {
    String proxyHost = "http://proxy.example.com";
    int proxyPort = 8080;
    builder.proxy(proxyHost, proxyPort);
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();

    assertEquals(EventSource.DEFAULT_CONNECT_TIMEOUT, Duration.ofMillis(client.connectTimeoutMillis()));
    assertEquals(EventSource.DEFAULT_READ_TIMEOUT, Duration.ofMillis(client.readTimeoutMillis()));
    assertEquals(EventSource.DEFAULT_WRITE_TIMEOUT, Duration.ofMillis(client.writeTimeoutMillis()));
    Assert.assertNotNull(client.proxy());
    assertEquals(proxyHost + ":" + proxyPort, client.proxy().address().toString());
  }

  @Test
  public void defaultClientWithProxy() {
    Proxy proxy = mock(java.net.Proxy.class);
    builder.proxy(proxy);
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();

    assertEquals(EventSource.DEFAULT_CONNECT_TIMEOUT, Duration.ofMillis(client.connectTimeoutMillis()));
    assertEquals(EventSource.DEFAULT_READ_TIMEOUT, Duration.ofMillis(client.readTimeoutMillis()));
    assertEquals(EventSource.DEFAULT_WRITE_TIMEOUT, Duration.ofMillis(client.writeTimeoutMillis()));
    assertEquals(proxy, client.proxy());
  }

  @Test
  public void defaultClientWithCustomTimeouts() {
    Duration connectTimeout = Duration.ofMillis(100);
    Duration readTimeout = Duration.ofMillis(1000);
    Duration writeTimeout = Duration.ofMillis(10000);
    builder.connectTimeout(connectTimeout);
    builder.readTimeout(readTimeout);
    builder.writeTimeout(writeTimeout);
    builder.build();
    OkHttpClient client = builder.getClientBuilder().build();

    assertEquals(connectTimeout, Duration.ofMillis(client.connectTimeoutMillis()));
    assertEquals(readTimeout, Duration.ofMillis(client.readTimeoutMillis()));
    assertEquals(writeTimeout, Duration.ofMillis(client.writeTimeoutMillis()));
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
