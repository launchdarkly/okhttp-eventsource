package com.launchdarkly.eventsource;

import org.junit.Assert;
import org.junit.Test;

import static com.launchdarkly.eventsource.EventSource.DEFAULT_BACKOFF_RESET_THRESHOLD_MS;
import static com.launchdarkly.eventsource.EventSource.DEFAULT_MAX_RECONNECT_TIME_MS;
import static com.launchdarkly.eventsource.EventSource.DEFAULT_RECONNECT_TIME_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BackoffCalculatorTest {

  @Test
  public void respectsDefaultMaximumBackoffTime() {
    BackoffCalculator calculator = subject();
    for (int i = 0; i < 300; i++) {
      assertTrue(calculator.delayAfterConnectedFor(0) < calculator.getMaxReconnectTimeMs());
    }
  }

  @Test
  public void respectsCustomMaximumBackoffTime() {
    BackoffCalculator calculator = new BackoffCalculator(5000, 2000, 100);
    for (int i = 0; i < 300; i++) {
      assertTrue(calculator.delayAfterConnectedFor(0) < calculator.getMaxReconnectTimeMs());
    }
  }

  @Test
  public void resetsDelayToZeroAfterSuccess() {
    BackoffCalculator calculator = subject();
    assertTrue(calculator.delayAfterConnectedFor(0) > 0);
    assertEquals(0, calculator.delayAfterConnectedFor(calculator.getBackoffResetThresholdMs()));
  }

  @Test
  public void delayIncreasesOnSubsequentFailures() {
    BackoffCalculator calculator = subject();
    long priorDelay = 0;
    for (int i = 0; i < 5; i++) {
      long thisDelay = calculator.delayAfterConnectedFor(0);
      assertTrue(thisDelay > priorDelay);
      priorDelay = thisDelay;
    }
  }

  private BackoffCalculator subject() {
    return new BackoffCalculator(DEFAULT_MAX_RECONNECT_TIME_MS,
        DEFAULT_RECONNECT_TIME_MS, DEFAULT_BACKOFF_RESET_THRESHOLD_MS);
  }
}
