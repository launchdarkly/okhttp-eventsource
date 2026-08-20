package com.launchdarkly.eventsource;

/**
 * RetryDelayStrategy is an abstraction of how EventSource should determine the delay
 * between retry attempts when a stream fails.
 * <p>
 * The default behavior, provided by {@link DefaultRetryDelayStrategy}, provides
 * customizable exponential backoff and jitter. Applications may also create their own
 * implementations of RetryDelayStrategy if they desire different behavior. It is
 * generally a best practice to use backoff and jitter, to avoid a reconnect storm
 * during a service interruption.
 * <p>
 * Implementations should be immutable. Each instance represents a single state in the
 * retry-delay sequence: {@link #getDelayMillis()} returns the delay to use for the
 * impending retry, and {@link #getNext()} returns the strategy instance to use for
 * the retry after that. Strategies with a base-delay concept may also implement
 * {@link #withBaseDelayMillis(long)} to accept server-directed reconnection-time
 * overrides from the SSE {@code retry:} field.
 *
 * @since 4.0.0
 */
public abstract class RetryDelayStrategy {
  /**
   * Returns the retry delay this instance represents, in milliseconds. Pure and
   * deterministic on a given instance.
   *
   * @return the delay in milliseconds
   * @since 5.0.0
   */
  public abstract long getDelayMillis();

  /**
   * Returns the strategy instance to use for the retry after this one. Does not
   * modify this instance.
   * <p>
   * Strategies that never advance (e.g., a constant-delay strategy) return
   * {@code this}. Strategies with backoff progression return a new instance
   * carrying the advanced state.
   *
   * @return the strategy to use next
   * @since 5.0.0
   */
  public abstract RetryDelayStrategy getNext();

  /**
   * Returns a fresh instance of this strategy with its base delay set to the given
   * value and any backoff progression reset.
   * <p>
   * The default implementation returns {@code this}. Strategies without a base-delay
   * concept opt out of wire-directed base overrides by not implementing this method.
   *
   * @param millis the new base delay in milliseconds
   * @return a fresh instance with the given base, or {@code this} if the strategy
   *   does not honor base overrides
   * @since 5.0.0
   */
  public RetryDelayStrategy withBaseDelayMillis(long millis) {
    return this;
  }

  /**
   * Returns the default implementation, configured to use the default backoff and
   * jitter.
   * <p>
   * You can call {@link DefaultRetryDelayStrategy} methods on this instance to configure a
   * strategy with different parameters.
   *
   * @return the {@link DefaultRetryDelayStrategy}.
   */
  public static DefaultRetryDelayStrategy defaultStrategy() {
    return DefaultRetryDelayStrategy.INSTANCE;
  }
}
