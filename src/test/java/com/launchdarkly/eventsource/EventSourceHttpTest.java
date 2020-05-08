package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.Stubs.LogItem;
import com.launchdarkly.eventsource.Stubs.TestHandler;

import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.RequestBody;

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
        assertEquals("GET", r.getMethod());
        assertEquals(requestPath, r.getPath());
        assertEquals("value1", r.getHeader("header1"));
        assertEquals("value2", r.getHeader("header2"));
        assertEquals("text/event-stream", r.getHeader("Accept"));
        assertEquals("no-cache", r.getHeader("Cache-Control"));
      }
    }
  }

  @Test
  public void customMethodWithBody() throws IOException {
    String content = "hello world";
    
    try (StubServer server = StubServer.start(hang())) {
      try (EventSource es = new EventSource.Builder(new TestHandler(), server.getUri())
          .method("report")
          .body(RequestBody.create("hello world", MediaType.parse("text/plain; charset=utf-8")))
          .build()) {
        es.start();
        
        StubServer.RequestInfo r = server.awaitRequest();
        assertEquals("REPORT", r.getMethod());
        assertEquals(content, r.getBody());
      }
    }
  }

  @Test
  public void configuredLastEventIdIsIncludedInHeaders() throws Exception {
    String lastId = "123";
    
    try (StubServer server = StubServer.start(hang())) {
      try (EventSource es = new EventSource.Builder(new TestHandler(), server.getUri())
          .lastEventId(lastId)
          .build()) {
        es.start();
        
        StubServer.RequestInfo r = server.awaitRequest();
        assertEquals(lastId, r.getHeader("Last-Event-Id"));
      }
    }
  }
  
  @Test
  public void lastEventIdIsUpdatedFromEvent() throws Exception {
    String newLastId = "099";
    String eventType = "thing";
    String eventData = "some-data";
    
    final String body = "event: " + eventType + "\nid: " + newLastId + "\ndata: " + eventData + "\n\n";
    
    TestHandler eventSink = new TestHandler();
    StubServer.InterruptibleHandler streamHandler = interruptible(stream(CONTENT_TYPE, streamProducerFromString(body, true)));

    try (StubServer server = StubServer.start(streamHandler)) {
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTime(Duration.ofMillis(10))
          .build()) {
        es.start();
        
        assertEquals(Stubs.LogItem.opened(), eventSink.log.take());
        assertEquals(Stubs.LogItem.event(eventType, eventData, newLastId), eventSink.log.take());

        StubServer.RequestInfo r0 = server.awaitRequest();
        assertNull(r0.getHeader("Last-Event-Id"));
        
        streamHandler.interrupt(); // force stream to reconnect
       
        StubServer.RequestInfo r1 = server.awaitRequest();
        assertEquals(newLastId, r1.getHeader("Last-Event-Id"));
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
          .reconnectTime(Duration.ofMillis(10))
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
          .reconnectTime(Duration.ofMillis(10))
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
          .reconnectTime(Duration.ofMillis(10))
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
          .reconnectTime(Duration.ofMillis(10))
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
          .reconnectTime(Duration.ofMillis(10))
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
  
  @Test
  public void newLastEventIdIsSentOnNextConnectAttempt() throws Exception {
    String initialLastId = "123";
    String newLastId = "099";
    String body = "id: " + newLastId + "\ndata: first\n\n";
    
    TestHandler eventSink = new TestHandler();

    StubServer.Handler streamHandler1 = stream(CONTENT_TYPE, streamProducerFromString(body, false));
    StubServer.Handler streamHandler2 = stream(CONTENT_TYPE, streamProducerFromString("", true));
    StubServer.Handler allRequests = forRequestsInSequence(streamHandler1, streamHandler2);

    try (StubServer server = StubServer.start(allRequests)) {
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .lastEventId(initialLastId)
          .build()) {
        es.start();
        
        StubServer.RequestInfo req0 = server.awaitRequest();
        StubServer.RequestInfo req1 = server.awaitRequest();
        assertEquals(initialLastId, req0.getHeader("Last-Event-ID"));
        assertEquals(newLastId, req1.getHeader("Last-Event-ID"));
      }
    }
  }

  @Test
  public void defaultThreadPriorityIsNotMaximum() throws Exception {
    StubServer.Handler streamHandler = stream(CONTENT_TYPE, streamProducerFromString("", false));
    
    ThreadCapturingHandler threadCapturingHandler = new ThreadCapturingHandler();
    
    try (StubServer server = StubServer.start(streamHandler)) {
      try (EventSource es = new EventSource.Builder(threadCapturingHandler, server.getUri())
          .build()) {
        es.start();
        
        Thread handlerThread = threadCapturingHandler.capturedThreads.take();
        
        assertNotEquals(Thread.MAX_PRIORITY, handlerThread.getPriority());
      }
    }
  }
  
  @Test
  public void canSetSpecificThreadPriority() throws Exception {
    StubServer.Handler streamHandler = stream(CONTENT_TYPE, streamProducerFromString("", false));
    
    ThreadCapturingHandler threadCapturingHandler = new ThreadCapturingHandler();
    
    try (StubServer server = StubServer.start(streamHandler)) {
      try (EventSource es = new EventSource.Builder(threadCapturingHandler, server.getUri())
          .threadPriority(Thread.MAX_PRIORITY)
          .build()) {
        es.start();
        
        Thread handlerThread = threadCapturingHandler.capturedThreads.take();
        
        assertEquals(Thread.MAX_PRIORITY, handlerThread.getPriority());
      }
    }
  }
  
  private static class ThreadCapturingHandler implements EventHandler {
    final BlockingQueue<Thread> capturedThreads = new LinkedBlockingQueue<>();
    
    public void onOpen() throws Exception {
      capturedThreads.add(Thread.currentThread());
    }
    
    public void onMessage(String event, MessageEvent messageEvent) throws Exception {}
    public void onError(Throwable t) {}
    public void onComment(String comment) {}
    public void onClosed() {}
  }
}
