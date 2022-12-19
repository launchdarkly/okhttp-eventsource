package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.MockConnectStrategy.PipedStreamRequestHandler;
import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LogCapture;

import org.junit.Rule;
import org.junit.Test;

import static com.launchdarkly.eventsource.MockConnectStrategy.ORIGIN;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithDataAndThenEnd;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithStream;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * These tests verify that EventSource interacts with the configured RetryDelayStrategy
 * in the expected way.
 *
 * Other details of how EventSource handles connection retries are covered by 
 * EventSourceReconnectTest. 
 */
@SuppressWarnings("javadoc")
public class EventSourceRetryDelayStrategyUsageTest {
  private static final long BRIEF_DELAY = 10;
  
  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();

  private EventSource.Builder baseBuilder(MockConnectStrategy mock) {
    return new EventSource.Builder(mock)
        .errorStrategy(ErrorStrategy.alwaysContinue())
        .retryDelay(BRIEF_DELAY, null)
        .logger(testLogger.getLogger());
  }
  
  private void expectReconnectingLogMessage() {
    LogCapture.Message m = testLogger.getLogCapture().requireMessage(LDLogLevel.INFO, 1000);
    assertThat(m.getText(), allOf(
        startsWith("Waiting"), endsWith(("milliseconds before reconnecting"))));
  }
  
  @Test
  public void nextRetryDelayStrategyIsAppliedEachTime() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();

    long initialDelay = 10;
    int attempts = 5;
    for (int i = 0; i < attempts; i++) {
      mock.configureRequests(respondWithDataAndThenEnd("data: event" + i + "\n\n"));
    }
    mock.configureRequests(respondWithStream()); // leave stream open after last retry

    int increment = 3;
    RetryDelayStrategy retryDelayStrategy = new ArithmeticallyIncreasingRetryDelayStrategy(increment, 0);

    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(retryDelayStrategy)
        .retryDelay(initialDelay, null)
        .logger(testLogger.getLogger())
        .build()) {
      es.start();
      
      for (int i = 0; i < attempts; i++) {
        assertThat(es.readAnyEvent(), equalTo(new MessageEvent(
            "message", "event" + i, null, ORIGIN)));

        assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));

        assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));

        expectReconnectingLogMessage();

        assertThat(es.getNextRetryDelayMillis(), equalTo(initialDelay + (increment * i)));
      }
    }
  }

  @Test
  public void sameRetryDelayStrategyIsReusedIfItReturnsNoNextOne() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();

    long initialDelay = 10;
    int attempts = 5;
    for (int i = 0; i < attempts; i++) {
      mock.configureRequests(respondWithDataAndThenEnd("data: event" + i + "\n\n"));
    }
    mock.configureRequests(respondWithStream()); // leave stream open after last retry

    int increment = 3;
    RetryDelayStrategy retryDelayStrategy = new FixedRetryDelayStrategy(increment);

    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(retryDelayStrategy)
        .retryDelay(initialDelay, null)
        .logger(testLogger.getLogger())
        .build()) {
      es.start();
      
      for (int i = 0; i < attempts; i++) {
        assertThat(es.readAnyEvent(), equalTo(new MessageEvent(
            "message", "event" + i, null, ORIGIN)));

        assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));

        assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));

        expectReconnectingLogMessage();

        assertThat(es.getNextRetryDelayMillis(), equalTo(initialDelay + increment));
      }
    }
  }
  
  @Test
  public void retryDelayStrategyIsResetAfterThreshold() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream1 = respondWithStream();
    PipedStreamRequestHandler stream2 = respondWithStream();
    PipedStreamRequestHandler stream3 = respondWithStream();
    PipedStreamRequestHandler stream4 = respondWithStream();
    mock.configureRequests(stream1, stream2, stream3, stream4);
    
    long initialDelay = 10;
    long threshold = 50;
    int increment = 3;
    RetryDelayStrategy retryDelayStrategy = new ArithmeticallyIncreasingRetryDelayStrategy(increment, 0);

    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(retryDelayStrategy)
        .retryDelay(initialDelay, null)
        .retryDelayResetThreshold(threshold, null)
        .logger(testLogger.getLogger())
        .build()) {
      es.start();
      
      stream1.close();
      
      // On first failure, the delay is the initial delay
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.getNextRetryDelayMillis(), equalTo(initialDelay));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));

      stream2.close();

      // On second failure, the delay is incremented because it happened sooner than the threshold
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.getNextRetryDelayMillis(), equalTo(initialDelay + increment));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));

      Thread.sleep(threshold + 10);
      stream3.close();

      // This time, the stream lasted longer than the threshold so we reset to the initial delay
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.getNextRetryDelayMillis(), equalTo(initialDelay));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      
      stream4.close();
      
      // And now this time, the stream did not last long enough so the delay gets incremented
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.getNextRetryDelayMillis(), equalTo(initialDelay + increment));
    }
  }
  
  private static class ArithmeticallyIncreasingRetryDelayStrategy extends RetryDelayStrategy {
    private final int increment;
    private final int counter;
    
    ArithmeticallyIncreasingRetryDelayStrategy(int increment, int counter) {
      this.increment = increment;
      this.counter = counter;
    }
    
    @Override
    public Result apply(long baseDelayMillis) {
      return new Result(
          baseDelayMillis + (counter * increment),
          new ArithmeticallyIncreasingRetryDelayStrategy(increment, counter + 1)
          );
    }
  }
  
  private static class FixedRetryDelayStrategy extends RetryDelayStrategy {
    private final int increment;
    
    FixedRetryDelayStrategy(int increment) {
      this.increment = increment;
    }
    
    @Override
    public Result apply(long baseDelayMillis) {
      return new Result(baseDelayMillis + increment, null);
    }
  }  
}
