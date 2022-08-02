package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.Stubs.LogItem;
import com.launchdarkly.eventsource.Stubs.TestHandler;
import com.launchdarkly.testhelpers.httptest.Handler;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;

import org.junit.Rule;
import org.junit.Test;

import java.net.ProtocolException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.launchdarkly.eventsource.TestHandlers.streamThatStaysOpen;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * End-to-end tests with real HTTP, specifically for the client's reconnect behavior.
 */
@SuppressWarnings("javadoc")
public class EventSourceHttpReconnectTest {
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
      Handler extraErrorAfterReconnectHandler,
      Duration reconnectTime,
      Consumer<TestHandler> checkExpectedEvents
      ) throws Exception {
    String body1 = "data: first";
    String body2 = "data: second";

    TestHandler eventSink = new TestHandler();
    
    Semaphore closeStreamSemaphore = new Semaphore(0);
    Handler streamHandler1 = Handlers.all(
        Handlers.SSE.start(),
        Handlers.SSE.event(body1),
        Handlers.waitFor(closeStreamSemaphore)
        );
    Handler streamHandler2 = streamThatStaysOpen(body2);
    Handler allRequests;
    if (extraErrorAfterReconnectHandler == null) {
      allRequests = Handlers.sequential(streamHandler1, streamHandler2);
    } else {
      allRequests = Handlers.sequential(streamHandler1, extraErrorAfterReconnectHandler, streamHandler2);
    }
        
    try (HttpServer server = HttpServer.start(allRequests)) {      
      try (EventSource es = new EventSource.Builder(eventSink, server.getUri())
          .reconnectTime(reconnectTime)
          .logger(testLogger.getLogger())
          .build()) {
        es.start();
        
        assertEquals(LogItem.opened(), eventSink.awaitLogItem());
        
        assertEquals(LogItem.event("message", "first"),
            eventSink.awaitLogItem());

        eventSink.assertNoMoreLogItems(); // should not have closed first stream yet
        
        closeStreamSemaphore.release();
        
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
        Handlers.status(500),
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
        Handlers.malformedResponse(),
        Duration.ofMillis(10),
        eventSink -> {
          LogItem errorItem = eventSink.awaitLogItem();
          assertEquals(ProtocolException.class, errorItem.error.getClass());
        });
  }

  private void verifyReconnectAfterErrorOnFirstRequest(
      Handler errorProducer,
      Duration reconnectTime,
      Consumer<TestHandler> checkExpectedEvents
      ) throws Exception {
    String body = "data: good";

    TestHandler eventSink = new TestHandler();
    
    Handler streamHandler = streamThatStaysOpen(body);
    Handler allRequests = Handlers.sequential(errorProducer, streamHandler);
 
    try (HttpServer server = HttpServer.start(allRequests)) { 
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
        Handlers.status(500),
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

    try (HttpServer server = HttpServer.start(Handlers.status(500))) {            
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
        Thread.sleep(1000);

        // If a ConnectionErrorHandler returns SHUTDOWN, EventSource does not call onClosed() or onError()
        // on the regular event handler, since it assumes that the caller already knows what happened.
        // Therefore we don't expect to see any items in eventSink.
        eventSink.assertNoMoreLogItems();

        assertEquals(1, server.getRecorder().count());
        assertEquals(0, receivedError.size()); // error handler should have only been called once
        
        assertEquals(ReadyState.SHUTDOWN, es.getState());
      }
    }
  }
  
  @Test
  public void canForceEventSourceToRestart() throws Exception {
    String body1 = "data: first";
    String body2 = "data: second";

    TestHandler eventSink = new TestHandler();

    Handler streamHandler1 = streamThatStaysOpen(body1);
    Handler streamHandler2 = streamThatStaysOpen(body2);
    Handler allRequests = Handlers.sequential(streamHandler1, streamHandler2);
    
    try (HttpServer server = HttpServer.start(allRequests)) {            
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
