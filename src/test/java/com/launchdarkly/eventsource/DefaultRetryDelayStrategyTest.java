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
        .backoffMultiplier(2).jitterMultiplier(0)
        .maxDelay(0, null);

    RetryDelayStrategy.Result r1 = s.apply(base);
    assertThat(r1.getDelayMillis(), equalTo(base));
    
    RetryDelayStrategy.Result r2 = r1.getNext().apply(base);
    assertThat(r2.getDelayMillis(), equalTo(base * 2));
    
    RetryDelayStrategy.Result r3 = r2.getNext().apply(base);
    assertThat(r3.getDelayMillis(), equalTo(base * 4));
    
    RetryDelayStrategy.Result r4 = r3.getNext().apply(base);
    assertThat(r4.getDelayMillis(), equalTo(base * 8));
    
    RetryDelayStrategy.Result r5 = r4.getNext().apply(base);
    assertThat(r5.getDelayMillis(), equalTo(base * 16));
  }

  @Test
  public void backoffWithNoJitterAndMax() {
    long base = 4;
    long max = base * 4 + 3;

    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .backoffMultiplier(2).jitterMultiplier(0)
        .maxDelay(max, TimeUnit.MILLISECONDS);

    RetryDelayStrategy.Result r1 = s.apply(base);
    assertThat(r1.getDelayMillis(), equalTo(base));
    
    RetryDelayStrategy.Result r2 = r1.getNext().apply(base);
    assertThat(r2.getDelayMillis(), equalTo(base * 2));
    
    RetryDelayStrategy.Result r3 = r2.getNext().apply(base);
    assertThat(r3.getDelayMillis(), equalTo(base * 4));
    
    RetryDelayStrategy.Result r4 = r3.getNext().apply(base);
    assertThat(r4.getDelayMillis(), equalTo(max));
  }
  
  @Test
  public void noBackoffAndNoJitter() {
    long base = 4;

    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .backoffMultiplier(1).jitterMultiplier(0)
        .maxDelay(0, null);

    RetryDelayStrategy.Result r1 = s.apply(base);
    assertThat(r1.getDelayMillis(), equalTo(base));
    
    RetryDelayStrategy.Result r2 = r1.getNext().apply(base);
    assertThat(r2.getDelayMillis(), equalTo(base));
    
    RetryDelayStrategy.Result r3 = r2.getNext().apply(base);
    assertThat(r3.getDelayMillis(), equalTo(base));
  }

  @Test
  public void backoffWithJitter() {
    long base = 4;
    int specifiedBackoff = 2;
    long max = base * specifiedBackoff * specifiedBackoff + 3;
    float specifiedJitter = 0.25f;

    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .backoffMultiplier(specifiedBackoff).jitterMultiplier(specifiedJitter)
        .maxDelay(max, TimeUnit.MILLISECONDS);
    
    RetryDelayStrategy.Result r1 =
        verifyJitter(s, base, base, specifiedJitter);
    
    RetryDelayStrategy.Result r2 =
        verifyJitter(r1.getNext(), base, base * specifiedBackoff, specifiedJitter);

    RetryDelayStrategy.Result r3 =
        verifyJitter(r2.getNext(), base, base * specifiedBackoff * specifiedBackoff, specifiedJitter);

    verifyJitter(r3.getNext(), base, max, specifiedJitter);
  }

  @Test
  public void zeroBaseDelayAlwaysProducesZero() {
    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy();

    for (int i = 0; i < 5; i++) {
      RetryDelayStrategy.Result r = s.apply(0);
      assertThat(r.getDelayMillis(), equalTo(0L));
      s = r.getNext();
    }
  }
  
  private RetryDelayStrategy.Result verifyJitter(
      RetryDelayStrategy s,
      long base,
      long baseWithBackoff,
      float expectedJitterRatio
      ) {
    // We can't 100% prove that it's using the expected jitter ratio, since the result
    // is pseudo-random, but we can at least prove that repeated computations don't
    // fall outside the expected range and aren't all equal.
    RetryDelayStrategy.Result lastResult = null;
    boolean atLeastOneWasDifferent = false;
    for (int i = 0; i < 100; i++) {
      RetryDelayStrategy.Result result = s.apply(base);
      assertThat(result.getDelayMillis(), allOf(
          greaterThanOrEqualTo((long)(baseWithBackoff * expectedJitterRatio)),
          lessThanOrEqualTo(baseWithBackoff)
          ));
      if (lastResult != null && !atLeastOneWasDifferent) {
        atLeastOneWasDifferent = result.getDelayMillis() != lastResult.getDelayMillis();
      }
      lastResult = result;
    }
    return lastResult;
  }
  
  @Test
  public void defaultBackoff() {
    long base = 4;

    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .jitterMultiplier(0).maxDelay(100, TimeUnit.SECONDS);
    
    RetryDelayStrategy.Result r1 = s.apply(base);
    assertThat(r1.getDelayMillis(), equalTo(base));
    
    RetryDelayStrategy.Result r2 = r1.getNext().apply(base);
    assertThat(r2.getDelayMillis(), equalTo((long)
        (base * DefaultRetryDelayStrategy.DEFAULT_BACKOFF_MULTIPLIER)));
  }

  @Test
  public void defaultJitter() {
    long base = 4;

    RetryDelayStrategy s = RetryDelayStrategy.defaultStrategy()
        .maxDelay(100, TimeUnit.SECONDS);
    
    verifyJitter(s, base, base, DefaultRetryDelayStrategy.DEFAULT_JITTER_MULTIPLIER);
  }
}
