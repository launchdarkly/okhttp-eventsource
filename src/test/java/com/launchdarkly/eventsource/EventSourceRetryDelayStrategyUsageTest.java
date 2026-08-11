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
  
  // Tests for setInitialRetryDelayMillis and setMaxRetryDelayMillis. These are the
  // SDK-side entry points for RETRY-spec regime switching (see LaunchDarkly's
  // server-SDK implementation guide, SDK-2775).

  @Test
  public void setInitialRetryDelayMillisUpdatesGetBaseRetryDelayMillis() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(respondWithStream()); // stays connected

    try (EventSource es = baseBuilder(mock).retryDelay(1000, null).build()) {
      es.start();
      assertThat(es.getBaseRetryDelayMillis(), equalTo(1000L));

      es.setInitialRetryDelayMillis(5000L);
      assertThat(es.getBaseRetryDelayMillis(), equalTo(5000L));

      es.setInitialRetryDelayMillis(300000L);
      assertThat(es.getBaseRetryDelayMillis(), equalTo(300000L));
    }
  }

  @Test
  public void setInitialRetryDelayMillisResetsExponentCounter() throws Exception {
    // After some retries have advanced the exponent counter, calling
    // setInitialRetryDelayMillis(newBase) must reset the counter so the next retry
    // uses the new base directly rather than newBase * multiplier^currentCounter.
    // This is the "reset n when delays change" invariant.
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler[] streams = new PipedStreamRequestHandler[4];
    for (int i = 0; i < streams.length; i++) {
      streams[i] = respondWithStream();
    }
    mock.configureRequests(streams);

    long normalBase = 100;
    long extendedBase = 5000;
    // Zero jitter so retry delays are deterministic.
    RetryDelayStrategy strategy = RetryDelayStrategy.defaultStrategy()
        .jitterMultiplier(0)
        .backoffMultiplier(2)
        .maxDelay(1_000_000, null); // effectively no cap for this test

    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(strategy)
        .retryDelay(normalBase, null)
        .build()) {
      es.start();

      // Trigger fault #1: exponent counter starts at 0, delay = normalBase * 2^0 = 100.
      streams[0].close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      assertThat(es.getNextRetryDelayMillis(), equalTo(normalBase));

      // Trigger fault #2: counter advances, delay = normalBase * 2^1 = 200.
      streams[1].close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      assertThat(es.getNextRetryDelayMillis(), equalTo(normalBase * 2));

      // SDK-side regime switch: set the new base and expect the counter to reset.
      es.setInitialRetryDelayMillis(extendedBase);

      // Trigger fault #3: delay should be extendedBase * 2^0 = 5000, NOT
      // extendedBase * 2^2 = 20000 (which would happen if the counter wasn't reset).
      streams[2].close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      assertThat(es.getNextRetryDelayMillis(), equalTo(extendedBase));
    }
  }

  @Test
  public void setMaxRetryDelayMillisClampsAndResetsExponentCounter() throws Exception {
    // After the exponent counter has advanced, calling setMaxRetryDelayMillis with a
    // new max should both (a) reset the counter to 0 so the next retry uses the base
    // delay directly, and (b) apply the new max as the ceiling for subsequent
    // exponential progression.
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler[] streams = new PipedStreamRequestHandler[5];
    for (int i = 0; i < streams.length; i++) {
      streams[i] = respondWithStream();
    }
    mock.configureRequests(streams);

    long base = 1000;
    RetryDelayStrategy strategy = RetryDelayStrategy.defaultStrategy()
        .jitterMultiplier(0)
        .backoffMultiplier(2)
        .maxDelay(30000, null); // normal-regime cap

    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(strategy)
        .retryDelay(base, null)
        .build()) {
      es.start();

      // Advance counter: 1000, 2000, 4000.
      long[] initialProgression = new long[] { base, base * 2, base * 4 };
      for (int i = 0; i < initialProgression.length; i++) {
        streams[i].close();
        assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
        assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
        assertThat(es.getNextRetryDelayMillis(), equalTo(initialProgression[i]));
      }

      // Change max to something large (extended-regime cap: 1hr). Counter resets.
      es.setMaxRetryDelayMillis(3_600_000L);

      // Next fault: with counter reset to 0, delay = base * 2^0 = base = 1000
      // (NOT base * 2^3 = 8000 which would happen if counter wasn't reset).
      streams[3].close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      assertThat(es.getNextRetryDelayMillis(), equalTo(base));

      // Following fault: counter=1, delay = base * 2 = 2000. Under the new max
      // (1hr), no clamping occurs.
      streams[4].close();
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
      assertThat(es.getNextRetryDelayMillis(), equalTo(base * 2));
    }
  }

  @Test
  public void setInitialAndSetMaxComposeForExtendedRegimeSequence() throws Exception {
    // Simulates a full SDK-side transition into an extended regime: caller invokes
    // both setters, then observes the RETRY-spec extended-regime doubling shape
    // (5min, 10min, 20min, 40min, then clamp to 60min) — realized here at ms-scale
    // (10, 20, 40, 80, 120 clamp) so the test doesn't have to actually wall-clock
    // wait through 5-minute retry sleeps. Same doubling+clamp shape.
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler[] streams = new PipedStreamRequestHandler[7];
    for (int i = 0; i < streams.length; i++) {
      streams[i] = respondWithStream();
    }
    mock.configureRequests(streams);

    // Normal-regime initial (1s) and cap (30s), just so the builder is happy.
    // Deterministic jitter=0.
    RetryDelayStrategy strategy = RetryDelayStrategy.defaultStrategy()
        .jitterMultiplier(0)
        .backoffMultiplier(2)
        .maxDelay(30_000, null);

    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(strategy)
        .retryDelay(1000, null)
        .build()) {
      es.start();

      // Regime switch to extended, scaled to ms so the test wall-clock stays under 1s.
      long extendedInitial = 10;
      long extendedMax = 120;
      es.setInitialRetryDelayMillis(extendedInitial);
      es.setMaxRetryDelayMillis(extendedMax);

      long[] expected = {
          extendedInitial,       // 10 ms  (analog of 5 min)
          extendedInitial * 2,   // 20 ms  (analog of 10 min)
          extendedInitial * 4,   // 40 ms  (analog of 20 min)
          extendedInitial * 8,   // 80 ms  (analog of 40 min)
          extendedMax,           // clamp  (analog of 60 min)
          extendedMax,           // still clamped
          extendedMax,           // still clamped
      };
      for (int i = 0; i < expected.length; i++) {
        streams[i].close();
        assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
        assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
        assertThat(es.getNextRetryDelayMillis(), equalTo(expected[i]));
      }
    }
  }

  @Test
  public void wireRetryHintStillTakesEffectAfterSdkSideSetters() throws Exception {
    // The server-directed retry: hint in the SSE wire remains authoritative for the
    // base delay. This test pins that behavior after the SDK-side setters have been
    // used to transition into an extended regime — a subsequent wire hint should
    // override the SDK's chosen initial delay.
    MockConnectStrategy mock = new MockConnectStrategy();
    // First: a stream that emits a retry: hint of 750ms, then closes.
    mock.configureRequests(respondWithDataAndThenEnd("retry: 750\n\n"));
    mock.configureRequests(respondWithStream());

    RetryDelayStrategy strategy = RetryDelayStrategy.defaultStrategy()
        .jitterMultiplier(0)
        .backoffMultiplier(2)
        .maxDelay(3_600_000, null);

    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(strategy)
        .retryDelay(1000, null)
        .build()) {
      es.start();

      // SDK transitions to extended: initial=5min, max=1hr.
      es.setInitialRetryDelayMillis(300_000L);
      es.setMaxRetryDelayMillis(3_600_000L);
      assertThat(es.getBaseRetryDelayMillis(), equalTo(300_000L));

      // Read from the stream — it will emit a retry: 750 line, which updates
      // baseRetryDelayMillis and resets the strategy. The retry: line is consumed
      // internally by EventSource (not surfaced as an event); the FaultEvent from
      // the stream ending follows.
      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));

      // Base delay is now the wire-hinted 750ms, not the SDK-set 5min.
      assertThat(es.getBaseRetryDelayMillis(), equalTo(750L));
      // And the computed retry delay reflects the wire hint (counter reset by
      // resetRetryDelayStrategy which the wire-hint path calls).
      assertThat(es.getNextRetryDelayMillis(), equalTo(750L));
    }
  }

  @Test
  public void settersOnCustomRetryDelayStrategyDoNotThrow() throws Exception {
    // Non-DefaultRetryDelayStrategy: setInitialRetryDelayMillis still updates the
    // base delay field (observable via getBaseRetryDelayMillis), and
    // setMaxRetryDelayMillis is a silent no-op.
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(respondWithStream());

    RetryDelayStrategy custom = new FixedRetryDelayStrategy(0);

    try (EventSource es = baseBuilder(mock)
        .retryDelayStrategy(custom)
        .retryDelay(1000, null)
        .build()) {
      es.start();

      es.setInitialRetryDelayMillis(5000L);
      assertThat(es.getBaseRetryDelayMillis(), equalTo(5000L));

      // No exception.
      es.setMaxRetryDelayMillis(60000L);
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
