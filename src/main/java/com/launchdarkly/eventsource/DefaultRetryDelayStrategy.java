package com.launchdarkly.eventsource;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.eventsource.Helpers.millisFromTimeUnit;

/**
 * Default implementation of the retry delay strategy, providing exponential backoff
 * and jitter.
 * <p>
 * Each instance is immutable: {@link #getDelayMillis()} returns the delay for this
 * instance, and {@link #getNext()} returns the successor instance with the base
 * delay multiplied by the backoff multiplier (pinned at the max delay). Jitter is
 * rolled once per instance at construction so {@link #getDelayMillis()} is
 * deterministic on a given instance.
 * <p>
 * This class is immutable. {@link RetryDelayStrategy#defaultStrategy()} returns the
 * default instance. To change any parameters, call methods which return a modified
 * instance:
 * <pre><code>
 *     RetryDelayStrategy strategy = RetryDelayStrategy.defaultStrategy()
 *       .initialDelay(1, TimeUnit.SECONDS)
 *       .jitterMultiplier(0.25f)
 *       .maxDelay(20, TimeUnit.SECONDS);
 * </code></pre>
 *
 * @since 4.0.0
 */
public class DefaultRetryDelayStrategy extends RetryDelayStrategy {
  /**
   * The default value for {@link #initialDelay(long, TimeUnit)}: 1 second.
   */
  public static final long DEFAULT_INITIAL_DELAY_MILLIS = 1000;

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

  static final DefaultRetryDelayStrategy INSTANCE = new DefaultRetryDelayStrategy(
      DEFAULT_INITIAL_DELAY_MILLIS,
      DEFAULT_MAX_DELAY_MILLIS,
      DEFAULT_BACKOFF_MULTIPLIER,
      DEFAULT_JITTER_MULTIPLIER);

  final long baseDelayMillis;
  private final long maxDelayMillis;
  private final float backoffMultiplier;
  private final float jitterMultiplier;
  private final long delayMillis;

  /**
   * Returns a modified strategy with a specific initial (base) delay. The returned
   * instance is fresh — its backoff progression is reset.
   *
   * @param initialDelay the initial delay in whatever time unit is specified by {@code timeUnit}
   * @param timeUnit the time unit, or {@code TimeUnit.MILLISECONDS} if null
   * @return a new instance with the specified initial delay
   * @since 5.0.0
   * @see #DEFAULT_INITIAL_DELAY_MILLIS
   */
  public DefaultRetryDelayStrategy initialDelay(long initialDelay, TimeUnit timeUnit) {
    return new DefaultRetryDelayStrategy(millisFromTimeUnit(initialDelay, timeUnit),
        this.maxDelayMillis, this.backoffMultiplier, this.jitterMultiplier);
  }

  /**
   * Returns a modified strategy with a specific maximum delay.
   *
   * @param maxDelay the maximum delay, in whatever time unit is specified by {@code timeUnit}
   * @param timeUnit the time unit, or {@code TimeUnit.MILLISECONDS} if null
   * @return a new instance with the specified maximum delay
   * @see #DEFAULT_MAX_DELAY_MILLIS
   */
  public DefaultRetryDelayStrategy maxDelay(long maxDelay, TimeUnit timeUnit) {
    return new DefaultRetryDelayStrategy(this.baseDelayMillis,
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
    return new DefaultRetryDelayStrategy(this.baseDelayMillis, this.maxDelayMillis,
        newBackoffMultiplier, this.jitterMultiplier);
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
    return new DefaultRetryDelayStrategy(this.baseDelayMillis, this.maxDelayMillis,
        this.backoffMultiplier, newJitterMultiplier);
  }

  private DefaultRetryDelayStrategy(
      long baseDelayMillis,
      long maxDelayMillis,
      float backoffMultiplier,
      float jitterMultiplier
      ) {
    this.baseDelayMillis = maxDelayMillis > 0 && baseDelayMillis > maxDelayMillis
        ? maxDelayMillis
        : baseDelayMillis;
    this.maxDelayMillis = maxDelayMillis;
    this.backoffMultiplier = backoffMultiplier;
    this.jitterMultiplier = jitterMultiplier;
    long adjustedDelay = this.baseDelayMillis;
    if (jitterMultiplier > 0 && this.baseDelayMillis > 0) {
      // 2^31 milliseconds is much longer than any reconnect time we would reasonably want to use, so we can pin this to int
      int maxTimeInt = this.baseDelayMillis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)this.baseDelayMillis;
      int jitterRange = Math.round(maxTimeInt * jitterMultiplier);
      if (jitterRange > 0) {
        adjustedDelay -= ThreadLocalRandom.current().nextInt(jitterRange);
      }
    }
    this.delayMillis = adjustedDelay;
  }

  @Override
  public long getDelayMillis() {
    return delayMillis;
  }

  @Override
  public RetryDelayStrategy getNext() {
    long nextBase = (long)(baseDelayMillis * backoffMultiplier);
    if (maxDelayMillis > 0 && nextBase > maxDelayMillis) {
      nextBase = maxDelayMillis;
    }
    return new DefaultRetryDelayStrategy(nextBase, maxDelayMillis, backoffMultiplier, jitterMultiplier);
  }

  @Override
  public DefaultRetryDelayStrategy withBaseDelayMillis(long millis) {
    return new DefaultRetryDelayStrategy(millis, maxDelayMillis, backoffMultiplier, jitterMultiplier);
  }
}
