package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.Stubs.LogItem;
import com.launchdarkly.eventsource.Stubs.TestHandler;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static com.launchdarkly.eventsource.StubServer.Handlers.chunkFromString;
import static com.launchdarkly.eventsource.StubServer.Handlers.chunksFromString;
import static com.launchdarkly.eventsource.StubServer.Handlers.hang;
import static com.launchdarkly.eventsource.StubServer.Handlers.interruptible;
import static com.launchdarkly.eventsource.StubServer.Handlers.stream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.RequestBody;

/**
 * End-to-end tests using real HTTP, not including specific test subsets like EventSourceHttpReconnectTest.
 */
@SuppressWarnings("javadoc")
public class EventSourceHttpTest {
  private static final String CONTENT_TYPE = "text/event-stream";
  
  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();

  // NOTE ABOUT KNOWN ISSUE: Intermittent test failures suggest that sometimes the handler's onClose()
  // method does not get called when the stream is completely shut down. This is not a new issue, and
  // it does not affect the way the LaunchDarkly SDKs use EventSource. So, for now, test assertions
  // for that method are commented out.
  
  @Test
  public void eventSourceSetsRequestProperties() throws Exception {
    String requestPath = "/some/path";
    Headers headers = new Headers.Builder().add("header1", "value1").add("header2", "value2").build();
    
    try (StubServer server = StubServer.start(hang())) {
      try (EventSource es = new EventSource.Builder(new TestHandler(testLogger.getLogger()), server.getUri().resolve(requestPath))
          .headers(headers)
          .logger(testLogger.getLogger())
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
      try (EventSource es = new EventSource.Builder(new TestHandler(testLogger.getLogger()), server.getUri())
          .method("report")
          .body(RequestBody.create("hello world", MediaType.parse("text/plain; charset=utf-8")))
          .logger(testLogger.getLogger())
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
      try (EventSource es = new EventSource.Builder(new TestHandler(testLogger.getLogger()), server.getUri())
          .lastEventId(lastId)
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        StubServer.RequestInfo r = server.awaitRequest();
        assertEquals(lastId, r.getHeader("Last-Event-Id"));
      }
    }
  }

  @Test
  public void emptyLastEventIdIsIgnored() throws Exception {
    try (StubServer server = StubServer.start(hang())) {
      try (EventSource es = new EventSource.Builder(new TestHandler(testLogger.getLogger()), server.getUri())
          .lastEventId("")
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        StubServer.RequestInfo r = server.awaitRequest();
        assertNull(r.getHeader("Last-Event-Id"));
      }
    }
  }
  
  @Test
  public void lastEventIdIsUpdatedFromEvent() throws Exception {
    String newLastId = "099";
    String eventType = "thing";
    String eventData = "some-data";
    
    final String body = "event: " + eventType + "\nid: " + newLastId + "\ndata: " + eventData + "\n\n";
    
    TestHandler eventSink = new TestHandler(testLogger.getLogger());
    StubServer.InterruptibleHandler streamHandler = interruptible(stream(CONTENT_TYPE, chunkFromString(body, true)));

    try (StubServer server = StubServer.start(streamHandler)) {
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTime(Duration.ofMillis(10))
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        assertEquals(Stubs.LogItem.opened(), eventSink.awaitLogItem());
        assertEquals(Stubs.LogItem.event(eventType, eventData, newLastId), eventSink.awaitLogItem());
        assertEquals(newLastId, es.getLastEventId());

        StubServer.RequestInfo r0 = server.awaitRequest();
        assertNull(r0.getHeader("Last-Event-Id"));
        
        streamHandler.interrupt(); // force stream to reconnect
       
        StubServer.RequestInfo r1 = server.awaitRequest();
        assertEquals(newLastId, r1.getHeader("Last-Event-Id"));
      }
    }
  }

  @Test
  public void reconnectIntervalIsUpdatedFromEvent() throws Exception {
    String eventType = "thing";
    String eventData = "some-data";
    
    final String body = "retry: 300\n" + "event: " + eventType + "\ndata: " + eventData + "\n\n";
    
    TestHandler eventSink = new TestHandler(testLogger.getLogger());
    StubServer.InterruptibleHandler streamHandler = interruptible(stream(CONTENT_TYPE, chunkFromString(body, true)));

    try (StubServer server = StubServer.start(streamHandler)) {
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTime(Duration.ofMillis(10))
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        assertEquals(Stubs.LogItem.opened(), eventSink.awaitLogItem());
        assertEquals(Stubs.LogItem.event(eventType, eventData, null), eventSink.awaitLogItem());
        
        assertEquals(Duration.ofMillis(300), es.reconnectTime);
      }
    }
  }

  @Test
  public void eventSourceConnects() throws Exception {
    TestHandler eventSink = new TestHandler(testLogger.getLogger());
    StubServer.Handler streamHandler = stream(CONTENT_TYPE, chunkFromString("", true));
    
    try (StubServer server = StubServer.start(streamHandler)) {
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());
 
        eventSink.assertNoMoreLogItems();
      }
    }
  }

  @Test
  public void startingAlreadyStartedEventSourceHasNoEffect() throws Exception {
    TestHandler eventSink = new TestHandler(testLogger.getLogger());
    StubServer.Handler streamHandler = stream(CONTENT_TYPE, chunkFromString("", true));
    
    try (StubServer server = StubServer.start(streamHandler)) {
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());
 
        es.start();
        
        Thread.sleep(50);
        
        eventSink.assertNoMoreLogItems();
      }
    }
  }

  @Test
  public void restartingNotYetStartedEventSourceIsTheSameAsStartingIt() throws Exception {
    TestHandler eventSink = new TestHandler(testLogger.getLogger());
    StubServer.Handler streamHandler = stream(CONTENT_TYPE, chunkFromString("", true));
    
    try (StubServer server = StubServer.start(streamHandler)) {
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .logger(testLogger.getLogger())
          .build()) {
        eventSink.assertNoMoreLogItems();

        es.restart();
        
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());
 
        eventSink.assertNoMoreLogItems();
      }
    }
  }

  @Test
  public void restartingAlreadyClosedEventSourceHasNoEffect() throws Exception {
    TestHandler eventSink = new TestHandler(testLogger.getLogger());
    StubServer.Handler streamHandler = stream(CONTENT_TYPE, chunkFromString("", true));
    
    try (StubServer server = StubServer.start(streamHandler)) {
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());

        es.close();
       
        assertEquals(LogItem.closed(), eventSink.awaitLogItem());

        es.restart();
       
        assertEquals(ReadyState.SHUTDOWN, es.getState());
        
        eventSink.assertNoMoreLogItems();
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
    
    TestHandler eventSink = new TestHandler(testLogger.getLogger());
    StubServer.Handler streamHandler = stream(CONTENT_TYPE, chunksFromString(body, 5, Duration.ZERO, true));
    
    try (StubServer server = StubServer.start(streamHandler)) {
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());
        
        assertEquals(LogItem.event("message", "data-by-itself"), // "message" is the default event name, per SSE spec
            eventSink.awaitLogItem());

        assertEquals(LogItem.event("event-with-data", "abc"),
            eventSink.awaitLogItem());
 
        assertEquals(LogItem.comment("this is a comment"),
            eventSink.awaitLogItem());
  
        assertEquals(LogItem.event("event-with-more-data-and-id",  "abc\ndef", "my-id"),
            eventSink.awaitLogItem());
        
        eventSink.assertNoMoreLogItems();
      }
    }
    // assertEquals(LogItem.closed(), eventSink.awaitLogItem()); // see NOTE ON KNOWN ISSUE
  }
  
  @Test
  public void defaultThreadPriorityIsNotMaximum() throws Exception {
    StubServer.Handler streamHandler = stream(CONTENT_TYPE, chunkFromString("", false));
    
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
    StubServer.Handler streamHandler = stream(CONTENT_TYPE, chunkFromString("", false));
    
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
