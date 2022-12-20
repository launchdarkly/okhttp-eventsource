package com.launchdarkly.eventsource.background;

import com.launchdarkly.eventsource.StreamHttpErrorException;

import java.io.EOFException;

/**
 * Interface for an object that will be notified when {@link BackgroundEventSource}
 * encounters a network error or receives an error response.
 * <p>
 * This is different from {@link BackgroundEventHandler#onError(Throwable)}
 * in two ways:
 * <ol>
 * <li> It has the ability to tell BackgroundEventSource to shut down instead of reconnecting.
 * <li> If the server simply ends the stream, the {@code ConnectionErrorHandler} is called with
 * an {@code EOFException}; {@code onError} is not called in this case.
 * </ol>
 */
public interface ConnectionErrorHandler {
  /**
   * Return values of {@link ConnectionErrorHandler#onConnectionError(Throwable)} indicating what
   * action the {@link BackgroundEventSource} should take after an error.
   */
  public static enum Action {
    /**
     * Specifies that the error should be logged normally and dispatched to the {@link
     * BackgroundEventHandler}. Connection retrying will proceed normally if appropriate.
     */
    PROCEED,
    /**
     * Specifies that the connection should be immediately shut down and not retried.  The error
     * will not be dispatched to the {@link BackgroundEventHandler}.
     */
    SHUTDOWN
  };
  
  /**
   * This method is called synchronously for all exceptions that occur on the socket connection
   * (including a {@link StreamHttpErrorException} if the server returns an unexpected HTTP
   * status, or {@link EOFException} if the streaming response has ended).
   * <p>
   * It must not take any direct action to affect the state of the connection, nor do any I/O of
   * its own, but it can return {@link Action#SHUTDOWN} to cause the connection to close.
   * 
   * @param t  a {@code Throwable} object
   * @return an {@link Action} constant
   */
  Action onConnectionError(Throwable t);
}
