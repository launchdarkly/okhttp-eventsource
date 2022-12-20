package com.launchdarkly.eventsource;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static com.launchdarkly.eventsource.MockConnectStrategy.ORIGIN;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithDataAndThenEnd;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithDataAndThenStayOpen;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithStream;
import static com.launchdarkly.eventsource.TestUtils.interruptOnAnotherThread;
import static com.launchdarkly.eventsource.TestUtils.interruptOnAnotherThreadAfterDelay;
import static com.launchdarkly.eventsource.TestUtils.interruptThisThreadFromAnotherThreadAfterDelay;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * End-to-end tests with real HTTP, specifically for the client's reconnect behavior.
 */
@SuppressWarnings("javadoc")
public class EventSourceReconnectTest {
  private static final long BRIEF_DELAY = 10;
  
  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();

  private EventSource.Builder baseBuilder(MockConnectStrategy mock) {
    return new EventSource.Builder(mock)
        .errorStrategy(ErrorStrategy.alwaysContinue())
        .retryDelay(BRIEF_DELAY, null)
        .logger(testLogger.getLogger());
  }
  
  @Test
  public void eventSourceReconnectsAfterStreamClosedByServer() throws Exception {
    String message1 = "data: first\n\n", message2 = "data: second\n\n";
    
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(
        respondWithDataAndThenEnd(message1),
        respondWithDataAndThenStayOpen(message2));

    try (EventSource es = baseBuilder(mock).build()) {
      assertThat(es.getState(), equalTo(ReadyState.RAW));

      es.start();
      
      assertThat(es.getState(), equalTo(ReadyState.OPEN));

      assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "first", null, ORIGIN)));
      
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));

      assertThat(es.getState(), equalTo(ReadyState.CLOSED));
      
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      
      assertThat(es.getState(), equalTo(ReadyState.OPEN));
      
      assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "second", null, ORIGIN)));
    }
  }

  @Test
  public void eventSourceReconnectsAfterExternallyInterrupted() throws Exception {
    String message1 = "data: first\n\n", message2 = "data: second\n\n";
    
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(respondWithDataAndThenStayOpen(message1),
        respondWithDataAndThenStayOpen(message2));

    try (EventSource es = baseBuilder(mock).build()) {
      assertThat(es.getState(), equalTo(ReadyState.RAW));
      
      es.start();

      assertThat(es.getState(), equalTo(ReadyState.OPEN));
      
      assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "first", null, ORIGIN)));
      
      interruptOnAnotherThread(es);
      
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByCallerException())));

      assertThat(es.getState(), equalTo(ReadyState.CLOSED));
      
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));

      assertThat(es.getState(), equalTo(ReadyState.OPEN));
      
      assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "second", null, ORIGIN)));
    }
  }

  @Test
  public void retryDelayIsTerminatedEarlyIfEventSourceInterruptIsCalled() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(
        respondWithDataAndThenEnd("data: first\n\n"),
        respondWithStream());

    AtomicInteger counter = new AtomicInteger(0);
    long longDelay = 5000, tinyDelay = 1;
    RetryDelayStrategy longDelayForFirstRetryOnly = new RetryDelayStrategy() {
      @Override
      public Result apply(long baseDelayMillis) {
        return new Result(
            counter.getAndIncrement() == 0 ? longDelay : tinyDelay,
            null);
      }
    };
    
    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(longDelayForFirstRetryOnly)
        .build()) {
      assertThat(es.getState(), equalTo(ReadyState.RAW));
      
      es.start();
      
      assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "first", null, ORIGIN)));

      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));

      assertThat(es.getNextRetryDelayMillis(), equalTo(longDelay));

      long timeBeforeRetrying = System.currentTimeMillis();
      interruptOnAnotherThreadAfterDelay(es, 100);
      es.start();

      long actualDuration = System.currentTimeMillis() - timeBeforeRetrying;
      assertThat(actualDuration, Matchers.lessThan(longDelay));
    }
  }

  @Test
  public void retryDelayIsTerminatedEarlyIfThreadInterruptIsCalled() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(
        respondWithDataAndThenEnd("data: first\n\n"),
        respondWithStream());

    AtomicInteger counter = new AtomicInteger(0);
    long longDelay = 5000, tinyDelay = 1;
    RetryDelayStrategy longDelayForFirstRetryOnly = new RetryDelayStrategy() {
      @Override
      public Result apply(long baseDelayMillis) {
        return new Result(
            counter.getAndIncrement() == 0 ? longDelay : tinyDelay,
            null);
      }
    };
    
    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(longDelayForFirstRetryOnly)
        .build()) {
      assertThat(es.getState(), equalTo(ReadyState.RAW));
      
      es.start();
      
      assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "first", null, ORIGIN)));

      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));

      assertThat(es.getNextRetryDelayMillis(), equalTo(longDelay));

      long timeBeforeRetrying = System.currentTimeMillis();
      interruptThisThreadFromAnotherThreadAfterDelay(100);
      es.start();

      long actualDuration = System.currentTimeMillis() - timeBeforeRetrying;
      assertThat(actualDuration, Matchers.lessThan(longDelay));
    }
  }
}
