package com.launchdarkly.eventsource;

import java.util.Objects;

/**
 * Describes a failure in the stream.
 * <p>
 * When an error occurs, if the configured {@link ErrorStrategy} returns
 * {@link ErrorStrategy.Action#CONTINUE}, {@link EventSource#readAnyEvent()} and
 * {@link EventSource#anyEvents()} will return a FaultEvent. Otherwise, the error
 * would instead be thrown as a {@link StreamException}.
 * <p>
 * If you receive a FaultEvent, the EventSource is now in an inactive state since
 * either a connection attempt has failed or an existing connection has been closed.
 * EventSource will attempt to reconnect if you either call {@link EventSource#start()}
 * or simply continue reading events after this point.
 *
 * @see StartedEvent
 * @since 4.0.0
 */
public final class FaultEvent implements StreamEvent {
  private final StreamException cause;
  
  /**
   * Creates an instance.
   *
   * @param cause the cause of the failure
   */
  public FaultEvent(StreamException cause) {
    this.cause = cause;
  }
  
  /**
   * Returns a {@link StreamException} describing the cause of the failure.
   *
   * @return the cause of the failure
   */
  public StreamException getCause() {
    return cause;
  }
  
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    FaultEvent that = (FaultEvent) o;
    return Objects.equals(cause, that.cause);
  }

  @Override
  public int hashCode() {
    return Objects.hash(cause);
  }
  
  @Override
  public String toString() {
    return "FaultEvent(" + cause + ")";
  }
}
