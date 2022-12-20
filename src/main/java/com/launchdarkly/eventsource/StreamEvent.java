package com.launchdarkly.eventsource;

/**
 * A marker interface for all types of stream information that can be returned by
 * {@link EventSource#readAnyEvent()} and {@link EventSource#messages()}.
 *
 * @since 4.0.0
 */
public interface StreamEvent {
}
