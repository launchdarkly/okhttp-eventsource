package com.launchdarkly.eventsource;

/**
 * An exception indicating that the stream stopped because the server closed the
 * connection.
 * <p>
 * This exception is thrown from {@link EventSource#readMessage()} and {@link EventSource#readAnyEvent()}
 * when the server closes the connection, whether or not the last message was complete. In streaming
 * data mode, if the connection ends while the application is reading a message's data from the
 * {@code Reader}, that {@code Reader} will throw a {@link StreamClosedWithIncompleteMessageException}
 * (an {@link java.io.IOException}) instead.
 * <p>
 * See {@link StreamException} for more about EventSource's exception behavior.
 *
 * @since 4.0.0
 */
@SuppressWarnings("serial")
public class StreamClosedByServerException extends StreamException {
  /**
   * Constructs an instance.
   */
  public StreamClosedByServerException() {
    super("Stream closed by server");
  }

  @Override
  public boolean equals(Object o) {
    return o != null && getClass() == o.getClass();
  }

  @Override
  public int hashCode() {
    return 0;
  }
}
