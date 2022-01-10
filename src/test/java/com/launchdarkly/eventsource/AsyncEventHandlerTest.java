package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.Stubs.LogItem;
import com.launchdarkly.eventsource.Stubs.TestHandler;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SuppressWarnings("javadoc")
public class AsyncEventHandlerTest {
  private final ExecutorService executor;
  private final TestHandler eventHandler;
  private final AsyncEventHandler asyncHandler;
  private final Logger logger;
  
  public AsyncEventHandlerTest() {
    executor = Executors.newSingleThreadExecutor();
    eventHandler = new TestHandler();
    logger = mock(Logger.class);
    asyncHandler = new AsyncEventHandler(executor, eventHandler, logger, null);
  }
  
  @After
  public void tearDown() {
    executor.shutdown();
  }
  
  private void verifyErrorLogged(Throwable t) {
    verify(logger).warn("Caught unexpected error from EventHandler: " + t);
    verify(logger).debug(eq("Stack trace: {}"), any(LazyStackTrace.class));
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
    verify(logger).warn("Caught unexpected error from EventHandler: " + err1);
    verify(logger).warn("Caught unexpected error from EventHandler.onError(): " + err2);
    verify(logger, times(2)).debug(eq("Stack trace: {}"), any(LazyStackTrace.class));
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
          AsyncEventHandler asyncHandler = new AsyncEventHandler(executor, eventHandler, logger, new Semaphore(1));

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
}
