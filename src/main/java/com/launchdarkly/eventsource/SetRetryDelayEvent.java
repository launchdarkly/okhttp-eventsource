package com.launchdarkly.eventsource;

/**
 * Represents a "retry:" line that was read from the stream. EventSource consumes this
 * internally from EventParser and does not surface it to the caller; therefore the
 * class is package-private.
 */
final class SetRetryDelayEvent implements StreamEvent {
  private final long retryMillis;
  
  SetRetryDelayEvent(long retryMillis) {
    this.retryMillis = retryMillis;
  }
  
  public long getRetryMillis() {
    return retryMillis;
  }
  
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    SetRetryDelayEvent that = (SetRetryDelayEvent) o;
    return retryMillis == that.retryMillis;
  }

  @Override
  public int hashCode() {
    return (int)retryMillis;
  }
  
  @Override
  public String toString() {
    return "SetRetryDelayEvent(" + retryMillis + ")";
  }
}
