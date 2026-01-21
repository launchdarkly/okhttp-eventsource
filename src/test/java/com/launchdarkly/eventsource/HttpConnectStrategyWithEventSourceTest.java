package com.launchdarkly.eventsource;

import com.launchdarkly.testhelpers.httptest.Handler;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;

import org.junit.Rule;
import org.junit.Test;

import org.hamcrest.Matchers;

import static com.launchdarkly.eventsource.TestUtils.interruptOnAnotherThread;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests of basic EventSource behavior using real HTTP requests.
 */
@SuppressWarnings("javadoc")
public class HttpConnectStrategyWithEventSourceTest {
  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();

  @Test
  public void eventSourceReadsEvents() throws Exception {
    Handler response = Handlers.all(
        Handlers.SSE.start(),
        Handlers.SSE.event("a", "data1"),
        Handlers.SSE.event("b", "data2"),
        Handlers.hang()
        );
    
    try (HttpServer server = HttpServer.start(response)) {
      try (EventSource es = new EventSource.Builder(server.getUri()).build()) {
        es.start();
        
        assertThat(es.readAnyEvent(), equalTo(
            new MessageEvent("a", "data1", null, es.getOrigin())));
  
        assertThat(es.readAnyEvent(), equalTo(
            new MessageEvent("b", "data2", null, es.getOrigin())));
      }
    }
  }

  @Test
  public void eventSourceReconnectsAfterSocketClosed() throws Exception {
    Handler response1 = Handlers.all(
        Handlers.SSE.start(),
        Handlers.SSE.event("message", "first")
        );
    Handler response2 = Handlers.all(
        Handlers.SSE.start(),
        Handlers.SSE.event("message", "second"),
        Handlers.SSE.leaveOpen()
        );
    Handler allResponses = Handlers.sequential(response1, response2);

    try (HttpServer server = HttpServer.start(allResponses)) {
      try (EventSource es = new EventSource.Builder(server.getUri())
          .errorStrategy(ErrorStrategy.alwaysContinue())
          .retryDelay(1, null)
          .build()) {
        es.start();
        
        assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "first", null, es.getOrigin())));
        
        assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
        
        assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
        
        assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "second", null, es.getOrigin())));
      }
    }
  }

  @Test
  public void eventSourceReconnectsAfterExternallyInterrupted() throws Exception {
    Handler response1 = Handlers.all(
        Handlers.SSE.start(),
        Handlers.SSE.event("message", "first"),
        Handlers.SSE.leaveOpen()
        );
    Handler response2 = Handlers.all(
        Handlers.SSE.start(),
        Handlers.SSE.event("message", "second"),
        Handlers.SSE.leaveOpen()
        );
    Handler allResponses = Handlers.sequential(response1, response2);

    try (HttpServer server = HttpServer.start(allResponses)) {
      try (EventSource es = new EventSource.Builder(server.getUri())
          .errorStrategy(ErrorStrategy.alwaysContinue())
          .retryDelay(1, null)
          .build()) {
        es.start();
        
        assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "first", null, es.getOrigin())));
        
        interruptOnAnotherThread(es);

        assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByCallerException())));
        
        assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
        
        assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "second", null, es.getOrigin())));
      }
    }
  }

  @Test
  public void messageEventsIncludeResponseHeaders() throws Exception {
    Handler response = Handlers.all(
        Handlers.header("X-Server-Id", "server-123"),
        Handlers.header("X-Request-Id", "request-456"),
        Handlers.SSE.start(),
        Handlers.SSE.event("message", "data1"),
        Handlers.SSE.event("message", "data2"),
        Handlers.hang()
        );

    try (HttpServer server = HttpServer.start(response)) {
      try (EventSource es = new EventSource.Builder(server.getUri()).build()) {
        es.start();

        // First message should have headers
        StreamEvent event1 = es.readAnyEvent();
        assertThat(event1 instanceof MessageEvent, equalTo(true));
        MessageEvent msg1 = (MessageEvent) event1;
        assertThat(msg1.getData(), equalTo("data1"));

        ResponseHeaders headers1 = msg1.getHeaders();
        assertThat(headers1, notNullValue());
        assertThat(headers1.size(), Matchers.greaterThan(0));

        // Find our custom headers using iteration
        String serverId1 = findHeaderValue(headers1, "X-Server-Id");
        String requestId1 = findHeaderValue(headers1, "X-Request-Id");
        assertThat(serverId1, equalTo("server-123"));
        assertThat(requestId1, equalTo("request-456"));

        // Also validate the value() helper method
        assertThat(headers1.value("X-Server-Id"), equalTo("server-123"));
        assertThat(headers1.value("X-Request-Id"), equalTo("request-456"));

        // Second message should have the same headers (same connection)
        StreamEvent event2 = es.readAnyEvent();
        assertThat(event2 instanceof MessageEvent, equalTo(true));
        MessageEvent msg2 = (MessageEvent) event2;
        assertThat(msg2.getData(), equalTo("data2"));

        ResponseHeaders headers2 = msg2.getHeaders();
        assertThat(headers2, notNullValue());

        String serverId2 = findHeaderValue(headers2, "X-Server-Id");
        String requestId2 = findHeaderValue(headers2, "X-Request-Id");
        assertThat(serverId2, equalTo("server-123"));
        assertThat(requestId2, equalTo("request-456"));

        // Also validate the value() helper method
        assertThat(headers2.value("X-Server-Id"), equalTo("server-123"));
        assertThat(headers2.value("X-Request-Id"), equalTo("request-456"));
      }
    }
  }

  private String findHeaderValue(ResponseHeaders headers, String name) {
    for (int i = 0; i < headers.size(); i++) {
      ResponseHeaders.Header header = headers.get(i);
      if (header.getName().equalsIgnoreCase(name)) {
        return header.getValue();
      }
    }
    return null;
  }

  @Test
  public void messageEventsIncludeHeadersFromReconnectedConnection() throws Exception {
    Handler response1 = Handlers.all(
        Handlers.header("X-Connection-Id", "connection-1"),
        Handlers.SSE.start(),
        Handlers.SSE.event("message", "first")
        );
    Handler response2 = Handlers.all(
        Handlers.header("X-Connection-Id", "connection-2"),
        Handlers.SSE.start(),
        Handlers.SSE.event("message", "second"),
        Handlers.SSE.leaveOpen()
        );
    Handler allResponses = Handlers.sequential(response1, response2);

    try (HttpServer server = HttpServer.start(allResponses)) {
      try (EventSource es = new EventSource.Builder(server.getUri())
          .errorStrategy(ErrorStrategy.alwaysContinue())
          .retryDelay(1, null)
          .build()) {
        es.start();

        // First message from first connection
        StreamEvent event1 = es.readAnyEvent();
        assertThat(event1 instanceof MessageEvent, equalTo(true));
        MessageEvent msg1 = (MessageEvent) event1;
        assertThat(msg1.getData(), equalTo("first"));
        assertThat(msg1.getHeaders(), notNullValue());
        assertThat(findHeaderValue(msg1.getHeaders(), "X-Connection-Id"), equalTo("connection-1"));
        // Also validate the value() helper method
        assertThat(msg1.getHeaders().value("X-Connection-Id"), equalTo("connection-1"));

        // Fault event when connection closes
        assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));

        // Started event for reconnection
        assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));

        // Second message from second connection (should have new headers)
        StreamEvent event2 = es.readAnyEvent();
        assertThat(event2 instanceof MessageEvent, equalTo(true));
        MessageEvent msg2 = (MessageEvent) event2;
        assertThat(msg2.getData(), equalTo("second"));
        assertThat(msg2.getHeaders(), notNullValue());
        assertThat(findHeaderValue(msg2.getHeaders(), "X-Connection-Id"), equalTo("connection-2"));
        // Also validate the value() helper method
        assertThat(msg2.getHeaders().value("X-Connection-Id"), equalTo("connection-2"));
      }
    }
  }

  @Test
  public void faultEventsIncludeHeadersForHttpErrors() throws Exception {
    Handler errorResponse = Handlers.all(
        Handlers.header("X-Error-Code", "RATE_LIMIT_EXCEEDED"),
        Handlers.header("Retry-After", "60"),
        Handlers.header("X-RateLimit-Remaining", "0"),
        Handlers.status(429)
        );

    try (HttpServer server = HttpServer.start(errorResponse)) {
      try (EventSource es = new EventSource.Builder(server.getUri())
          .errorStrategy(ErrorStrategy.alwaysContinue())
          .build()) {

        StreamEvent event = es.readAnyEvent();
        assertThat(event instanceof FaultEvent, equalTo(true));
        FaultEvent fault = (FaultEvent) event;

        // Verify it's an HTTP error
        assertThat(fault.getCause() instanceof StreamHttpErrorException, equalTo(true));
        StreamHttpErrorException httpError = (StreamHttpErrorException) fault.getCause();
        assertThat(httpError.getCode(), equalTo(429));

        // Verify headers are available using iteration
        ResponseHeaders headers = fault.getHeaders();
        assertThat(headers, notNullValue());
        assertThat(findHeaderValue(headers, "X-Error-Code"), equalTo("RATE_LIMIT_EXCEEDED"));
        assertThat(findHeaderValue(headers, "Retry-After"), equalTo("60"));
        assertThat(findHeaderValue(headers, "X-RateLimit-Remaining"), equalTo("0"));

        // Also validate the value() helper method
        assertThat(headers.value("X-Error-Code"), equalTo("RATE_LIMIT_EXCEEDED"));
        assertThat(headers.value("Retry-After"), equalTo("60"));
        assertThat(headers.value("X-RateLimit-Remaining"), equalTo("0"));
      }
    }
  }

  @Test
  public void faultEventsDoNotIncludeHeadersForNonHttpErrors() throws Exception {
    Handler response = Handlers.all(
        Handlers.SSE.start(),
        Handlers.SSE.event("message", "data1")
        // Connection closes, causing StreamClosedByServerException
        );

    try (HttpServer server = HttpServer.start(response)) {
      try (EventSource es = new EventSource.Builder(server.getUri())
          .errorStrategy(ErrorStrategy.alwaysContinue())
          .build()) {
        es.start();

        // Read the message
        assertThat(es.readAnyEvent(), equalTo(
            new MessageEvent("message", "data1", null, es.getOrigin())));

        // Get the fault event
        StreamEvent event = es.readAnyEvent();
        assertThat(event instanceof FaultEvent, equalTo(true));
        FaultEvent fault = (FaultEvent) event;

        // Verify it's not an HTTP error
        assertThat(fault.getCause() instanceof StreamClosedByServerException, equalTo(true));

        // Headers should be null for non-HTTP errors
        assertThat(fault.getHeaders(), nullValue());
      }
    }
  }
}
