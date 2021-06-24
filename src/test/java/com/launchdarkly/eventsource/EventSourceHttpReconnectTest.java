package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.Stubs.LogItem;
import com.launchdarkly.eventsource.Stubs.TestHandler;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.launchdarkly.eventsource.StubServer.Handlers.chunkFromString;
import static com.launchdarkly.eventsource.StubServer.Handlers.forRequestsInSequence;
import static com.launchdarkly.eventsource.StubServer.Handlers.interruptible;
import static com.launchdarkly.eventsource.StubServer.Handlers.ioError;
import static com.launchdarkly.eventsource.StubServer.Handlers.returnStatus;
import static com.launchdarkly.eventsource.StubServer.Handlers.stream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * End-to-end tests with real HTTP, specifically for the client's reconnect behavior.
 */
@SuppressWarnings("javadoc")
public class EventSourceHttpReconnectTest {
  private static final String CONTENT_TYPE = "text/event-stream";
  
  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();

  @Test
  public void eventSourceReconnectsAfterSocketClosed() throws Exception {
    verifyReconnectAfterStreamInterrupted(
        null,
        Duration.ofMillis(10),
        eventSink -> {
          assertEquals(LogItem.closed(), eventSink.awaitLogItem());
        });
  }
  
  private void verifyReconnectAfterStreamInterrupted(
      StubServer.Handler extraErrorAfterReconnectHandler,
      Duration reconnectTime,
      Consumer<TestHandler> checkExpectedEvents
      ) {
    String body1 = "data: first\n\n";
    String body2 = "data: second\n\n";

    TestHandler eventSink = new TestHandler();
    
    StubServer.InterruptibleHandler streamHandler1 = interruptible(stream(CONTENT_TYPE, chunkFromString(body1, true)));
    StubServer.Handler streamHandler2 = stream(CONTENT_TYPE, chunkFromString(body2, true));
    StubServer.Handler allRequests;
    if (extraErrorAfterReconnectHandler == null) {
      allRequests = forRequestsInSequence(streamHandler1, streamHandler2);
    } else {
      allRequests = forRequestsInSequence(streamHandler1, extraErrorAfterReconnectHandler, streamHandler2);
    }
        
    try (StubServer server = StubServer.start(allRequests)) {      
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTime(reconnectTime)
          .build()) {
        es.start();
        
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());
        
        assertEquals(LogItem.event("message", "first"),
            eventSink.awaitLogItem());

        eventSink.assertNoMoreLogItems(); // should not have closed first stream yet
        
        streamHandler1.interrupt();
        
        checkExpectedEvents.accept(eventSink);
       
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());

        assertEquals(LogItem.event("message", "second"),
            eventSink.awaitLogItem());
      }
      
      assertEquals(LogItem.closed(), eventSink.awaitLogItem());
      eventSink.assertNoMoreLogItems();
    }
  }

  @Test
  public void eventSourceReconnectsAfterHttpErrorOnFirstRequest() throws Exception {
    verifyReconnectAfterErrorOnFirstRequest(
        returnStatus(500),
        Duration.ofMillis(10),
        eventSink -> {
          LogItem errorItem = eventSink.awaitLogItem();
          assertEquals(LogItem.error(new UnsuccessfulResponseException(500)), errorItem);
          assertEquals(500, ((UnsuccessfulResponseException)errorItem.error).getCode());
        });
  }

  @Test
  public void eventSourceReconnectsAfterNetworkErrorOnFirstRequest() throws Exception {
    verifyReconnectAfterErrorOnFirstRequest(
        ioError(),
        Duration.ofMillis(10),
        eventSink -> {
          LogItem errorItem = eventSink.awaitLogItem();
          assertEquals(IOException.class, errorItem.error.getClass());
        });
  }

  private void verifyReconnectAfterErrorOnFirstRequest(
      StubServer.Handler errorProducer,
      Duration reconnectTime,
      Consumer<TestHandler> checkExpectedEvents
      ) {
    String body = "data: good\n\n";

    TestHandler eventSink = new TestHandler();
    
    StubServer.Handler streamHandler = stream(CONTENT_TYPE, chunkFromString(body, true));
    StubServer.Handler allRequests = forRequestsInSequence(errorProducer, streamHandler);
 
    try (StubServer server = StubServer.start(allRequests)) { 
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTime(reconnectTime)
          .logger(testLogger.getLogger())
          .build()) {
        es.start();

        checkExpectedEvents.accept(eventSink);
 
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());
        
        assertEquals(LogItem.event("message", "good"),
            eventSink.awaitLogItem());
        
        eventSink.assertNoMoreLogItems();
      }
      
      assertEquals(LogItem.closed(), eventSink.awaitLogItem());
      eventSink.assertNoMoreLogItems();
    }
  }

  @Test
  public void eventSourceReconnectsAgainAfterErrorOnFirstReconnect() throws Exception {
    verifyReconnectAfterStreamInterrupted(
        returnStatus(500),
        Duration.ofMillis(10),
        eventSink -> {
          assertEquals(LogItem.closed(), eventSink.awaitLogItem());
          assertEquals(LogItem.error(new UnsuccessfulResponseException(500)),
              eventSink.awaitLogItem());
        });
  }

  @Test
  public void eventSourceReconnectsEvenIfDelayIsZero() throws Exception {
    verifyReconnectAfterStreamInterrupted(
        null,
        Duration.ZERO,
        eventSink -> {
          assertEquals(LogItem.closed(), eventSink.awaitLogItem());
        });
  }

  @Test
  public void eventSourceReconnectsEvenIfDelayIsNegative() throws Exception {
    verifyReconnectAfterStreamInterrupted(
        null,
        Duration.ofMillis(-1),
        eventSink -> {
          assertEquals(LogItem.closed(), eventSink.awaitLogItem());
        });
  }

  @Test
  public void streamDoesNotReconnectIfConnectionErrorHandlerSaysToStop() throws Exception {
    final BlockingQueue<Throwable> receivedError = new ArrayBlockingQueue<Throwable>(1);
    
    ConnectionErrorHandler connectionErrorHandler = new ConnectionErrorHandler() {
      public Action onConnectionError(Throwable t) {
        receivedError.add(t);
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
       
        Throwable t = receivedError.poll(500, TimeUnit.MILLISECONDS);
        assertNotNull(t);
        assertEquals(UnsuccessfulResponseException.class, t.getClass());

        // There's no way to know exactly when EventSource has transitioned its state to
        // SHUTDOWN after calling the error handler, so this is an arbitrary delay
        Thread.sleep(100);

        // If a ConnectionErrorHandler returns SHUTDOWN, EventSource does not call onClosed() or onError()
        // on the regular event handler, since it assumes that the caller already knows what happened.
        // Therefore we don't expect to see any items in eventSink.
        eventSink.assertNoMoreLogItems();

        assertNotNull(server.awaitRequest(Duration.ZERO));
        assertNull(server.awaitRequest(Duration.ZERO)); // no more requests should have been made
        assertEquals(0, receivedError.size()); // error handler should have only been called once
        
        assertEquals(ReadyState.SHUTDOWN, es.getState());
      }
    }
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
      
       assertEquals(LogItem.closed(), eventSink.awaitLogItem());
    }
  }
}
