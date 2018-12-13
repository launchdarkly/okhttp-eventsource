package com.launchdarkly.eventsource;

import java.net.URI;

/**
 * Event information that is passed to {@link EventHandler#onMessage(String, MessageEvent)}.
 */
public class MessageEvent {
  private final String data;
  private final String lastEventId;
  private final URI origin;

  /**
   * Constructs a new instance.
   * @param data the event data, if any
   * @param lastEventId the event ID, if any
   * @param origin the stream endpoint
   */
  public MessageEvent(String data, String lastEventId, URI origin) {
    this.data = data;
    this.lastEventId = lastEventId;
    this.origin = origin;
  }

  /**
   * Constructs a new instance.
   * @param data the event data, if any
   */
  public MessageEvent(String data) {
    this(data, null, null);
  }

  /**
   * Returns the event data, if any.
   * @return the data string or null
   */
  public String getData() {
    return data;
  }

  /**
   * Returns the event ID, if any.
   * @return the event ID or null
   */
  public String getLastEventId() {
    return lastEventId;
  }

  /**
   * Returns the endpoint of the stream that generated the event.
   * @return the stream URI
   */
  public URI getOrigin() {
    return origin;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    MessageEvent that = (MessageEvent) o;

    if (data != null ? !data.equals(that.data) : that.data != null) return false;
    if (lastEventId != null ? !lastEventId.equals(that.lastEventId) : that.lastEventId != null) return false;
    return !(origin != null ? !origin.equals(that.origin) : that.origin != null);

  }

  @Override
  public int hashCode() {
    int result = data != null ? data.hashCode() : 0;
    result = 31 * result + (lastEventId != null ? lastEventId.hashCode() : 0);
    result = 31 * result + (origin != null ? origin.hashCode() : 0);
    return result;
  }
}
