package com.launchdarkly.eventsource;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.eventsource.Helpers.millisFromTimeUnit;

/**
 * Default implementation of the retry delay strategy, providing exponential backoff
 * and jitter.
 * <p>
 * The algorithm is as follows:
 * <ul>
 * <li> Start with the configured base delay as set by
 *   {@link EventSource.Builder#retryDelay(long, java.util.concurrent.TimeUnit)}. </li>
 * <li> On each subsequent attempt, multiply the base delay by the backoff multiplier
 *   (default: {@link #DEFAULT_BACKOFF_MULTIPLIER}) giving the current base delay. </li>
 * <li> If the maximum delay (default: {@link #DEFAULT_MAX_DELAY_MILLIS}) is
 *   non-zero, the base delay is pinned to be no greater than that value. </li>
 * <li> If the jitter multipler (default: {@link #DEFAULT_JITTER_MULTIPLIER}) is
 *   non-zero, the actual delay for each attempt is equal to the current base delay
 *   minus a pseudo-random number equal to that ratio times itself. For instance, a
 *   jitter multiplier of 0.25 would mean that a base delay of 1000 is changed to
 *   a value in the range [750, 1000]. </li>
 * </ul>
 * <p>
 * This class is immutable. {@link RetryDelayStrategy#defaultStrategy()} returns the
 * default instance. To change any parameters, call methods which return a modified
 * instance:
 * <pre><code>
 *     RetryDelayStrategy strategy = RetryDelayStrategy.defaultStrategy()
 *       .jitterMultiplier(0.25)
 *       .maxDelay(20, TimeUnit.SECONDS);
 * </code></pre>
 *
 * @since 4.0.0
 */
public class DefaultRetryDelayStrategy extends RetryDelayStrategy {
  /**
   * The default value for {@link #maxDelay(long, TimeUnit)}: 30 seconds.
   */
  public static final long DEFAULT_MAX_DELAY_MILLIS = 30000;
  
  /**
   * The default value for {@link #backoffMultiplier(float)}: 2.
   */
  public static final float DEFAULT_BACKOFF_MULTIPLIER = 2;
  
  /**
   * The default value for {@link #jitterMultiplier(float)}: 0.5.
   */
  public static final float DEFAULT_JITTER_MULTIPLIER = 0.5f;

  static DefaultRetryDelayStrategy INSTANCE = new DefaultRetryDelayStrategy(0,
      DEFAULT_MAX_DELAY_MILLIS,
      DEFAULT_BACKOFF_MULTIPLIER,
      DEFAULT_JITTER_MULTIPLIER);
  
  private final long lastBaseDelayMillis;
  private final long maxDelayMillis;
  private final float backoffMultiplier;
  private final float jitterMultiplier;
  private static final SecureRandom random = new SecureRandom();
  
  /**
   * Returns a modified strategy with a specific maximum delay.
   *
   * @param maxDelay the maximum delay, in whatever time unit is specified by {@code timeUnit}
   * @param timeUnit the time unit, or {@code TimeUnit.MILLISECONDS} if null
   * @return a new instance with the specified maximum delay
   * @see #DEFAULT_MAX_DELAY_MILLIS
   */
  public DefaultRetryDelayStrategy maxDelay(long maxDelay, TimeUnit timeUnit) {
    return new DefaultRetryDelayStrategy(lastBaseDelayMillis,
        millisFromTimeUnit(maxDelay, timeUnit),
        this.backoffMultiplier,
        this.jitterMultiplier
        );
  }

  /**
   * Returns a modified strategy with a specific backoff multipler. A multipler of 1
   * means the base delay never changes, 2 means it doubles each time, etc.
   * 
   * @param newBackoffMultiplier the backoff multipler
   * @return a new instance with the specified backoff multiplier
   * @see #DEFAULT_BACKOFF_MULTIPLIER
   */
  public DefaultRetryDelayStrategy backoffMultiplier(float newBackoffMultiplier) {
    return new DefaultRetryDelayStrategy(0, this.maxDelayMillis, newBackoffMultiplier, this.jitterMultiplier);
  }

  /**
   * Returns a modified strategy with a specific jitter multipler. A multipler of 0.5
   * means each delay is reduced randomly by up to 50%, 0.25 means it is reduced
   * randomly by up to 25%, etc. Zero means there is no jitter.
   * 
   * @param newJitterMultiplier the jigger multipler
   * @return a new instance with the specified jitter multipler
   * @see #DEFAULT_JITTER_MULTIPLIER
   */
  public DefaultRetryDelayStrategy jitterMultiplier(float newJitterMultiplier) {
    return new DefaultRetryDelayStrategy(0, this.maxDelayMillis, this.backoffMultiplier, newJitterMultiplier);
  }
  
  private DefaultRetryDelayStrategy(
      long lastBaseDelayMillis,
      long maxDelayMillis,
      float backoffMultiplier,
      float jitterMultiplier
      ) {
    this.lastBaseDelayMillis = lastBaseDelayMillis;
    this.maxDelayMillis = maxDelayMillis;
    this.backoffMultiplier = backoffMultiplier;
    this.jitterMultiplier = jitterMultiplier;
  }
  
  @Override
  public Result apply(long baseDelayMillis) {
    long nextBaseDelay = lastBaseDelayMillis == 0 ? baseDelayMillis :
      (long)(lastBaseDelayMillis * backoffMultiplier);
    if (maxDelayMillis > 0 && nextBaseDelay > maxDelayMillis) {
      nextBaseDelay = maxDelayMillis;
    }
    long adjustedDelay = nextBaseDelay;
    if (jitterMultiplier > 0) {
      // 2^31 milliseconds is much longer than any reconnect time we would reasonably want to use, so we can pin this to int
      int maxTimeInt = nextBaseDelay > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)nextBaseDelay;
      int jitterRange = Math.round(maxTimeInt * jitterMultiplier);
      if (jitterRange > 0) {
        adjustedDelay -= random.nextInt(jitterRange);
      }
    }
    RetryDelayStrategy updatedStrategy =
        new DefaultRetryDelayStrategy(nextBaseDelay, maxDelayMillis, backoffMultiplier, jitterMultiplier);
    return new Result(adjustedDelay, updatedStrategy);
  }
}
