package com.launchdarkly.eventsource;

import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static org.junit.Assert.assertEquals;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;

public class EventSourceHttpTest {
  private static final int CHUNK_SIZE = 5;

  @Test
  public void eventSourceReadsChunkedResponse() throws Exception {
    String body = "data: data-by-itself\n\n" +
            "event: event-with-data\n" +
            "data: abc\n\n" +
            ": this is a comment\n" +
            "event: event-with-more-data-and-id\n" +
            "id: my-id\n" +
            "data: abc\n" +
            "data: def\n\n";

    TestHandler handler = new TestHandler();

    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(createEventsResponse(body, SocketPolicy.KEEP_OPEN));
      server.start();

      try (EventSource es = new EventSource.Builder(handler, server.url("/"))
          .build()) {
        es.start();

        assertEquals(LogItem.opened(), handler.log.take());

        assertEquals(LogItem.event("message", "data-by-itself"), // "message" is the default event name, per SSE spec
            handler.log.take());

        assertEquals(LogItem.event("event-with-data", "abc"),
            handler.log.take());

        assertEquals(LogItem.comment("this is a comment"),
            handler.log.take());

        assertEquals(LogItem.event("event-with-more-data-and-id",  "abc\ndef", "my-id"),
            handler.log.take());
      }

      assertEquals(LogItem.closed(), handler.log.take());
    }
  }

  @Test
  public void eventSourceReconnectsAfterSocketClosed() throws Exception {
    String body1 = "data: first\n\n";
    String body2 = "data: second\n\n";

    TestHandler handler = new TestHandler();

    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(createEventsResponse(body1, SocketPolicy.DISCONNECT_AT_END));
      server.enqueue(createEventsResponse(body2, SocketPolicy.KEEP_OPEN));
      server.start();

      try (EventSource es = new EventSource.Builder(handler, server.url("/"))
          .reconnectTimeMs(10)
          .build()) {
        es.start();

        assertEquals(LogItem.opened(), handler.log.take());

        assertEquals(LogItem.event("message", "first"),
            handler.log.take());

        assertEquals(LogItem.closed(), handler.log.take());

        assertEquals(LogItem.opened(), handler.log.take());

        assertEquals(LogItem.event("message", "second"),
            handler.log.take());
      }

      assertEquals(LogItem.closed(), handler.log.take());
    }
  }

  @Test
  public void eventSourceReconnectsAfterErrorOnFirstRequest() throws Exception {
    String body = "data: good\n\n";

    TestHandler handler = new TestHandler();

    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(createErrorResponse(500));
      server.enqueue(createEventsResponse(body, SocketPolicy.KEEP_OPEN));
      server.start();

      try (EventSource es = new EventSource.Builder(handler, server.url("/"))
          .reconnectTimeMs(10)
          .build()) {
        es.start();

        assertEquals(LogItem.error(new UnsuccessfulResponseException(500)),
            handler.log.take());

        assertEquals(LogItem.opened(), handler.log.take());

        assertEquals(LogItem.event("message", "good"),
            handler.log.take());
      }

      assertEquals(LogItem.closed(), handler.log.take());
    }
  }

  @Test
  public void eventSourceReconnectsAgainAfterErrorOnFirstReconnect() throws Exception {
    String body1 = "data: first\n\n";
    String body2 = "data: second\n\n";

    TestHandler handler = new TestHandler();

    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(createEventsResponse(body1, SocketPolicy.DISCONNECT_AT_END));
      server.enqueue(createErrorResponse(500));
      server.enqueue(createEventsResponse(body2, SocketPolicy.KEEP_OPEN));
      server.start();

      try (EventSource es = new EventSource.Builder(handler, server.url("/"))
          .reconnectTimeMs(10)
          .build()) {
        es.start();

        assertEquals(LogItem.opened(), handler.log.take());

        assertEquals(LogItem.event("message", "first"),
            handler.log.take());

        assertEquals(LogItem.closed(), handler.log.take());

        assertEquals(LogItem.error(new UnsuccessfulResponseException(500)),
            handler.log.take());

        assertEquals(LogItem.opened(), handler.log.take());

        assertEquals(LogItem.event("message", "second"),
            handler.log.take());
      }

      assertEquals(LogItem.closed(), handler.log.take());
    }
  }

  private MockResponse createEventsResponse(String body, SocketPolicy socketPolicy) {
    return new MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setChunkedBody(body, CHUNK_SIZE)
        .setSocketPolicy(socketPolicy);
  }

  private MockResponse createErrorResponse(int status) {
    return new MockResponse().setResponseCode(status);
  }

  static class LogItem {
    private final String action;
    private final String[] params;

    private LogItem(String action, String[] params) {
      this.action = action;
      this.params = params;
    }

    public static LogItem opened() {
      return new LogItem("opened", null);
    }

    public static LogItem closed() {
      return new LogItem("closed", null);
    }

    public static LogItem event(String eventName, String data) {
      return event(eventName, data, null);
    }

    public static LogItem event(String eventName, String data, String eventId) {
      if (eventId == null) {

      }
      return new LogItem("event", eventId == null ? new String[] { eventName, data } :
        new String[] { eventName, data, eventId });
    }

    public static LogItem comment(String comment) {
      return new LogItem("comment", new String[] { comment });
    }

    public static LogItem error(Throwable t) {
      return new LogItem("error", new String[] { t.toString() });
    }

    public String toString() {
      StringBuilder sb = new StringBuilder().append(action);
      if (params != null) {
        sb.append("(");
        for (int i = 0; i < params.length; i++) {
          if (i > 0) {
            sb.append(",");
          }
          sb.append(params[i]);
        }
        sb.append(")");
      }
      return sb.toString();
    }

    public boolean equals(Object o) {
      return (o instanceof LogItem) && toString().equals(o.toString());
    }

    public int hashCode() {
      return toString().hashCode();
    }
  }

  static class TestHandler implements EventHandler {
    public final BlockingQueue<LogItem> log = new ArrayBlockingQueue<>(100);

    public void onOpen() throws Exception {
      log.add(LogItem.opened());
    }

    public void onMessage(String event, MessageEvent messageEvent) throws Exception {
      log.add(LogItem.event(event, messageEvent.getData(), messageEvent.getLastEventId()));
    }

    public void onError(Throwable t) {
      log.add(LogItem.error(t));
    }

    public void onComment(String comment) throws Exception {
      log.add(LogItem.comment(comment));
    }

    @Override
    public void onClosed() throws Exception {
      log.add(LogItem.closed());
    }
  }
}
