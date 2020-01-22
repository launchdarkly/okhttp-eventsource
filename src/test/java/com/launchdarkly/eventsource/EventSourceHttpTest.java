package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.Stubs.LogItem;
import com.launchdarkly.eventsource.Stubs.TestHandler;

import org.junit.Test;

import java.time.Duration;

import static com.launchdarkly.eventsource.Stubs.createErrorResponse;
import static com.launchdarkly.eventsource.Stubs.createEventsResponse;
import static org.junit.Assert.assertEquals;

import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;

@SuppressWarnings("javadoc")
public class EventSourceHttpTest {
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
          .reconnectTime(Duration.ofMillis(10))
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
          .reconnectTime(Duration.ofMillis(10))
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
          .reconnectTime(Duration.ofMillis(10))
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
}
