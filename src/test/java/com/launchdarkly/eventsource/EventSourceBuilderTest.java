package com.launchdarkly.eventsource;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;

@SuppressWarnings("javadoc")
public class EventSourceBuilderTest {
  private static final URI STREAM_URI = URI.create("http://www.example.com/");
  private static final HttpUrl STREAM_HTTP_URL = HttpUrl.parse("http://www.example.com/");
  private EventSource.Builder builder;

  @Before
  public void setUp() {
    EventHandler eventHandler = mock(EventHandler.class);
    builder = new EventSource.Builder(eventHandler, STREAM_URI);
  }

  @Test
  public void hasExpectedUri() {
    try (EventSource eventSource = builder.build()) {
      assertEquals(STREAM_URI, eventSource.getUri());
    }
  }

  @Test
  public void hasExpectedUriWhenInitializedWithHttpUrl() {
    try (EventSource es = new EventSource.Builder(mock(EventHandler.class), STREAM_HTTP_URL).build()) {
      assertEquals(STREAM_URI, es.getUri());
    }
  }
  
  @Test
  public void hasExpectedHttpUrlWhenInitializedWithUri() {
    try (EventSource eventSource = builder.build()) {
      assertEquals(STREAM_HTTP_URL, eventSource.getHttpUrl());
    }
  }
  
  @Test
  public void hasExpectedHttpUrlWhenInitializedWithHttpUrl() {
    try (EventSource es = new EventSource.Builder(mock(EventHandler.class), STREAM_HTTP_URL).build()) {
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
    builder.reconnectTime(Duration.ofMillis(2000));
    try (EventSource eventSource = builder.build()) {
      assertEquals(eventSource.backoffWithJitter(300).compareTo(EventSource.DEFAULT_MAX_RECONNECT_TIME), -1);
    }
  }

  @Test
  public void respectsCustomMaximumBackoffTime() {
    Duration max = Duration.ofMillis(5000);
    builder.reconnectTime(Duration.ofMillis(2000));
    builder.maxReconnectTime(max);
    try (EventSource eventSource = builder.build()) {
      assertEquals(eventSource.backoffWithJitter(300).compareTo(max), -1);
    }
  }
  
  @Test
  public void lastEventIdIsSetToConfiguredValue() throws Exception {
    String lastId = "123";
    builder.lastEventId(lastId);
    try (EventSource es = builder.build()) {
      assertEquals(lastId, es.getLastEventId());
    }
  }

  private OkHttpClient getHttpClientFromBuilder() {
    try (EventSource es = builder.build()) {} // ensures that the configuration is all copied into the client builder
    return builder.getClientBuilder().build();
  }
  
  @Test
  public void defaultClient() {
    OkHttpClient client = getHttpClientFromBuilder();
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
    OkHttpClient client = getHttpClientFromBuilder();

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
    OkHttpClient client = getHttpClientFromBuilder();

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
    OkHttpClient client = getHttpClientFromBuilder();

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
    OkHttpClient client = getHttpClientFromBuilder();

    assertEquals(writeTimeout, client.writeTimeoutMillis());
  }
}
