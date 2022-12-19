package com.launchdarkly.eventsource;

import java.util.Objects;

/**
 * Base class for all exceptions thrown by {@link EventSource}.
 *
 * @since 4.0.0
 */
@SuppressWarnings("serial")
public class StreamException extends Exception {
  /**
   * Base class constructor.
   * @param message the event message
   */
  protected StreamException(String message) {
    super(message);
  }

  /**
   * Base class constructor.
   * @param cause a wrapped exception
   */
  protected StreamException(Exception cause) {
    super(cause);
  }
  
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    StreamException that = (StreamException) o;
    return Objects.equals(getMessage(), that.getMessage()) &&
        Objects.equals(getCause(), that.getCause());
  }
  
  @Override
  public int hashCode() {
    return Objects.hash(getMessage(), getCause());
  }
}
