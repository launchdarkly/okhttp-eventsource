package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.Stubs.LogItem;
import com.launchdarkly.eventsource.Stubs.TestHandler;
import com.launchdarkly.logging.LDLogLevel;

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@SuppressWarnings("javadoc")
public class AsyncEventHandlerTest {
  private final ExecutorService executor;
  private final TestHandler eventHandler;
  private final AsyncEventHandler asyncHandler;

  @Rule public TestScopedLoggerRule testLoggerRule = new TestScopedLoggerRule();
  
  public AsyncEventHandlerTest() {
    executor = Executors.newSingleThreadExecutor();
    eventHandler = new TestHandler();
    asyncHandler = new AsyncEventHandler(executor, eventHandler, testLoggerRule.getLogger(), null);
  }
  
  @After
  public void tearDown() {
    executor.shutdown();
  }
  
  private void verifyErrorLogged(Throwable t) {
    testLoggerRule.awaitMessageContaining(LDLogLevel.WARN, "Caught unexpected error from EventHandler: " + t);
    testLoggerRule.awaitMessageContaining(LDLogLevel.DEBUG, "Stack trace:");
  }
  
  @Test
  public void errorFromOnOpenIsCaughtAndLoggedAndRedispatched() {    
    RuntimeException err = new RuntimeException("sorry");
    eventHandler.fakeError = err;
    
    asyncHandler.onOpen();
    
    assertEquals(LogItem.opened(), eventHandler.awaitLogItem());
    assertEquals(LogItem.error(err), eventHandler.awaitLogItem());
    verifyErrorLogged(err);
  }

  @Test
  public void errorFromOnMessageIsCaughtAndLoggedAndRedispatched() {
    RuntimeException err = new RuntimeException("sorry");
    eventHandler.fakeError = err;
    
    MessageEvent event = new MessageEvent("x");
    asyncHandler.onMessage("message", event);
    
    assertEquals(LogItem.event("message", "x"), eventHandler.awaitLogItem());
    assertEquals(LogItem.error(err), eventHandler.awaitLogItem());
    verifyErrorLogged(err);
  }


  @Test
  public void errorFromOnCommentIsCaughtAndLoggedAndRedispatched() {
    RuntimeException err = new RuntimeException("sorry");
    eventHandler.fakeError = err;
    
    asyncHandler.onComment("x");
    
    assertEquals(LogItem.comment("x"), eventHandler.awaitLogItem());
    assertEquals(LogItem.error(err), eventHandler.awaitLogItem());
    verifyErrorLogged(err);
  }

  @Test
  public void errorFromOnClosedIsCaughtAndLoggedAndRedispatched() {    
    RuntimeException err = new RuntimeException("sorry");
    eventHandler.fakeError = err;
    
    asyncHandler.onClosed();
    
    assertEquals(LogItem.closed(), eventHandler.awaitLogItem());
    assertEquals(LogItem.error(err), eventHandler.awaitLogItem());
    verifyErrorLogged(err);
  }

  @Test
  public void errorFromOnErrorIsCaughtAndLogged() {
    RuntimeException err1 = new RuntimeException("sorry");
    RuntimeException err2 = new RuntimeException("sorrier");
    eventHandler.fakeError = err1;
    eventHandler.fakeErrorFromErrorHandler = err2;
    
    asyncHandler.onOpen();
        
    assertEquals(LogItem.opened(), eventHandler.awaitLogItem());
    assertEquals(LogItem.error(err1), eventHandler.awaitLogItem());
    
    testLoggerRule.awaitMessageContaining(LDLogLevel.WARN, "Caught unexpected error from EventHandler: " + err1);
    testLoggerRule.awaitMessageContaining(LDLogLevel.DEBUG, "Stack trace:");
    testLoggerRule.awaitMessageContaining(LDLogLevel.WARN, "Caught unexpected error from EventHandler.onError(): " + err2);
    testLoggerRule.awaitMessageContaining(LDLogLevel.DEBUG, "Stack trace:");
  }

  @Test
  public void backpressureOnQueueFull() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      final CountDownLatch latch1 = new CountDownLatch(1);
      EventHandler eventHandler = mock(EventHandler.class);
      doAnswer(invocation -> {
        latch1.await();
        return null;
      }).doNothing().when(eventHandler).onMessage(anyString(), any(MessageEvent.class));

      final CountDownLatch latch2 = new CountDownLatch(1);
      final CountDownLatch latch3 = new CountDownLatch(1);
      ExecutorService blockable = Executors.newSingleThreadExecutor();
      try {
        blockable.execute(() -> {
          AsyncEventHandler asyncHandler = new AsyncEventHandler(executor, eventHandler, testLoggerRule.getLogger(), new Semaphore(1));

          asyncHandler.onOpen();

          asyncHandler.onMessage("message", new MessageEvent("hello world"));
          latch2.countDown();
          asyncHandler.onMessage("message", new MessageEvent("goodbye horses"));
          latch3.countDown();
        });

        assertTrue("Expected latch2 to trip", latch2.await(5, TimeUnit.SECONDS));
        assertFalse("Expected latch3 not to trip", latch3.await(250, TimeUnit.MILLISECONDS));
        latch1.countDown();
        assertTrue("Expected latch3 to trip", latch3.await(5, TimeUnit.SECONDS));
      } finally {
        latch1.countDown();
        latch2.countDown();
        latch3.countDown();
        blockable.shutdown();
        assertTrue("Expected background thread to terminate", blockable.awaitTermination(5, TimeUnit.SECONDS));
      }
    } finally {
      executor.shutdown();
    }
  }
  
  @Test
  public void streamingMessageIsClosedAfterDispatch() {
    Reader r = new StringReader("hello");
    MessageEvent event1 = new MessageEvent("message", r, null, null);
    MessageEvent event2 = new MessageEvent("other");
    
    asyncHandler.onMessage("message", event1);
    asyncHandler.onMessage("message", event2);
    
    assertEquals(LogItem.event("message", "<streaming>"), eventHandler.awaitLogItem());
    assertEquals(LogItem.event("message", "other"), eventHandler.awaitLogItem());
    
    // Now that the second one has been received, we know for sure that the handler for the first one returned
    try {
      r.read();
      Assert.fail("expected exception, reader should have been closed");
    } catch (IOException e) {}
  }
}
