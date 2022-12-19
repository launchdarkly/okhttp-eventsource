package com.launchdarkly.eventsource;

import java.util.concurrent.TimeUnit;

/**
 * An abstraction of how to determine whether a stream failure should be thrown to the
 * caller as an exception, or treated as an event.
 */
public abstract class ErrorStrategy {
  /**
   * Describes the possible actions EventSource could take after an error.
   */
  public static enum Action {
    /**
     * Indicates that EventSource should throw a {@link StreamException} from whatever
     * reading method was called ({@link EventSource#start()}, {@link EventSource#readMessage()},
     * etc.).
     */
    THROW,
    
    /**
     * Indicates that EventSource should not throw an exception, but instead return a
     * {@link FaultEvent} to the caller. If the caller continues to read from the failed
     * stream after that point, EventSource will try to reconnect to the stream.
     */
    CONTINUE
  };
  
  /**
   * The return type of {@link ErrorStrategy#apply(StreamException)}.
   */
  public static class Result {
    private final Action action;
    private final ErrorStrategy next;
    
    /**
     * Constructs an instance.
     *
     * @param action see {@link #getAction()}
     * @param next see {@link #getNext()}
     */
    public Result(Action action, ErrorStrategy next) {
      this.action = action;
      this.next = next;
    }

    /**
     * Returns the action that EventSource should take.
     *
     * @return the action specified by the strategy
     */
    public Action getAction() {
      return action;
    }

    /**
     * Returns the strategy instance to be used for the next retry, or null to use the
     * same instance as last time.
     *
     * @return a new instance or null
     */
    public ErrorStrategy getNext() {
      return next;
    }
  }
  
  /**
   * Applies the strategy to determine whether to retry after a failure.
   *
   * @param exception describes the failure
   * @return the result
   */
  public abstract Result apply(StreamException exception);
  
  /**
   * Specifies that EventSource should always throw an exception if there is an error.
   * This is the default behavior if you do not configure another.
   *
   * @return a strategy to be passed to {@link EventSource.Builder#errorStrategy(ErrorStrategy)}.
   */
  public static ErrorStrategy alwaysThrow() {
    return new ErrorStrategy() {
      @Override
      public Result apply(StreamException exception) {
        return new Result(Action.THROW, null);
      }
    };
  }
  
  /**
   * Specifies that EventSource should never throw an exception, but should return all errors
   * as {@link FaultEvent}s. Be aware that using this mode could cause {@link EventSource#start()}
   * to block indefinitely if connections never succeed.
   *
   * @return a strategy to be passed to {@link EventSource.Builder#errorStrategy(ErrorStrategy)}.
   */
  public static ErrorStrategy alwaysContinue() {
    return new ErrorStrategy() {
      @Override
      public Result apply(StreamException exception) {
        return new Result(Action.CONTINUE, null);
      }
    };
  }
  
  /**
   * Specifies that EventSource should automatically retry after a failure for up to this
   * number of consecutive attempts, but should throw an exception after that point.
   *
   * @param maxAttempts the maximum number of consecutive retries
   * @return a strategy to be passed to {@link EventSource.Builder#errorStrategy(ErrorStrategy)}.
   */
  public static ErrorStrategy continueWithMaxAttempts(int maxAttempts) {
    return new MaxAttemptsStrategy(maxAttempts, 0);
  }
  
  private static class MaxAttemptsStrategy extends ErrorStrategy {
    private final int maxAttempts;
    private final int counter;
    
    MaxAttemptsStrategy(int maxAttempts, int counter) {
      this.maxAttempts = maxAttempts;
      this.counter = counter;
    }
    
    @Override
    public Result apply(StreamException exception) {
      if (counter < maxAttempts) {
        return new Result(Action.CONTINUE, new MaxAttemptsStrategy(maxAttempts, counter + 1));
      }
      return new Result(Action.THROW, null);
    }
  }
  
  /**
   * Specifies that EventSource should automatically retry after a failure and can retry
   * repeatedly until this amount of time has elapsed, but should throw an exception after
   * that point.
   *
   * @param maxTime the time limit, in whatever units are specified by {@code timeUnit}
   * @param timeUnit the time unit, or {@code TimeUnit.MILLISECONDS} if null
   * @return a strategy to be passed to {@link EventSource.Builder#errorStrategy(ErrorStrategy)}.
   */
  public static ErrorStrategy continueWithTimeLimit(long maxTime, TimeUnit timeUnit) {
    long timeoutMillis = Helpers.millisFromTimeUnit(maxTime, timeUnit);
    return new TimeLimitStrategy(timeoutMillis, 0);
  }
  
  private static class TimeLimitStrategy extends ErrorStrategy {
    private final long timeoutMillis;
    private final long startTime;
    
    TimeLimitStrategy(long timeoutMillis, long startTime) {
      this.timeoutMillis = timeoutMillis;
      this.startTime = startTime;
    }
    
    @Override
    public Result apply(StreamException exception) {
      if (startTime == 0) {
        return new Result(Action.CONTINUE, new TimeLimitStrategy(timeoutMillis, System.currentTimeMillis()));
      }
      if (System.currentTimeMillis() - startTime < timeoutMillis) {
        return new Result(Action.CONTINUE, new TimeLimitStrategy(timeoutMillis, startTime));
      }
      return new Result(Action.THROW, null);
    }
  }
}
