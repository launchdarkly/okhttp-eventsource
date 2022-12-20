package com.launchdarkly.eventsource;

/**
 * An exception indicating that the stream stopped because the server closed the
 * connection.
 * <p>
 * This exception only happens if you are trying to read from one thread while
 * {@link EventSource#stop()}, {@link EventSource#interrupt()}, or {@link EventSource#close()}
 * is called from another thread.
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
