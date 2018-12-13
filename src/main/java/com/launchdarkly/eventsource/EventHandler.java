package com.launchdarkly.eventsource;

/**
 * Interface for an object that will receive SSE events.
 */
public interface EventHandler {
  /**
   * EventSource calls this method when the stream connection has been opened.
   * @throws Exception throwing an exception here will cause it to be logged and also sent to {@link #onError(Throwable)}
   */
  void onOpen() throws Exception;
  
  /**
   * EventSource calls this method when the stream connection has been closed.
   * @throws Exception throwing an exception here will cause it to be logged and also sent to {@link #onError(Throwable)}
   */
  void onClosed() throws Exception;
  
  /**
   * EventSource calls this method when it has received a new event from the stream.
   * @param event the event name, from the {@code event:} line in the stream  
   * @param messageEvent a {@link MessageEvent} object containing all the other event properties
   * @throws Exception throwing an exception here will cause it to be logged and also sent to {@link #onError(Throwable)}
   */
  void onMessage(String event, MessageEvent messageEvent) throws Exception;
  
  /**
   * EventSource calls this method when it has received a comment line from the stream (any line starting with a colon).
   * @param comment the comment line
   * @throws Exception throwing an exception here will cause it to be logged and also sent to {@link #onError(Throwable)}
   */
  void onComment(String comment) throws Exception;
  
  /**
   * This method will be called for all exceptions that occur on the socket connection (including
   * an {@link UnsuccessfulResponseException} if the server returns an unexpected HTTP status),
   * but only after the {@link ConnectionErrorHandler} (if any) has processed it.  If you need to
   * do anything that affects the state of the connection, use {@link ConnectionErrorHandler}.
   * @param t  a {@code Throwable} object
   */
  void onError(Throwable t);
}