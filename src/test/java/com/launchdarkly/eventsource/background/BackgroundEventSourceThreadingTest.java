package com.launchdarkly.eventsource.background;

import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.MockConnectStrategy;
import com.launchdarkly.eventsource.MockConnectStrategy.PipedStreamRequestHandler;
import com.launchdarkly.eventsource.StreamClosedByServerException;
import com.launchdarkly.eventsource.TestScopedLoggerRule;
import com.launchdarkly.eventsource.background.Stubs.LogItem;
import com.launchdarkly.eventsource.background.Stubs.TestHandler;

import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithDataAndThenStayOpen;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithStream;
import static com.launchdarkly.testhelpers.ConcurrentHelpers.assertNoMoreValues;
import static com.launchdarkly.testhelpers.ConcurrentHelpers.awaitValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public class BackgroundEventSourceThreadingTest {
  private final MockConnectStrategy mockConnect = new MockConnectStrategy();

  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();
  
  private EventSource.Builder baseEventSourceBuilder() {
    return new EventSource.Builder(mockConnect)
        .retryDelay(1, null)
        .logger(testLogger.getLogger());
  }

  @Test
  public void canUseCustomExecutors() throws Exception {
    BlockingQueue<Thread> calledStreamExecutorFromThread = new LinkedBlockingQueue<>();
    BlockingQueue<Thread> calledEventsExecutorFromThread = new LinkedBlockingQueue<>();
    TestHandler testHandler = new TestHandler();
    mockConnect.configureRequests(respondWithStream());
    
    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        testHandler,
        baseEventSourceBuilder()
        )
        .streamExecutor(makeCapturingExecutor(calledStreamExecutorFromThread))
        .eventsExecutor(makeCapturingExecutor(calledEventsExecutorFromThread))
        .build()) {
      bes.start();
      
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.opened()));
      
      assertThat(awaitValue(calledStreamExecutorFromThread, 1, TimeUnit.SECONDS),
          sameInstance(Thread.currentThread()));
      assertNoMoreValues(calledStreamExecutorFromThread, 100, null);
      
      assertThat(awaitValue(calledEventsExecutorFromThread, 1, TimeUnit.SECONDS),
          not(sameInstance(Thread.currentThread())));
      assertNoMoreValues(calledEventsExecutorFromThread, 100, null);
    }
  }

  @Test
  public void extraCallOfStartDoesNotCallExecutorAgain() throws Exception {
    BlockingQueue<Thread> calledStreamExecutorFromThread = new LinkedBlockingQueue<>();
    TestHandler testHandler = new TestHandler(testLogger.getLogger());
    mockConnect.configureRequests(respondWithStream());

    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        testHandler,
        baseEventSourceBuilder()
        )
        .streamExecutor(makeCapturingExecutor(calledStreamExecutorFromThread))
        .build()) {
      bes.start();

      assertThat(awaitValue(calledStreamExecutorFromThread, 1, TimeUnit.SECONDS),
          sameInstance(Thread.currentThread()));
      assertNoMoreValues(calledStreamExecutorFromThread, 100, null);

      bes.start();

      assertNoMoreValues(calledStreamExecutorFromThread, 100, null);
    }
  }
  
  @Test
  public void defaultThreadPriorityIsNotMaximum() throws Exception {
    BlockingQueue<Thread> capturedThreads = new LinkedBlockingQueue<>();
    mockConnect.configureRequests(respondWithStream());
    
    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        makeThreadCapturingHandler(capturedThreads),
        baseEventSourceBuilder()
        ).build()) {
      bes.start();
      
      Thread handlerThread = awaitValue(capturedThreads, 1, TimeUnit.SECONDS);
      
      assertThat(handlerThread.getPriority(), not(equalTo(Thread.MAX_PRIORITY)));
    }
  }
  
  @Test
  public void canSetSpecificThreadPriority() throws Exception {
    BlockingQueue<Thread> capturedThreads = new LinkedBlockingQueue<>();
    mockConnect.configureRequests(respondWithStream());
    
    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        makeThreadCapturingHandler(capturedThreads),
        baseEventSourceBuilder()
        )
        .threadPriority(Thread.MAX_PRIORITY).build()) {
      bes.start();
      
      Thread handlerThread = awaitValue(capturedThreads, 1, TimeUnit.SECONDS);
      
      assertThat(handlerThread.getPriority(), equalTo(Thread.MAX_PRIORITY));
    }
  }
  
  @Test
  public void threadsAreStoppedAfterExplicitShutdown() throws Exception {
    String name = "MyTestSource";
    TestHandler testHandler = new TestHandler(testLogger.getLogger());
    mockConnect.configureRequests(respondWithStream());

    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        testHandler,
        baseEventSourceBuilder()
        )
        .threadBaseName(name)
        .build()) {
      bes.start();

      assertNumberOfThreadsWithSubstring(name, 2, 2000);

      bes.close();

      assertNumberOfThreadsWithSubstring(name, 0, 2000);
    }
  }

  @Test
  public void threadsAreStoppedAfterShutdownIsForcedByConnectionErrorHandler() throws Exception {
    String name = "MyTestSource";
    TestHandler testHandler = new TestHandler(testLogger.getLogger());
    ConnectionErrorHandler errorHandler = e -> ConnectionErrorHandler.Action.SHUTDOWN;

    PipedStreamRequestHandler stream = respondWithStream();
    mockConnect.configureRequests(stream);

    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        testHandler,
        baseEventSourceBuilder()
        )
        .connectionErrorHandler(errorHandler)
        .threadBaseName(name)
        .build()) {
      bes.start();
      
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.opened()));

      assertNumberOfThreadsWithSubstring(name, 2, 2000);

      stream.close();

      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.error(new StreamClosedByServerException())));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.closed()));

      assertNumberOfThreadsWithSubstring(name, 0, 2000);
    }
  }
  
  @Test
  public void backpressureOnQueueFull() throws Exception {
    Semaphore canProceed = new Semaphore(0);
    
    BlockingQueue<String> callLog = new LinkedBlockingQueue<>();
    
    BackgroundEventHandler testHandler = new BaseBackgroundEventHandler() {
      @Override
      public void onMessage(String event, MessageEvent messageEvent) throws Exception {
        callLog.add("onMessage:" + messageEvent.getData());
        canProceed.acquire();
        callLog.add("onMessage exit");
      }
    };

    mockConnect.configureRequests(respondWithDataAndThenStayOpen(
        "data: data1\n\n",
        "data: data2\n\n",
        "data: data3\n\n"
        ));
    
    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        testHandler,
        baseEventSourceBuilder()
        )
        .maxEventTasksInFlight(1)
        .build()) {
      bes.start();

      assertThat(awaitValue(callLog, 100, null), equalTo("onMessage:data1"));
      assertNoMoreValues(callLog, 100, null);
      
      canProceed.release();
      assertThat(awaitValue(callLog, 100, null), equalTo("onMessage exit"));
      assertThat(awaitValue(callLog, 100, null), equalTo("onMessage:data2"));
      assertNoMoreValues(callLog, 100, null);
      
      canProceed.release();
      assertThat(awaitValue(callLog, 100, null), equalTo("onMessage exit"));
      assertThat(awaitValue(callLog, 100, null), equalTo("onMessage:data3"));
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
  
  private static Executor makeCapturingExecutor(BlockingQueue<Thread> capturedCallerThreadsQueue) {
    return runnable -> {
      capturedCallerThreadsQueue.add(Thread.currentThread());
      new Thread(runnable).start();
    };
  }
  
  private static BackgroundEventHandler makeThreadCapturingHandler(BlockingQueue<Thread> capturedThreadsQueue) {
    return new BaseBackgroundEventHandler() {
      public void onOpen() throws Exception {
        capturedThreadsQueue.add(Thread.currentThread());
      }
    };
  }
}
