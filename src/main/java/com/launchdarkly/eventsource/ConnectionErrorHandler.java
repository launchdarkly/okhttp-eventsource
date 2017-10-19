package com.launchdarkly.eventsource;

public interface ConnectionErrorHandler {
  /**
   * Return values of {@link ConnectionErrorHandler#onConnectionError(Throwable)} indicating what
   * action the {@link EventSource} should take after an error.
   */
  public static enum Action {
    /**
     * Specifies that the error should be logged normally and dispatched to the {@link EventHandler}.
     * Connection retrying will proceed normally if appropriate.
     */
    PROCEED,
    /**
     * Specifies that the connection should be immediately shut down and not retried.  The error
     * will not be dispatched to the {@link EventHandler}.
     */
    SHUTDOWN
  };
  
  /**
   * This method is called synchronously for all exceptions that occur on the socket connection
   * (including an {@link UnsuccessfulResponseException} if the server returns an unexpected HTTP
   * status).  It must not take any direct action to affect the state of the connection, nor do
   * any I/O of its own, but can return {@link Action#SHUTDOWN} to cause the connection to close.
   * @param t  a {@code Throwable} object
   * @return an {@link Action} constant
   */
  Action onConnectionError(Throwable t);
  
  public static final ConnectionErrorHandler DEFAULT = new ConnectionErrorHandler() {
    @Override
    public Action onConnectionError(Throwable t) {
      return Action.PROCEED;
    }
  };
}