package com.launchdarkly.eventsource;

import java.util.Objects;

/**
 * Describes a comment line received from the stream.
 * <p>
 * An SSE comment is a line that starts with a colon. There is no defined meaning for this
 * in the SSE specification, and most clients ignore it. It may be used to provide a
 * periodic heartbeat from the server to keep connections from timing out.
 * 
 * @since 4.0.0
 */
public final class CommentEvent implements StreamEvent {
  private final String text;
  
  /**
   * Creates an instance.
   *
   * @param text the comment text, not including the leading colon
   */
  public CommentEvent(String text) {
    this.text = text;
  }

  /**
   * Returns the comment text, not including the leading colon.
   *
   * @return the text
   */
  public String getText() {
    return text;
  }
  
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    CommentEvent that = (CommentEvent) o;
    return Objects.equals(text, that.text);
  }

  @Override
  public int hashCode() {
    return Objects.hash(text);
  }
  
  @Override
  public String toString() {
    return "CommentEvent(" + text + ")";
  }
}
