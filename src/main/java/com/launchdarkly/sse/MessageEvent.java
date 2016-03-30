package com.launchdarkly.sse;

public class MessageEvent {
  private final String data;
  private final String lastEventId;
  private final String origin;

  public MessageEvent(String data, String lastEventId, String origin) {
    this.data = data;
    this.lastEventId = lastEventId;
    this.origin = origin;
  }

  public MessageEvent(String data) {
    this(data, null, null);
  }

  public String getData() {
    return data;
  }

  public String getLastEventId() {
    return lastEventId;
  }

  public String getOrigin() {
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
