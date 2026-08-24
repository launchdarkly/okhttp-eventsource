package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.MockConnectStrategy.PipedStreamRequestHandler;
import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LogCapture;

import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static com.launchdarkly.eventsource.MockConnectStrategy.ORIGIN;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithDataAndThenEnd;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithStream;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;

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
        .logger(testLogger.getLogger());
  }
  
  // Consumes the "Waiting X milliseconds before reconnecting" log message emitted
  // by EventSource just before the sleep, and returns the value of X. Since the
  // log now emits the strategy's computed delay (no elapsed-time subtraction),
  // tests can assert exact equality against the strategy's expected delay.
  private long readReconnectDelayFromLog() {
    LogCapture.Message m = testLogger.getLogCapture().requireMessage(LDLogLevel.INFO, 1000);
    assertThat(m.getText(), allOf(
        startsWith("Waiting"), endsWith("milliseconds before reconnecting")));
    return Long.parseLong(m.getText().split(" ")[1]);
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
    RetryDelayStrategy retryDelayStrategy =
        new ArithmeticallyIncreasingRetryDelayStrategy(initialDelay, increment, 0);

    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(retryDelayStrategy)
        .logger(testLogger.getLogger())
        .build()) {
      es.start();

      for (int i = 0; i < attempts; i++) {
        assertThat(es.readAnyEvent(), equalTo(new MessageEvent(
            "message", "event" + i, null, ORIGIN)));

        assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));

        assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));

        assertThat(readReconnectDelayFromLog(), equalTo(initialDelay + (increment * i)));
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
    RetryDelayStrategy retryDelayStrategy = new FixedRetryDelayStrategy(initialDelay, increment);

    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(retryDelayStrategy)
        .logger(testLogger.getLogger())
        .build()) {
      es.start();
      
      for (int i = 0; i < attempts; i++) {
        assertThat(es.readAnyEvent(), equalTo(new MessageEvent(
            "message", "event" + i, null, ORIGIN)));

        assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));

        assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));

        assertThat(readReconnectDelayFromLog(), equalTo(initialDelay + increment));
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
    RetryDelayStrategy retryDelayStrategy =
        new ArithmeticallyIncreasingRetryDelayStrategy(initialDelay, increment, 0);

    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(retryDelayStrategy)
        .retryDelayResetThreshold(threshold, null)
        .logger(testLogger.getLogger())
        .build()) {
      es.start();
      
      stream1.close();

      // On first failure, the delay is the initial delay
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      assertThat(readReconnectDelayFromLog(), equalTo(initialDelay));

      stream2.close();

      // On second failure, the delay is incremented because it happened sooner than the threshold
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      assertThat(readReconnectDelayFromLog(), equalTo(initialDelay + increment));

      Thread.sleep(threshold + 10);
      stream3.close();

      // This time, the stream lasted longer than the threshold so we reset to the initial delay
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      assertThat(readReconnectDelayFromLog(), equalTo(initialDelay));

      stream4.close();

      // And now this time, the stream did not last long enough so the delay gets incremented
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      assertThat(readReconnectDelayFromLog(), equalTo(initialDelay + increment));
    }
  }
  
  private static class ArithmeticallyIncreasingRetryDelayStrategy extends RetryDelayStrategy {
    private final long baseDelayMillis;
    private final int increment;
    private final int counter;

    ArithmeticallyIncreasingRetryDelayStrategy(long baseDelayMillis, int increment, int counter) {
      this.baseDelayMillis = baseDelayMillis;
      this.increment = increment;
      this.counter = counter;
    }

    ArithmeticallyIncreasingRetryDelayStrategy(int increment) {
      this(0, increment, 0);
    }

    @Override
    public long getDelayMillis() {
      return baseDelayMillis + (counter * increment);
    }

    @Override
    public RetryDelayStrategy getNext() {
      return new ArithmeticallyIncreasingRetryDelayStrategy(baseDelayMillis, increment, counter + 1);
    }

    @Override
    public RetryDelayStrategy withBaseDelayMillis(long millis) {
      return new ArithmeticallyIncreasingRetryDelayStrategy(millis, increment, 0);
    }
  }

  private static class FixedRetryDelayStrategy extends RetryDelayStrategy {
    private final long baseDelayMillis;
    private final int increment;

    FixedRetryDelayStrategy(long baseDelayMillis, int increment) {
      this.baseDelayMillis = baseDelayMillis;
      this.increment = increment;
    }

    FixedRetryDelayStrategy(int increment) {
      this(0, increment);
    }

    @Override
    public long getDelayMillis() {
      return baseDelayMillis + increment;
    }

    @Override
    public RetryDelayStrategy getNext() {
      return this;
    }

    @Override
    public RetryDelayStrategy withBaseDelayMillis(long millis) {
      return new FixedRetryDelayStrategy(millis, increment);
    }
  }

  // Tests for activateRetryDelayStrategy: the SDK-side entry point for regime
  // switching per the LaunchDarkly RETRY spec. Strategies are registered at build
  // time via repeated calls to retryDelayStrategy(); the first call sets the
  // default (reset target) and subsequent calls register additional strategies
  // available for runtime activation.

  @Test
  public void activateRetryDelayStrategyNullIsNoOp() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(respondWithStream());
    try (EventSource es = baseBuilder(mock).build()) {
      es.start();
      RetryDelayStrategy before = es.currentRetryDelayStrategy;
      es.activateRetryDelayStrategy(null);
      // No throw, no state change.
      assertThat(es.currentRetryDelayStrategy, equalTo(before));
    }
  }

  @Test
  public void activateRetryDelayStrategyUnregisteredIsNoOp() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(respondWithStream());
    try (EventSource es = baseBuilder(mock).build()) {
      es.start();
      RetryDelayStrategy before = es.currentRetryDelayStrategy;
      es.activateRetryDelayStrategy(new FixedRetryDelayStrategy(100));
      // No throw, no state change.
      assertThat(es.currentRetryDelayStrategy, equalTo(before));
    }
  }

  @Test
  public void activateRetryDelayStrategySwapsTheActiveStrategy() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream1 = respondWithStream();
    PipedStreamRequestHandler stream2 = respondWithStream();
    PipedStreamRequestHandler stream3 = respondWithStream();
    mock.configureRequests(stream1, stream2, stream3);

    long initialDelay = 10;
    int normalIncrement = 3, extendedIncrement = 100;
    RetryDelayStrategy normal = new FixedRetryDelayStrategy(initialDelay, normalIncrement);
    RetryDelayStrategy extended = new FixedRetryDelayStrategy(initialDelay, extendedIncrement);
    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(normal)        // first call = default
        .retryDelayStrategy(extended)      // second call = additional
        .build()) {
      es.start();

      stream1.close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      // Default (normal) is active.
      assertThat(readReconnectDelayFromLog(), equalTo(initialDelay + normalIncrement));

      // Swap to the extended strategy.
      es.activateRetryDelayStrategy(extended);

      stream2.close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      assertThat(readReconnectDelayFromLog(), equalTo(initialDelay + extendedIncrement));
    }
  }

  @Test
  public void healthyOpResetRevertsToDefaultStrategy() throws Exception {
    // After healthy-op reset threshold elapses, active reverts to the default
    // (first-registered) strategy.
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream1 = respondWithStream();
    PipedStreamRequestHandler stream2 = respondWithStream();
    PipedStreamRequestHandler stream3 = respondWithStream();
    mock.configureRequests(stream1, stream2, stream3);

    long initialDelay = 10;
    long threshold = 50;
    int normalIncrement = 3, extendedIncrement = 100;
    RetryDelayStrategy normal = new FixedRetryDelayStrategy(initialDelay, normalIncrement);
    RetryDelayStrategy extended = new FixedRetryDelayStrategy(initialDelay, extendedIncrement);
    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(normal)
        .retryDelayStrategy(extended)
        .retryDelayResetThreshold(threshold, null)
        .build()) {
      es.start();

      // Activate extended, then close the stream quickly so no reset triggers.
      es.activateRetryDelayStrategy(extended);

      stream1.close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      // Extended is active; delay reflects extended's shape.
      assertThat(readReconnectDelayFromLog(), equalTo(initialDelay + extendedIncrement));

      // Let the next connection last past the reset threshold. Healthy-op reset
      // should revert to the default (normal) strategy.
      Thread.sleep(threshold + 10);
      stream2.close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      // Reverted to default -> uses normal's shape.
      assertThat(readReconnectDelayFromLog(), equalTo(initialDelay + normalIncrement));
    }
  }

  @Test
  public void perStrategyStateIsPreservedAcrossActivations() throws Exception {
    // Each registered strategy's backoff progression state persists across
    // activations. Deactivating and reactivating a strategy resumes from where
    // its counter last left off (not fresh).
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream1 = respondWithStream();
    PipedStreamRequestHandler stream2 = respondWithStream();
    PipedStreamRequestHandler stream3 = respondWithStream();
    PipedStreamRequestHandler stream4 = respondWithStream();
    mock.configureRequests(stream1, stream2, stream3, stream4);

    long initialDelay = 10;
    int normalIncrement = 3, extendedIncrement = 100;
    RetryDelayStrategy normal = new ArithmeticallyIncreasingRetryDelayStrategy(initialDelay, normalIncrement, 0);
    RetryDelayStrategy extended = new ArithmeticallyIncreasingRetryDelayStrategy(initialDelay, extendedIncrement, 0);
    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(normal)
        .retryDelayStrategy(extended)
        .build()) {
      es.start();

      // Fault 1 (default = normal). Counter advances on normal to 1.
      stream1.close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      assertThat(readReconnectDelayFromLog(), equalTo(initialDelay));

      // Activate extended. Its counter is still 0.
      es.activateRetryDelayStrategy(extended);

      stream2.close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      // Extended's first apply, counter=0.
      assertThat(readReconnectDelayFromLog(), equalTo(initialDelay));

      stream3.close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      // Extended's second apply, counter=1.
      assertThat(readReconnectDelayFromLog(), equalTo(initialDelay + extendedIncrement));

      // Re-activate normal. Its counter was 1 when we left it.
      es.activateRetryDelayStrategy(normal);

      stream4.close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      // Normal resumes at counter=1: delay = initialDelay + 1 * normalIncrement.
      assertThat(readReconnectDelayFromLog(), equalTo(initialDelay + normalIncrement));
    }
  }

  @Test
  public void wireRetryHintAppliesToAllRegisteredStrategies() throws Exception {
    // A server-directed retry hint received via the SSE "retry:" field is
    // applied to every registered strategy's snapshot, not just the currently-
    // active one. Verifies the "sticky-to-all" behavior called out in the PR
    // description (and matching Go's ApplyRetryTime).
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream = respondWithStream();
    mock.configureRequests(stream);
    stream.provideData("retry: 500\n\ndata: x\n\n");

    DefaultRetryDelayStrategy normal = RetryDelayStrategy.defaultStrategy()
        .initialDelay(1000, TimeUnit.MILLISECONDS)
        .jitterMultiplier(0);
    DefaultRetryDelayStrategy extended = RetryDelayStrategy.defaultStrategy()
        .initialDelay(60_000, TimeUnit.MILLISECONDS)
        .jitterMultiplier(0);

    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(normal)      // default (active)
        .retryDelayStrategy(extended)    // additional
        .build()) {
      es.start();
      assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "x", null, ORIGIN)));

      // Normal is the active strategy; its snapshot should reflect the wire hint.
      assertEquals(500L,
          ((DefaultRetryDelayStrategy) es.currentRetryStrategySnapshot()).baseDelayMillis);

      // Extended was not active when the hint arrived, but the hint stampeded
      // across every registered strategy's reset instance. Activate to peek.
      es.activateRetryDelayStrategy(extended);
      assertEquals(500L,
          ((DefaultRetryDelayStrategy) es.currentRetryStrategySnapshot()).baseDelayMillis);
    }
  }

  @Test
  public void wireRetryHintIsStickyAcrossHealthyOpReset() throws Exception {
    // A wire hint received on connection N stays sticky when a later healthy-op
    // reset fires: the reset re-instantiates each registered strategy against
    // the wire base, not the caller's originally-registered base. Verifies the
    // WHATWG-sticky claim in the PR description.
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream1 = respondWithStream();
    PipedStreamRequestHandler stream2 = respondWithStream();
    mock.configureRequests(stream1, stream2);
    stream1.provideData("retry: 500\n\ndata: x\n\n");

    long threshold = 50;
    // backoffMultiplier(1) keeps base flat across getNext() so the assertion
    // reads the pure post-reset base.
    DefaultRetryDelayStrategy normal = RetryDelayStrategy.defaultStrategy()
        .initialDelay(1000, TimeUnit.MILLISECONDS)
        .jitterMultiplier(0)
        .backoffMultiplier(1);

    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(normal)
        .retryDelayResetThreshold(threshold, null)
        .build()) {
      es.start();
      assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "x", null, ORIGIN)));

      // Let the connection live past the reset threshold, then fault it. The
      // healthy-op reset should fire in computeReconnectDelay and re-apply the
      // wire hint (500), not revert to the caller's original base (1000).
      Thread.sleep(threshold + 10);
      stream1.close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      assertThat(readReconnectDelayFromLog(), equalTo(500L));
    }
  }

  @Test
  public void healthyOpResetIgnoresConsumerProcessingDelay() throws Exception {
    // computeReconnectDelay runs at sleep-time (after the consumer has been
    // handed a FaultEvent and looped back to readAnyEvent). The healthy-op
    // threshold check must measure only the prior connection's duration
    // (disconnectedTime - connectedTime), NOT (now - connectedTime), or a
    // slow consumer can spuriously trip the reset for a connection that
    // was actually short.
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream1 = respondWithStream();
    PipedStreamRequestHandler stream2 = respondWithStream();
    PipedStreamRequestHandler stream3 = respondWithStream();
    mock.configureRequests(stream1, stream2, stream3);

    long threshold = 200;
    long initialDelay = 100;
    DefaultRetryDelayStrategy strat = RetryDelayStrategy.defaultStrategy()
        .initialDelay(initialDelay, TimeUnit.MILLISECONDS)
        .jitterMultiplier(0);   // backoffMultiplier default 2

    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(strat)
        .retryDelayResetThreshold(threshold, TimeUnit.MILLISECONDS)
        .build()) {
      es.start();

      // Fault 1: brief connection, well below threshold. Delay = initialDelay.
      stream1.close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      assertThat(readReconnectDelayFromLog(), equalTo(initialDelay));

      // Fault 2: same brief connection, but the consumer takes a long time
      // between reading the FaultEvent and readAnyEvent'ing again -- long
      // enough that (now - connectedTime) crosses the threshold even though
      // the connection itself did not.
      stream2.close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      Thread.sleep(threshold + 100);
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));

      // Correct behavior: no reset (actual connection duration << threshold),
      // so the strategy's counter advances and delay is initialDelay * 2.
      // Bug (pre-fix): reset fires because now - connectedTime >= threshold,
      // giving delay = initialDelay again.
      assertThat(readReconnectDelayFromLog(), equalTo(initialDelay * 2));
    }
  }
}
