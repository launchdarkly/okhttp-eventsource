package com.launchdarkly.eventsource;

import java.util.Random;

public class BackoffCalculator {

  private final Random jitter = new Random();

  private long maxReconnectTimeMs;
  private long reconnectTimeMs;
  private long backoffResetThresholdMs;
  private int retryCount;

  /**
   * A calculator for deriving jittered backoff durations, based upon the quantity of consecutive
   * short-lived connections preceding each calculation request.
   *
   * @param maxReconnectTimeMs The maximum backoff delay in milliseconds
   * @param reconnectTimeMs The base backoff delay in milliseconds. This value grows exponentially
   *                        with each short-lived connection until `maxReconnectTime` is reached.
   * @param backoffResetThresholdMs The duration a connection must have been alive for before it is
   *                                considered to be long-lived.
   */
  public BackoffCalculator(long maxReconnectTimeMs, long reconnectTimeMs, long backoffResetThresholdMs) {
    this.maxReconnectTimeMs = maxReconnectTimeMs;
    this.reconnectTimeMs = reconnectTimeMs;
    this.backoffResetThresholdMs = backoffResetThresholdMs;
  }

  public long delayAfterConnectedFor(long connectedDuration) {
    retryCount = connectedDuration >= backoffResetThresholdMs
        ? 0
        : retryCount + 1;
    return delayGivenRetryCount();
  }

  public long getBackoffResetThresholdMs() {
    return backoffResetThresholdMs;
  }

  public void setBackoffResetThresholdMs(long backoffResetThresholdMs) {
    this.backoffResetThresholdMs = backoffResetThresholdMs;
  }

  public long getMaxReconnectTimeMs() {
    return maxReconnectTimeMs;
  }

  public void setMaxReconnectTimeMs(long maxReconnectTimeMs) {
    this.maxReconnectTimeMs = maxReconnectTimeMs;
  }

  public long getReconnectTimeMs() {
    return reconnectTimeMs;
  }

  public void setReconnectTimeMs(long reconnectTimeMs) {
    this.reconnectTimeMs = reconnectTimeMs;
  }

  private long delayGivenRetryCount() {
    if (retryCount == 0) {
      return 0;
    }
    long jitterVal = Math.min(maxReconnectTimeMs, reconnectTimeMs * pow2(retryCount));
    return jitterVal / 2 + nextLong(jitter, jitterVal) / 2;
  }

  // Returns 2**k, or Integer.MAX_VALUE if 2**k would overflow
  private int pow2(int k) {
    return (k < Integer.SIZE - 1) ? (1 << k) : Integer.MAX_VALUE;
  }

  // Adapted from http://stackoverflow.com/questions/2546078/java-random-long-number-in-0-x-n-range
  // Since ThreadLocalRandom.current().nextLong(n) requires Android 5
  private long nextLong(Random rand, long bound) {
    if (bound <= 0) {
      throw new IllegalArgumentException("bound must be positive");
    }

    long r = rand.nextLong() & Long.MAX_VALUE;
    long m = bound - 1L;
    if ((bound & m) == 0) { // i.e., bound is a power of 2
      r = (bound * r) >> (Long.SIZE - 1);
    } else {
      for (long u = r; u - (r = u % bound) + m < 0L; u = rand.nextLong() & Long.MAX_VALUE) ;
    }
    return r;
  }
}
