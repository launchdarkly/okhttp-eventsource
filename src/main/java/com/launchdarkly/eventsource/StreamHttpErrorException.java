package com.launchdarkly.eventsource;

/**
 * An exception indicating that the remote server returned an HTTP error.
 * <p>
 * {@link HttpConnectStrategy} defines an error as any non-2xx status.
 * <p>
 * See {@link StreamException} for more about EventSource's exception behavior.
 *
 * @since 4.0.0
 */
@SuppressWarnings("serial")
public class StreamHttpErrorException extends StreamException {

  private final int code;

  /**
   * Constructs an instance.
   * @param code the HTTP status
   */
  public StreamHttpErrorException(int code) {
    super("Server returned HTTP error " + code);
    this.code = code;
  }

  /**
   * Returns the HTTP status code.
   * @return the HTTP status
   */
  public int getCode() {
    return code;
  }
}
