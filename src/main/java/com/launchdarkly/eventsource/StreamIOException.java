package com.launchdarkly.eventsource;

import java.io.IOException;
import java.util.Objects;

/**
 * An exception indicating that a connection could not be made, or that an
 * existing connection failed with a network error.
 * <p>
 * See {@link StreamException} for more about EventSource's exception behavior.
 *
 * @since 4.0.0
 */
@SuppressWarnings("serial")
public final class StreamIOException extends StreamException {
  private final IOException ioException;
  
  /**
   * Constructs an instance.
   *
   * @param cause the underlying error
   */
  public StreamIOException(IOException cause) {
    super(cause);
    this.ioException = cause;
  }
  
  /**
   * Returns the original error as an {@link IOException}.
   * @return the original error
   */
  public IOException getIOException() {
    return ioException;
  }
  
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    StreamIOException that = (StreamIOException) o;
    return Objects.equals(ioException, that.ioException);
  }

  @Override
  public int hashCode() {
    return Objects.hash(ioException);
  }
}
