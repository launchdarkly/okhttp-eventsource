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

import static com.launchdarkly.eventsource.Stubs.createEventsResponse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.Buffer;

@SuppressWarnings("javadoc")
public class EventSourceTest {
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
  
  @Test
  public void customMethod() throws IOException {
    builder.method("report");
    builder.body(RequestBody.create("hello world", MediaType.parse("text/plain; charset=utf-8")));
    try (EventSource es = builder.build()) {
      Request req = es.buildRequest();
      assertEquals("REPORT", req.method());
      assertEquals(MediaType.parse("text/plain; charset=utf-8"), req.body().contentType());
      Buffer actualBody = new Buffer();
      req.body().writeTo(actualBody);
      assertEquals("hello world", actualBody.readString(Charset.forName("utf-8")));
  
      // ensure we can build multiple requests:
      req = es.buildRequest();
      assertEquals("REPORT", req.method());
      assertEquals(MediaType.parse("text/plain; charset=utf-8"), req.body().contentType());
      actualBody = new Buffer();
      req.body().writeTo(actualBody);
      assertEquals("hello world", actualBody.readString(Charset.forName("utf-8")));
    }
  }

  @Test
  public void defaultMethod() {
    try (EventSource es = builder.build()) {
      Request req = es.buildRequest();
      assertEquals("GET", req.method());
      assertEquals(null, req.body());
    }
  }
  
  @Test
  public void customHeaders() throws IOException {
    Headers headers = new Headers.Builder()
        .add("header1", "value1").add("header1", "value2")
        .add("header2", "value1")
        .build();
    builder.headers(headers);
    try (EventSource es = builder.build()) {
      Request req = es.buildRequest();
      assertEquals(Arrays.asList("value1", "value2"), req.headers().values("header1"));
      assertEquals(Arrays.asList("value1"), req.headers().values("header2"));
      assertEquals(Arrays.asList("text/event-stream"), req.headers().values("Accept"));
      assertEquals(Arrays.asList("no-cache"), req.headers().values("Cache-Control"));
    }
  }
  
  @Test
  public void customHeadersOverwritingDefaults() throws IOException {
    Headers headers = new Headers.Builder()
        .add("Accept", "text/plain")
        .add("header2", "value1")
        .build();
    builder.headers(headers);
    try (EventSource es = builder.build()) {
      Request req = es.buildRequest();
      assertEquals(Arrays.asList("text/plain"), req.headers().values("Accept"));
      assertEquals(Arrays.asList("value1"), req.headers().values("header2"));
    }
  }
  
  @Test
  public void configuredLastEventIdIsIncludedInHeaders() throws Exception {
    String lastId = "123";
    builder.lastEventId(lastId);
    try (EventSource es = builder.build()) {
      Request req = es.buildRequest();
      assertEquals(Arrays.asList(lastId), req.headers().values("Last-Event-Id"));
    }
  }
  
  @Test
  public void lastEventIdIsUpdatedFromEvent() throws Exception {
    String initialLastId = "123";
    String newLastId = "099";
    String eventType = "thing";
    String eventData = "some-data";
    
    try (MockWebServer server = new MockWebServer()) {
      String body = "id: " + newLastId + "\nevent: " + eventType + "\ndata: " + eventData + "\n\n";
      server.enqueue(createEventsResponse(body, SocketPolicy.KEEP_OPEN));
      server.start();
      
      Stubs.TestHandler eventHandler = new Stubs.TestHandler();
      EventSource.Builder builder = new EventSource.Builder(eventHandler, server.url("/"))
          .lastEventId(initialLastId);
      try (EventSource es = builder.build()) {
        es.start();
        assertEquals(Stubs.LogItem.opened(), eventHandler.log.take());
        
        Stubs.LogItem receivedEvent = eventHandler.log.take(); // waits till we've processed a request
        assertEquals(Stubs.LogItem.event(eventType, eventData, newLastId), receivedEvent);
        
        assertEquals(newLastId, es.getLastEventId());
      }
    }
  }
  
  @Test
  public void newLastEventIdIsSentOnNextConnectAttempt() throws Exception {
    String initialLastId = "123";
    String newLastId = "099";
    String eventType = "thing";
    String eventData = "some-data";
    
    try (MockWebServer server = new MockWebServer()) {
      String body = "id: " + newLastId + "\nevent: " + eventType + "\ndata: " + eventData + "\n\n";
      server.enqueue(createEventsResponse(body, SocketPolicy.KEEP_OPEN));
      server.enqueue(createEventsResponse(body, SocketPolicy.KEEP_OPEN)); // expect a 2nd connection
      server.start();
      
      Stubs.TestHandler eventHandler = new Stubs.TestHandler();
      EventSource.Builder builder = new EventSource.Builder(eventHandler, server.url("/"))
          .reconnectTime(Duration.ofMillis(100))
          .lastEventId(initialLastId);
      try (EventSource es = builder.build()) {
        es.start();
        
        RecordedRequest req0 = server.takeRequest();
        RecordedRequest req1 = server.takeRequest();
        assertEquals(initialLastId, req0.getHeader("Last-Event-Id"));
        assertEquals(newLastId, req1.getHeader("Last-Event-Id"));
      }
    }
  }
}
