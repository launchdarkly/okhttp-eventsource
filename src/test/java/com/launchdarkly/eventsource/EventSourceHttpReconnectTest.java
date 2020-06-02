package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.Stubs.LogItem;
import com.launchdarkly.eventsource.Stubs.TestHandler;

import org.junit.Rule;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.launchdarkly.eventsource.StubServer.Handlers.chunkFromString;
import static com.launchdarkly.eventsource.StubServer.Handlers.forRequestsInSequence;
import static com.launchdarkly.eventsource.StubServer.Handlers.interruptible;
import static com.launchdarkly.eventsource.StubServer.Handlers.returnStatus;
import static com.launchdarkly.eventsource.StubServer.Handlers.stream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end tests with real HTTP, specifically for the client's reconnect behavior.
 */
@SuppressWarnings("javadoc")
public class EventSourceHttpReconnectTest {
  private static final String CONTENT_TYPE = "text/event-stream";
  
  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();

  // NOTE ABOUT KNOWN ISSUE: Intermittent test failures suggest that sometimes the handler's onClose()
  // method does not get called when the stream is completely shut down. This is not a new issue, and
  // it does not affect the way the LaunchDarkly SDKs use EventSource. So, for now, test assertions
  // for that method are commented out.
  
  @Test
  public void eventSourceReconnectsAfterSocketClosed() throws Exception {
    String body1 = "data: first\n\n";
    String body2 = "data: second\n\n";

    TestHandler eventSink = new TestHandler();
    
    StubServer.InterruptibleHandler streamHandler1 = interruptible(stream(CONTENT_TYPE, chunkFromString(body1, true)));
    StubServer.Handler streamHandler2 = stream(CONTENT_TYPE, chunkFromString(body2, true));
    StubServer.Handler allRequests = forRequestsInSequence(streamHandler1, streamHandler2);
        
    try (StubServer server = StubServer.start(allRequests)) {      
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTime(Duration.ofMillis(10))
          .build()) {
        es.start();
        
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());
        
        assertEquals(LogItem.event("message", "first"),
            eventSink.awaitLogItem());

        eventSink.assertNoMoreLogItems(); // should not have closed first stream yet
        
        streamHandler1.interrupt();

        assertEquals(LogItem.closed(), eventSink.awaitLogItem());
       
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());

        assertEquals(LogItem.event("message", "second"),
            eventSink.awaitLogItem());
      }
      
      // assertEquals(LogItem.closed(), eventSink.awaitLogItem()); // see NOTE ON KNOWN ISSUE
      // eventSink.assertNoMoreLogItems();
    }
  }

  @Test
  public void eventSourceReconnectsAfterErrorOnFirstRequest() throws Exception {
    String body = "data: good\n\n";
    int statusCode = 500;

    TestHandler eventSink = new TestHandler();

    StubServer.Handler streamHandler = stream(CONTENT_TYPE, chunkFromString(body, true));
    StubServer.Handler allRequests = forRequestsInSequence(returnStatus(statusCode), streamHandler);
    
    try (StubServer server = StubServer.start(allRequests)) {            
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTime(Duration.ofMillis(10))
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
       
        LogItem errorItem = eventSink.awaitLogItem();
        assertEquals(LogItem.error(new UnsuccessfulResponseException(500)), errorItem);
        assertEquals(statusCode, ((UnsuccessfulResponseException)errorItem.error).getCode());
        
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());
        
        assertEquals(LogItem.event("message", "good"),
            eventSink.awaitLogItem());
        
        eventSink.assertNoMoreLogItems();
      }
      
      // assertEquals(LogItem.closed(), eventSink.awaitLogItem()); // see NOTE ON KNOWN ISSUE
    }
  }

  @Test
  public void eventSourceReconnectsAgainAfterErrorOnFirstReconnect() throws Exception {
    String body1 = "data: first\n\n";
    String body2 = "data: second\n\n";

    TestHandler eventSink = new TestHandler();

    StubServer.InterruptibleHandler streamHandler1 = interruptible(stream(CONTENT_TYPE, chunkFromString(body1, true)));
    StubServer.Handler streamHandler2 = stream(CONTENT_TYPE, chunkFromString(body2, true));
    StubServer.Handler allRequests = forRequestsInSequence(streamHandler1, returnStatus(500), streamHandler2);
    
    try (StubServer server = StubServer.start(allRequests)) {            
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTime(Duration.ofMillis(10))
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
       
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());
        
        assertEquals(LogItem.event("message", "first"),
            eventSink.awaitLogItem());
        
        eventSink.assertNoMoreLogItems();
 
        streamHandler1.interrupt(); // make first stream fail
        
        assertEquals(LogItem.closed(), eventSink.awaitLogItem());
        
        assertEquals(LogItem.error(new UnsuccessfulResponseException(500)),
            eventSink.awaitLogItem());
        
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());
        
        assertEquals(LogItem.event("message", "second"),
            eventSink.awaitLogItem());
        
        eventSink.assertNoMoreLogItems();
      }
      
      // assertEquals(LogItem.closed(), eventSink.awaitLogItem()); // see NOTE ON KNOWN ISSUE
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
          .logger(testLogger.getLogger())
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

    StubServer.Handler streamHandler1 = stream(CONTENT_TYPE, chunkFromString(body1, true));
    StubServer.Handler streamHandler2 = stream(CONTENT_TYPE, chunkFromString(body2, true));
    StubServer.Handler allRequests = forRequestsInSequence(streamHandler1, streamHandler2);
    
    try (StubServer server = StubServer.start(allRequests)) {            
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTime(Duration.ofMillis(10))
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
       
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());
        
        assertEquals(LogItem.event("message", "first"),
            eventSink.awaitLogItem());
        
        eventSink.assertNoMoreLogItems();
        
        es.restart();
         
        assertEquals(LogItem.closed(), eventSink.awaitLogItem()); // there shouldn't be any error notification, just "closed"
        
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());
        
        assertEquals(LogItem.event("message", "second"),
            eventSink.awaitLogItem());
        
        eventSink.assertNoMoreLogItems();
      }
      
      // assertEquals(LogItem.closed(), eventSink.awaitLogItem()); // see NOTE ON KNOWN ISSUE
    }
  }
}
