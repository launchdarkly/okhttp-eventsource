package com.launchdarkly.eventsource;

import okhttp3.OkHttpClient;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.net.Proxy;
import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

public class EventSourceTest {
  private EventSource eventSource;
  private EventSource.Builder builder;

  @Before
  public void setUp() {
    EventHandler eventHandler = mock(EventHandler.class);
    builder = new EventSource.Builder(eventHandler, URI.create("http://www.example.com"));
    eventSource = builder.build();
  }

  @Test
  public void respectsMaximumBackoffTime() {
    eventSource.setReconnectionTimeMs(2000);
    Assert.assertTrue(eventSource.backoffWithJitter(300) < EventSource.MAX_RECONNECT_TIME_MS);
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
}
