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
 * Implementations of this interface should be immutable. To implement strategies where
 * the delay uses different parameters on each subsequent retry (such as exponential
 * backoff), the strategy should return a new instance of its own class in
 * {@link RetryDelayStrategy.Result#getNext()}, rather than modifying the state of the
 * existing instance. This makes it easy for EventSource to reset to the original delay
 * state when appropriate by simply reusing the original instance.
 *
 * @since 4.0.0
 */
public abstract class RetryDelayStrategy {
  /**
   * The return type of {@link RetryDelayStrategy#apply(long)}.
   */
  public static class Result {
    private final long delayMillis;
    private final RetryDelayStrategy next;
    
    /**
     * Constructs an instance.
     *
     * @param delayMillis the computed delay in milliseconds
     * @param next a {@link RetryDelayStrategy} instance to be used for the next retry;
     *   null means to use the same instance as last time
     */
    public Result(long delayMillis, RetryDelayStrategy next) {
      this.delayMillis = delayMillis;
      this.next = next;
    }

    /**
     * Returns the computed delay.
     * @return the delay in milliseconds
     */
    public long getDelayMillis() {
      return delayMillis;
    }

    /**
     * Returns the strategy instance to be used for the next retry, or null to use the
     * same instance as last time.
     * @return a new instance or null
     */
    public RetryDelayStrategy getNext() {
      return next;
    }
  }
  
  /**
   * Applies the strategy to compute the appropriate retry delay.
   *
   * @param baseDelayMillis the initial configured base delay as set by
   *   {@link EventSource.Builder#retryDelay(long, java.util.concurrent.TimeUnit)}
   * @return the computed delay
   */
  public abstract Result apply(long baseDelayMillis);
  
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
