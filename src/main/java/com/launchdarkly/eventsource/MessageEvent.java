package com.launchdarkly.eventsource;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.util.Objects;

/**
 * Event information that is passed to {@link EventHandler#onMessage(String, MessageEvent)}.
 */
public class MessageEvent {
  /**
   * The default value of {@link #getEventName()} for all SSE messages that did not have an {@code event}
   * field. This constant is defined in the SSE specification.
   * 
   * @since 2.6.0
   */
  public static final String DEFAULT_EVENT_NAME = "message";
  
  private static final int READER_BUFFER_SIZE = 2000; // used only when converting a Reader into a String
  
  private volatile String data;
  private volatile Reader dataReader;
  private final Object dataReaderLock;
  private final String eventName;
  private final String lastEventId;
  private final URI origin;

  /**
   * Simple constructor with event data only, using the default event name.
   * <p>
   * This constructor assumes that the event data has been fully read into memory as a String.
   * 
   * @param data the event data; if null, will be changed to an empty string
   */
  public MessageEvent(String data) {
    this(null, data, null, null);
  }

  /**
   * Constructs a new instance with the default event name.
   * <p>
   * This constructor assumes that the event data has been fully read into memory as a String.
   * 
   * @param data the event data; if null, will be changed to an empty string
   * @param lastEventId the event ID, or null if none
   * @param origin the stream endpoint
   */
  public MessageEvent(String data, String lastEventId, URI origin) {
    this(null, data, lastEventId, origin);
  }

  /**
   * Constructs a new instance.
   * <p>
   * This constructor assumes that the event data has been fully read into memory as a String.
   * 
   * @param eventName the event name; if null, {@link #DEFAULT_EVENT_NAME} is used
   * @param data the event data; if null, will be changed to an empty string
   * @param lastEventId the event ID, or null if none
   * @param origin the stream endpoint
   * 
   * @since 2.6.0
   */
  public MessageEvent(String eventName, String data, String lastEventId, URI origin) {
    this.eventName = eventName == null ? DEFAULT_EVENT_NAME : eventName;
    this.data = data == null ? "" : data;
    this.dataReader = null;
    this.dataReaderLock = new Object();
    this.lastEventId = lastEventId;
    this.origin = origin;
  }

  /**
   * Constructs a new instance with lazy-loading behavior.
   * <p>
   * This constructor takes a {@link Reader} instead of a String for the event data. This is an
   * optimization that sometimes allows events to be processed without large buffers. The caller
   * must be careful about using this model because the behavior of the reader is not idempotent;
   * see {@link #getDataReader()}.
   * 
   * @param eventName an object that will provide the event name if requested
   * @param dataReader a {@link Reader} for consuming the event data
   * @param lastEventId an object that will provide the last event ID if requested
   * @param origin the stream endpoint
   * 
   * @see #getDataReader()
   * @since 2.6.0
   */
  public MessageEvent(
      String eventName,
      Reader dataReader,
      String lastEventId,
      URI origin
      ) {
    this.data = null;
    this.dataReader = dataReader;
    this.dataReaderLock = new Object();
    this.eventName = eventName == null ? DEFAULT_EVENT_NAME : eventName;
    this.lastEventId = lastEventId;
    this.origin = origin;
  }

  /**
   * Constructs a new instance.
   * 
   * @param eventName the event name
   * @param data the event data, if any
   */
  public MessageEvent(String eventName, String data) {
    this(eventName, data, null, null);
  }

  /**
   * Returns the event name. This is the value of the {@code event} field in the SSE message, or, if
   * there was none, the constant {@link #DEFAULT_EVENT_NAME}.
   * 
   * @return the event name
   * @since 2.6.0
   */
  public String getEventName() {
    return eventName;
  }
  
  /**
   * Returns the event data as a string.
   * <p>
   * The format of event data is described in the SSE specification. Every event has at least one
   * line with a {@code data} or {@code data:} prefix. After removing the prefix, multiple lines
   * are concatenated with a separator of {@code '\n'}.
   * <p>
   * If you have set the {@link EventSource.Builder#streamEventData(boolean)} option to {@code true}
   * to enable streaming delivery of event data to your handler without buffering the entire event,
   * you should use {@link #getDataReader()} instead of {@link #getData()}. Calling {@link #getData()}
   * in this mode would defeat the purpose by causing all of the data to be read at once. However, if
   * you do this, {@link #getData()} memoizes the result so that calling it repeatedly does not try
   * to read the stream again.
   * <p>
   * The method will never return {@code null}; every event has data, even if the data is empty
   * (zero length).
   * 
   * @return the data string
   */
  public String getData() {
    if (data != null) {
      return data;
    }
    synchronized (dataReaderLock) {
      if (data != null) { // debounce concurrent requests
        return data; // COVERAGE: this condition can't be reproduced in unit tests
      }
      char[] buffer = new char[READER_BUFFER_SIZE];
      StringBuilder sb = new StringBuilder(READER_BUFFER_SIZE);
      try {
        int n;
        while ((n = dataReader.read(buffer, 0, buffer.length)) != -1) {
          sb.append(buffer, 0, n);
        }
        dataReader.close();
      } catch (IOException e) {
      }
      data = sb.toString();
      dataReader = new StringReader(data);
      return data;
    }
  }

  /**
   * Returns a single-use {@link Reader} for consuming the event data.
   * <p>
   * This is meant to be used if you have set the option {@link EventSource.Builder#streamEventData(boolean)}
   * to {@code true}, indicating that you want to consume the data with a streaming approach,
   * rather than buffering the full event in memory. In this mode, {@link #getDataReader()}
   * returns a {@link Reader} that consumes the event data while it is being received. The
   * format is the same as described in {@link #getData()} (for instance, if the data was
   * sent in multiple lines, the lines are separated by {@code '\n'}). Because this {@link Reader}
   * is connected directly to the HTTP response stream, it is only valid within the scope of
   * your handler's {@link EventHandler#onMessage(String, MessageEvent)} method and will be
   * closed as soon as your handler returns.
   * <p>
   * See {@link EventSource.Builder#streamEventData(boolean)} for more details and important
   * limitations of the streaming mode.
   * <p>
   * If you have <i>not</i> set that option, then the returned {@link Reader} simply provides
   * the same already-buffered data that would be available from {@link #getData()}.
   * <p>
   * This {@link Reader} can be used only once; if you have already consumed the data, further
   * calls to {@link #getDataReader()} will return the same instance that will not read any more. 
   * <p>
   * The method will never return {@code null}; every event has data, even if the data is empty
   * (zero length).
   *  
   * @return a reader for the event data
   * @since 2.6.0
   */
  public Reader getDataReader() {
    synchronized (dataReaderLock) {
      if (dataReader == null) {
        dataReader = new StringReader(data);
      }
      return dataReader;
    }
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

  /**
   * Returns {@code true} if this event was dispatched with streaming data behavior rather than pre-read data.
   * <p>
   * This is only the case if you have set the {@link EventSource.Builder#streamEventData(boolean)} option
   * to {@code true}.
   * 
   * @return true if the event has streaming data
   * @since 2.6.0
   */
  public boolean isStreamingData() {
    return data == null;
  }
  
  void close() {
    synchronized (dataReaderLock) {
      if (dataReader != null) {
        try {
          dataReader.close();
        } catch (IOException e) {}
      }
    }
  }
  
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    MessageEvent that = (MessageEvent) o;

    return Objects.equals(getEventName(), that.getEventName()) &&
        Objects.equals(getData(), that.getData()) &&
        Objects.equals(getLastEventId(), that.getLastEventId()) &&
        Objects.equals(getOrigin(), that.getOrigin());
  }

  @Override
  public int hashCode() {
    return Objects.hash(getEventName(), getData(), getLastEventId(), getOrigin());
  }
  
  @Override
  public String toString() {
    synchronized (dataReaderLock) {
      StringBuilder sb = new StringBuilder("MessageEvent(eventName=")
          .append(eventName)
          .append(",data=")
          .append(data == null ? "<streaming>" : data);
      if (lastEventId != null) {
        sb.append(",id=").append(lastEventId);
      }
      sb.append(",origin=").append(origin).append(')');
      return sb.toString();
    }
  }
}
