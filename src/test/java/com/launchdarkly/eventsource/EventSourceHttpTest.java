package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.Stubs.LogItem;
import com.launchdarkly.eventsource.Stubs.TestHandler;
import com.launchdarkly.testhelpers.httptest.Handler;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.launchdarkly.eventsource.TestHandlers.chunksFromString;
import static com.launchdarkly.eventsource.TestHandlers.streamThatStaysOpen;
import static com.launchdarkly.testhelpers.httptest.Handlers.hang;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.RequestBody;

/**
 * End-to-end tests using real HTTP, not including specific test subsets like EventSourceHttpReconnectTest.
 */
@SuppressWarnings("javadoc")
public class EventSourceHttpTest {
  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();

  @Test
  public void eventSourceSetsRequestProperties() throws Exception {
    String requestPath = "/some/path";
    Headers headers = new Headers.Builder().add("header1", "value1").add("header2", "value2").build();
    
    try (HttpServer server = HttpServer.start(hang())) {
      try (EventSource es = new EventSource.Builder(new TestHandler(testLogger.getLogger()), server.getUri().resolve(requestPath))
          .headers(headers)
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        RequestInfo r = server.getRecorder().requireRequest();
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
    
    try (HttpServer server = HttpServer.start(hang())) {
      try (EventSource es = new EventSource.Builder(new TestHandler(testLogger.getLogger()), server.getUri())
          .method("report")
          .body(RequestBody.create("hello world", MediaType.parse("text/plain; charset=utf-8")))
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        RequestInfo r = server.getRecorder().requireRequest();
        assertEquals("REPORT", r.getMethod());
        assertEquals(content, r.getBody());
      }
    }
  }

  @Test
  public void configuredLastEventIdIsIncludedInHeaders() throws Exception {
    String lastId = "123";
    
    try (HttpServer server = HttpServer.start(hang())) {
      try (EventSource es = new EventSource.Builder(new TestHandler(testLogger.getLogger()), server.getUri())
          .lastEventId(lastId)
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        RequestInfo r = server.getRecorder().requireRequest();
        assertEquals(lastId, r.getHeader("Last-Event-Id"));
      }
    }
  }

  @Test
  public void emptyLastEventIdIsIgnored() throws Exception {
    try (HttpServer server = HttpServer.start(hang())) {
      try (EventSource es = new EventSource.Builder(new TestHandler(testLogger.getLogger()), server.getUri())
          .lastEventId("")
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        RequestInfo r = server.getRecorder().requireRequest();
        assertNull(r.getHeader("Last-Event-Id"));
      }
    }
  }
  
  @Test
  public void lastEventIdIsUpdatedFromEvent() throws Exception {
    String newLastId = "099";
    String eventType = "thing";
    String eventData = "some-data";
    
    final String body = "event: " + eventType + "\nid: " + newLastId + "\ndata: " + eventData;
    
    TestHandler eventSink = new TestHandler(testLogger.getLogger());
    Semaphore closeStreamSemaphore = new Semaphore(0);
    Handler streamHandler = Handlers.all(
        Handlers.SSE.start(),
        Handlers.SSE.event(body),
        Handlers.waitFor(closeStreamSemaphore)
        );

    try (HttpServer server = HttpServer.start(streamHandler)) {
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTime(10, null)
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        assertEquals(Stubs.LogItem.opened(), eventSink.awaitLogItem());
        assertEquals(Stubs.LogItem.event(eventType, eventData, newLastId), eventSink.awaitLogItem());
        assertEquals(newLastId, es.getLastEventId());

        RequestInfo r0 = server.getRecorder().requireRequest();
        assertNull(r0.getHeader("Last-Event-Id"));
        
        closeStreamSemaphore.release(); // force stream to reconnect
       
        RequestInfo r1 = server.getRecorder().requireRequest();
        assertEquals(newLastId, r1.getHeader("Last-Event-Id"));
      }
    }
  }

  @Test
  public void reconnectIntervalIsUpdatedFromEvent() throws Exception {
    String eventType = "thing";
    String eventData = "some-data";
    
    final String body = "retry: 300\n" + "event: " + eventType + "\ndata: " + eventData;
    
    TestHandler eventSink = new TestHandler(testLogger.getLogger());
    Handler streamHandler = streamThatStaysOpen(body);

    try (HttpServer server = HttpServer.start(streamHandler)) {
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTime(10, null)
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        assertEquals(Stubs.LogItem.opened(), eventSink.awaitLogItem());
        assertEquals(Stubs.LogItem.event(eventType, eventData, null), eventSink.awaitLogItem());
        
        assertEquals(300, es.reconnectTimeMillis);
      }
    }
  }

  @Test
  public void eventSourceConnects() throws Exception {
    TestHandler eventSink = new TestHandler(testLogger.getLogger());

    try (HttpServer server = HttpServer.start(streamThatStaysOpen())) {
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
    
    try (HttpServer server = HttpServer.start(streamThatStaysOpen())) {
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
    
    try (HttpServer server = HttpServer.start(streamThatStaysOpen())) {
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
    
    try (HttpServer server = HttpServer.start(streamThatStaysOpen())) {
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
    Handler streamHandler = chunksFromString(body, 5, 0, true);
    
    try (HttpServer server = HttpServer.start(streamHandler)) {
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
    
    assertEquals(LogItem.closed(), eventSink.awaitLogItem());
  }

  @Test
  public void processDataWithFixedQueueSize() throws Exception {
    final String body = "data: data-by-itself\n\n" +
            "event: event-with-data\n" +
            "data: abc\n\n" +
            ": this is a comment\n" +
            "event: event-with-more-data-and-id\n" +
            "id: my-id\n" +
            "data: abc\n" +
            "data: def\n\n";

    TestHandler eventSink = new TestHandler(testLogger.getLogger());
    Handler streamHandler = chunksFromString(body, 5, 0, true);

    try (HttpServer server = HttpServer.start(streamHandler)) {
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
              .maxEventTasksInFlight(1)
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

    assertEquals(LogItem.closed(), eventSink.awaitLogItem());
  }

  @Test(timeout = 15000)
  public void canAwaitClosed() throws Exception {
    final String body = "data: event1\n\n";

    AtomicBoolean received = new AtomicBoolean(false);
    TestHandler eventSink = new TestHandler(testLogger.getLogger()) {
      @Override
      public void onMessage(String event, MessageEvent messageEvent) throws Exception {
        received.set(true);
        super.onMessage(event, messageEvent);
      }
    };
    Handler streamHandler = chunksFromString(body, 5, 0, true);

    try (HttpServer server = HttpServer.start(streamHandler)) {
      EventSource es = new EventSource.Builder(eventSink, server.getUri())
              .logger(testLogger.getLogger())
              .build();
      try {
        es.start();

        assertEquals(LogItem.opened(), eventSink.awaitLogItem());

        assertEquals(LogItem.event("message", "event1"), // "message" is the default event name, per SSE spec
                eventSink.awaitLogItem());
      } finally {
        es.close();
        assertTrue("Expected close to complete", es.awaitClosed(10, TimeUnit.SECONDS));
        assertTrue(received.get());
      }
    }

    assertEquals(LogItem.closed(), eventSink.awaitLogItem());
  }

  @Test
  public void awaitClosedTimesOutIfEventHandlerNotCompleted() throws Exception {
    final String body = "data: event1\n\n";

    CountDownLatch enteredHandler = new CountDownLatch(1);
    CountDownLatch handlerCanProceed = new CountDownLatch(1);
    TestHandler eventSink = new TestHandler(testLogger.getLogger()) {
      @Override
      public void onMessage(String event, MessageEvent messageEvent) throws Exception {
        enteredHandler.countDown();
        handlerCanProceed.await(5, TimeUnit.SECONDS);
        super.onMessage(event, messageEvent);
      }
    };
    Handler streamHandler = chunksFromString(body, 5, 0, true);

    try (HttpServer server = HttpServer.start(streamHandler)) {
      EventSource es = new EventSource.Builder(eventSink, server.getUri())
              .logger(testLogger.getLogger())
              .build();
      try {
        es.start();

        assertEquals(LogItem.opened(), eventSink.awaitLogItem());

        assertTrue("expected handler to be called", enteredHandler.await(1, TimeUnit.SECONDS));
      } finally {
        es.close();
       
        try {
          assertFalse("Expected awaitClosed to time out", es.awaitClosed(2, TimeUnit.SECONDS));
        } finally {
          handlerCanProceed.countDown();
        }
      }
    }
  }
  
  @Test
  public void defaultThreadPriorityIsNotMaximum() throws Exception {
    ThreadCapturingHandler threadCapturingHandler = new ThreadCapturingHandler();
    
    try (HttpServer server = HttpServer.start(streamThatStaysOpen())) {
      try (EventSource es = new EventSource.Builder(threadCapturingHandler, server.getUri())
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        Thread handlerThread = threadCapturingHandler.capturedThreads.take();
        
        assertNotEquals(Thread.MAX_PRIORITY, handlerThread.getPriority());
      }
    }
  }
  
  @Test
  public void canSetSpecificThreadPriority() throws Exception {
    ThreadCapturingHandler threadCapturingHandler = new ThreadCapturingHandler();
    
    try (HttpServer server = HttpServer.start(streamThatStaysOpen())) {
      try (EventSource es = new EventSource.Builder(threadCapturingHandler, server.getUri())
          .threadPriority(Thread.MAX_PRIORITY)
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        Thread handlerThread = threadCapturingHandler.capturedThreads.take();
        
        assertEquals(Thread.MAX_PRIORITY, handlerThread.getPriority());
      }
    }
  }
  
  @Test
  public void threadsAreStoppedAfterExplicitShutdown() throws Exception {
    String name = "MyTestSource";
    TestHandler eventSink = new TestHandler(testLogger.getLogger());
    
    try (HttpServer server = HttpServer.start(streamThatStaysOpen())) {
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .name(name)
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        assertNumberOfThreadsWithSubstring(name, 2, 2000);

        es.close();
        
        assertNumberOfThreadsWithSubstring(name, 0, 2000);
      }
    }
  }

  @Test
  public void threadsAreStoppedAfterShutdownIsForcedByConnectionErrorHandler() throws Exception {
    String name = "MyTestSource";
    TestHandler eventSink = new TestHandler(testLogger.getLogger());
    ConnectionErrorHandler errorHandler = e -> ConnectionErrorHandler.Action.SHUTDOWN;
    
    Semaphore closeStreamSemaphore = new Semaphore(0);
    Handler streamHandler = Handlers.all(
        Handlers.SSE.start(),
        Handlers.waitFor(closeStreamSemaphore)
        );
    
    try (HttpServer server = HttpServer.start(streamHandler)) {
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .name(name)
          .connectionErrorHandler(errorHandler)
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());
        
        assertNumberOfThreadsWithSubstring(name, 2, 2000);

        closeStreamSemaphore.release();
        assertEquals("closed", eventSink.awaitLogItem().action);
        
        assertNumberOfThreadsWithSubstring(name, 0, 2000);
      }
    }
  }
  
  private void assertNumberOfThreadsWithSubstring(String s, int expectedCount, long timeoutMillis) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    int count = 0;
    while (System.currentTimeMillis() < deadline) {
      int n = Thread.currentThread().getThreadGroup().activeCount();
      Thread[] ts = new Thread[n];
      Thread.currentThread().getThreadGroup().enumerate(ts, true);
      count = 0;
      for (Thread t: ts) {
        if (t != null && t.isAlive() && t.getName().contains(s)) {
          count++;
        }
      }
      if (count == expectedCount) {
        return;        
      }
      Thread.sleep(50);
    }
    fail("wanted " + expectedCount + " threads with substring '" + s
        + "' but found " + count + " after " + timeoutMillis + "ms");
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
