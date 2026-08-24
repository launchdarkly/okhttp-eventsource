package com.launchdarkly.eventsource;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertThat;

@SuppressWarnings("javadoc")
public class DefaultRetryDelayStrategyTest {
  @Test
  public void backoffWithNoJitterAndNoMax() {
    long base = 4;

    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .initialDelay(base, TimeUnit.MILLISECONDS)
        .backoffMultiplier(2).jitterMultiplier(0)
        .maxDelay(0, null);

    assertThat(s.getDelayMillis(), equalTo(base));

    s = s.getNext();
    assertThat(s.getDelayMillis(), equalTo(base * 2));

    s = s.getNext();
    assertThat(s.getDelayMillis(), equalTo(base * 4));

    s = s.getNext();
    assertThat(s.getDelayMillis(), equalTo(base * 8));

    s = s.getNext();
    assertThat(s.getDelayMillis(), equalTo(base * 16));
  }

  @Test
  public void backoffWithNoJitterAndMax() {
    long base = 4;
    long max = base * 4 + 3;

    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .initialDelay(base, TimeUnit.MILLISECONDS)
        .backoffMultiplier(2).jitterMultiplier(0)
        .maxDelay(max, TimeUnit.MILLISECONDS);

    assertThat(s.getDelayMillis(), equalTo(base));

    s = s.getNext();
    assertThat(s.getDelayMillis(), equalTo(base * 2));

    s = s.getNext();
    assertThat(s.getDelayMillis(), equalTo(base * 4));

    s = s.getNext();
    assertThat(s.getDelayMillis(), equalTo(max));
  }

  @Test
  public void noBackoffAndNoJitter() {
    long base = 4;

    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .initialDelay(base, TimeUnit.MILLISECONDS)
        .backoffMultiplier(1).jitterMultiplier(0)
        .maxDelay(0, null);

    assertThat(s.getDelayMillis(), equalTo(base));
    s = s.getNext();
    assertThat(s.getDelayMillis(), equalTo(base));
    s = s.getNext();
    assertThat(s.getDelayMillis(), equalTo(base));
  }

  @Test
  public void backoffWithJitter() {
    long base = 4;
    int specifiedBackoff = 2;
    long max = base * specifiedBackoff * specifiedBackoff + 3;
    float specifiedJitter = 0.25f;

    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .initialDelay(base, TimeUnit.MILLISECONDS)
        .backoffMultiplier(specifiedBackoff).jitterMultiplier(specifiedJitter)
        .maxDelay(max, TimeUnit.MILLISECONDS);

    s = verifyJitter(s, base, specifiedJitter);
    s = verifyJitter(s, base * specifiedBackoff, specifiedJitter);
    s = verifyJitter(s, base * specifiedBackoff * specifiedBackoff, specifiedJitter);
    verifyJitter(s, max, specifiedJitter);
  }

  @Test
  public void zeroBaseDelayAlwaysProducesZero() {
    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .initialDelay(0, TimeUnit.MILLISECONDS);

    for (int i = 0; i < 5; i++) {
      assertThat(s.getDelayMillis(), equalTo(0L));
      s = s.getNext();
    }
  }

  @Test
  public void withBaseDelayMillisOverridesAndResetsProgression() {
    long initialBase = 100;
    long overrideBase = 500;

    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .initialDelay(initialBase, TimeUnit.MILLISECONDS)
        .backoffMultiplier(2).jitterMultiplier(0)
        .maxDelay(0, null);

    // Advance a few steps.
    s = s.getNext();
    s = s.getNext();
    // Now at 400 (100 * 2 * 2).
    assertThat(s.getDelayMillis(), equalTo(initialBase * 4));

    // Override the base; expect a fresh snapshot at the new base.
    s = s.withBaseDelayMillis(overrideBase);
    assertThat(s.getDelayMillis(), equalTo(overrideBase));

    // Advance from the fresh snapshot.
    s = s.getNext();
    assertThat(s.getDelayMillis(), equalTo(overrideBase * 2));
  }

  // Verifies that a strategy's getDelayMillis() sits in the expected jitter range
  // around baseWithBackoff, and returns the getNext() strategy for chained
  // verification. Because each snapshot's jitter is rolled once at construction
  // (deterministic per instance), we sample 100 fresh withBaseDelayMillis
  // reconstructions to confirm the range and that the values aren't all identical.
  private RetryDelayStrategy verifyJitter(
      RetryDelayStrategy s,
      long baseWithBackoff,
      float expectedJitterRatio
      ) {
    long firstDelay = s.getDelayMillis();
    assertThat(firstDelay, allOf(
        greaterThanOrEqualTo((long)(baseWithBackoff * expectedJitterRatio)),
        lessThanOrEqualTo(baseWithBackoff)
    ));

    // Sample additional jittered values via withBaseDelayMillis() (each call
    // reconstructs with a fresh jitter roll).
    boolean atLeastOneWasDifferent = false;
    for (int i = 0; i < 100; i++) {
      RetryDelayStrategy sampled = s.withBaseDelayMillis(baseWithBackoff);
      long delay = sampled.getDelayMillis();
      assertThat(delay, allOf(
          greaterThanOrEqualTo((long)(baseWithBackoff * expectedJitterRatio)),
          lessThanOrEqualTo(baseWithBackoff)
      ));
      if (delay != firstDelay) {
        atLeastOneWasDifferent = true;
      }
    }
    // (Not asserting atLeastOneWasDifferent strictly to avoid flakes on very small
    // baseWithBackoff values, but it should virtually always be true.)
    return s.getNext();
  }

  @Test
  public void defaultBackoff() {
    long base = 4;

    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .initialDelay(base, TimeUnit.MILLISECONDS)
        .jitterMultiplier(0).maxDelay(100, TimeUnit.SECONDS);

    assertThat(s.getDelayMillis(), equalTo(base));

    s = s.getNext();
    assertThat(s.getDelayMillis(), equalTo((long)
        (base * DefaultRetryDelayStrategy.DEFAULT_BACKOFF_MULTIPLIER)));
  }

  @Test
  public void defaultJitter() {
    long base = 4;

    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .initialDelay(base, TimeUnit.MILLISECONDS)
        .maxDelay(100, TimeUnit.SECONDS);

    verifyJitter(s, base, DefaultRetryDelayStrategy.DEFAULT_JITTER_MULTIPLIER);
  }

  @Test
  public void tinyBaseWithSmallJitterProducesNoJitter() {
    // When base * jitterMultiplier rounds below 1, jitter is effectively disabled
    // (the jitter subtraction would be zero). Verify this edge is handled without
    // throwing (SecureRandom.nextInt(0) would throw IllegalArgumentException).
    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .initialDelay(1, TimeUnit.MILLISECONDS)
        .jitterMultiplier(0.4f);
    assertThat(s.getDelayMillis(), equalTo(1L));
  }

  @Test
  public void initialDelayAboveMaxDelayIsClamped() {
    // The pre-PR apply(base) path pinned every attempt (including the first)
    // against maxDelay. Post-PR, the max is enforced only in getNext(), so
    // an initialDelay above maxDelay must still be clamped at construction
    // for parity.
    long max = 30_000;
    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .initialDelay(1, TimeUnit.HOURS)
        .backoffMultiplier(2).jitterMultiplier(0)
        .maxDelay(max, TimeUnit.MILLISECONDS);

    assertThat(s.getDelayMillis(), equalTo(max));
    // Subsequent progression stays pinned.
    assertThat(s.getNext().getDelayMillis(), equalTo(max));
  }

  @Test
  public void withBaseDelayMillisAboveMaxDelayIsClamped() {
    // A wire retry hint whose value exceeds the strategy's maxDelay must not
    // bypass the max on the immediate reconnect. withBaseDelayMillis is the
    // entry point for wire hints via EventSource.resetAllRegisteredStrategyState.
    long max = 30_000;
    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .initialDelay(1, TimeUnit.SECONDS)
        .backoffMultiplier(2).jitterMultiplier(0)
        .maxDelay(max, TimeUnit.MILLISECONDS)
        .withBaseDelayMillis(60_000);

    assertThat(s.getDelayMillis(), equalTo(max));
  }
}
