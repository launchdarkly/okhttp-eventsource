package com.launchdarkly.eventsource;

import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.launchdarkly.eventsource.StubServer.Handlers.forRequestsInSequence;
import static com.launchdarkly.eventsource.StubServer.Handlers.hang;
import static com.launchdarkly.eventsource.StubServer.Handlers.interruptible;
import static com.launchdarkly.eventsource.StubServer.Handlers.returnStatus;
import static com.launchdarkly.eventsource.StubServer.Handlers.stream;
import static com.launchdarkly.eventsource.StubServer.Handlers.streamProducerFromChunkedString;
import static com.launchdarkly.eventsource.StubServer.Handlers.streamProducerFromString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import okhttp3.Headers;

@SuppressWarnings("javadoc")
public class EventSourceHttpTest {
  private static final String CONTENT_TYPE = "text/event-stream";
  
  @Test
  public void eventSourceSetsRequestProperties() throws Exception {
    String requestPath = "/some/path";
    Headers headers = new Headers.Builder().add("header1", "value1").add("header2", "value2").build();
    
    try (StubServer server = StubServer.start(hang())) {
      try (EventSource es = new EventSource.Builder(new TestHandler(), server.getUri().resolve(requestPath))
          .headers(headers)
          .build()) {
        es.start();
        
        StubServer.RequestInfo r = server.awaitRequest();
        assertEquals(requestPath, r.getPath());
        assertEquals("value1", r.getHeader("header1"));
        assertEquals("value2", r.getHeader("header2"));
      }
    }
  }
  
  @Test
  public void eventSourceReadsChunkedResponse() throws Exception {
    final String body = "data: data-by-itself\n\n" +
            "event: event-with-data\n" +
            "data: abc\n\n" +
            ": this is a comment\n" +
            "event: event-with-more-data-and-id\n" +
            "id: my-id\n" +
            "data: abc\n" +
            "data: def\n\n";
    
    TestHandler eventSink = new TestHandler();
    StubServer.Handler streamHandler = stream(CONTENT_TYPE,
        streamProducerFromChunkedString(body, 5, Duration.ZERO, true));
    
    try (StubServer server = StubServer.start(streamHandler)) {
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri()).build()) {
        es.start();
        
        assertEquals(LogItem.opened(), eventSink.log.take());
        
        assertEquals(LogItem.event("message", "data-by-itself"), // "message" is the default event name, per SSE spec
            eventSink.log.take());

        assertEquals(LogItem.event("event-with-data", "abc"),
            eventSink.log.take());
 
        assertEquals(LogItem.comment("this is a comment"),
            eventSink.log.take());
  
        assertEquals(LogItem.event("event-with-more-data-and-id",  "abc\ndef", "my-id"),
            eventSink.log.take());
        
        eventSink.assertNoMoreLogItems();
      }
    }
    assertEquals(LogItem.closed(), eventSink.log.take());
  }
  
  @Test
  public void eventSourceReconnectsAfterSocketClosed() throws Exception {
    String body1 = "data: first\n\n";
    String body2 = "data: second\n\n";

    TestHandler eventSink = new TestHandler();
    
    StubServer.InterruptibleHandler streamHandler1 = interruptible(stream(CONTENT_TYPE, streamProducerFromString(body1, true)));
    StubServer.Handler streamHandler2 = stream(CONTENT_TYPE, streamProducerFromString(body2, true));
    StubServer.Handler allRequests = forRequestsInSequence(streamHandler1, streamHandler2);
        
    try (StubServer server = StubServer.start(allRequests)) {      
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTimeMs(10)
          .build()) {
        es.start();
        
        assertEquals(LogItem.opened(), eventSink.log.take());
        
        assertEquals(LogItem.event("message", "first"),
            eventSink.log.take());

        eventSink.assertNoMoreLogItems(); // should not have closed first stream yet
        
        streamHandler1.interrupt();

        assertEquals(LogItem.closed(), eventSink.log.take());
       
        assertEquals(LogItem.opened(), eventSink.log.take());

        assertEquals(LogItem.event("message", "second"),
            eventSink.log.take());
      }
      
      assertEquals(LogItem.closed(), eventSink.log.take());
      eventSink.assertNoMoreLogItems();
    }
  }

  @Test
  public void eventSourceReconnectsAfterErrorOnFirstRequest() throws Exception {
    String body = "data: good\n\n";

    TestHandler eventSink = new TestHandler();

    StubServer.Handler streamHandler = stream(CONTENT_TYPE, streamProducerFromString(body, true));
    StubServer.Handler allRequests = forRequestsInSequence(returnStatus(500), streamHandler);
    
    try (StubServer server = StubServer.start(allRequests)) {            
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTimeMs(10)
          .build()) {
        es.start();
       
        assertEquals(LogItem.error(new UnsuccessfulResponseException(500)),
            eventSink.log.take());
        
        assertEquals(LogItem.opened(), eventSink.log.take());
        
        assertEquals(LogItem.event("message", "good"),
            eventSink.log.take());
        
        eventSink.assertNoMoreLogItems();
      }
      
      assertEquals(LogItem.closed(), eventSink.log.take());
    }
  }

  @Test
  public void eventSourceReconnectsAgainAfterErrorOnFirstReconnect() throws Exception {
    String body1 = "data: first\n\n";
    String body2 = "data: second\n\n";

    TestHandler eventSink = new TestHandler();

    StubServer.InterruptibleHandler streamHandler1 = interruptible(stream(CONTENT_TYPE, streamProducerFromString(body1, true)));
    StubServer.Handler streamHandler2 = stream(CONTENT_TYPE, streamProducerFromString(body2, true));
    StubServer.Handler allRequests = forRequestsInSequence(streamHandler1, returnStatus(500), streamHandler2);
    
    try (StubServer server = StubServer.start(allRequests)) {            
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTimeMs(10)
          .build()) {
        es.start();
       
        assertEquals(LogItem.opened(), eventSink.log.take());
        
        assertEquals(LogItem.event("message", "first"),
            eventSink.log.take());
        
        eventSink.assertNoMoreLogItems();
        
        streamHandler1.interrupt(); // make first stream fail
        
        assertEquals(LogItem.closed(), eventSink.log.take());
        
        assertEquals(LogItem.error(new UnsuccessfulResponseException(500)),
            eventSink.log.take());
        
        assertEquals(LogItem.opened(), eventSink.log.take());
        
        assertEquals(LogItem.event("message", "second"),
            eventSink.log.take());
        
        eventSink.assertNoMoreLogItems();
      }
      
      assertEquals(LogItem.closed(), eventSink.log.take());
    }
  }

  @Test
  public void streamDoesNotReconnectIfConnectionErrorHandlerSaysToStop() throws Exception {
    final AtomicBoolean calledHandler = new AtomicBoolean(false);
    final AtomicReference<Throwable> receivedError = new AtomicReference<Throwable>();
    
    ConnectionErrorHandler connectionErrorHandler = new ConnectionErrorHandler() {
      public Action onConnectionError(Throwable t) {
        calledHandler.set(true);
        receivedError.set(t);
        return Action.SHUTDOWN;
      }
    };
    
    TestHandler eventSink = new TestHandler();

    try (StubServer server = StubServer.start(returnStatus(500))) {            
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .connectionErrorHandler(connectionErrorHandler)
          .reconnectTimeMs(10)
          .build()) {
        es.start();
       
        // If a ConnectionErrorHandler returns SHUTDOWN, EventSource does not call onClosed() or onError()
        // on the regular event handler, since it assumes that the caller already knows what happened.
        // Therefore we don't expect to see any items in eventSink.
        eventSink.assertNoMoreLogItems();

        assertEquals(ReadyState.SHUTDOWN, es.getState());
      }
    }
    
    assertTrue(calledHandler.get());
    assertNotNull(receivedError.get());
    assertEquals(UnsuccessfulResponseException.class, receivedError.get().getClass());
  }
  
  @Test
  public void canForceEventSourceToRestart() throws Exception {
    String body1 = "data: first\n\n";
    String body2 = "data: second\n\n";

    TestHandler eventSink = new TestHandler();

    StubServer.Handler streamHandler1 = stream(CONTENT_TYPE, streamProducerFromString(body1, true));
    StubServer.Handler streamHandler2 = stream(CONTENT_TYPE, streamProducerFromString(body2, true));
    StubServer.Handler allRequests = forRequestsInSequence(streamHandler1, streamHandler2);
    
    try (StubServer server = StubServer.start(allRequests)) {            
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTimeMs(10)
          .build()) {
        es.start();
       
        assertEquals(LogItem.opened(), eventSink.log.take());
        
        assertEquals(LogItem.event("message", "first"),
            eventSink.log.take());
        
        eventSink.assertNoMoreLogItems();
        
        es.restart();
         
        assertEquals(LogItem.closed(), eventSink.log.take()); // there shouldn't be any error notification, just "closed"
        
        assertEquals(LogItem.opened(), eventSink.log.take());
        
        assertEquals(LogItem.event("message", "second"),
            eventSink.log.take());
        
        eventSink.assertNoMoreLogItems();
      }
      
      assertEquals(LogItem.closed(), eventSink.log.take());
    }
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
    
    void assertNoMoreLogItems() {
      try {
        assertNull(log.poll(100, TimeUnit.MILLISECONDS));
      } catch (InterruptedException e) {}
    }
  }
}
