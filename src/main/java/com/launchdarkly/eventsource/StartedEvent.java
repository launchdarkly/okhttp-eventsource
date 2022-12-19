package com.launchdarkly.eventsource;

/**
 * Represents the beginning of a stream.
 * <p>
 * This event will be returned by {@link EventSource#readAnyEvent()} or
 * {@link EventSource#anyEvents()} if the stream started as a side effect of
 * calling those methods, rather than from calling {@link EventSource#start()}.
 * You will also get a new StartedEvent if the stream was closed and then
 * reconnected.
 *
 * @see FaultEvent
 * @since 4.0.0
 */
public final class StartedEvent implements StreamEvent {
  @Override
  public boolean equals(Object o) {
    return o instanceof StartedEvent;
  }

  @Override
  public int hashCode() {
    return 0;
  }
  
  @Override
  public String toString() {
    return "StartedEvent";
  }
}
