package com.launchdarkly.eventsource;

public interface EventHandler {
  void onOpen() throws Exception;
  void onClosed() throws Exception;
  void onMessage(String event, MessageEvent messageEvent) throws Exception;
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