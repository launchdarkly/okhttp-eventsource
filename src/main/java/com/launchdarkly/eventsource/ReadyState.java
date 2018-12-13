package com.launchdarkly.eventsource;

/**
 * Enum values that can be returned by {@link EventSource#getState()}.
 */
public enum ReadyState {
  /**
   * The EventSource's {@link EventSource#start()} method has not yet been called.
   */
  RAW,
  /**
   * The EventSource is attempting to make a connection.
   */
  CONNECTING,
  /**
   * The connection is active and the EventSource is listening for events.
   */
  OPEN,
  /**
   * The connection has been closed or has failed, and the EventSource will attempt to reconnect.
   */
  CLOSED,
  /**
   * The connection has been permanently closed and will not reconnect.
   */
  SHUTDOWN
}
