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
  private final ResponseHeaders headers;

  /**
   * Constructs an instance.
   * @param code the HTTP status
   */
  public StreamHttpErrorException(int code) {
    this(code, null);
  }

  /**
   * Constructs an instance with response headers.
   * @param code the HTTP status
   * @param headers the response headers, or null if not available
   *
   * @since 4.2.0
   */
  public StreamHttpErrorException(int code, ResponseHeaders headers) {
    super("Server returned HTTP error " + code);
    this.code = code;
    this.headers = headers;
  }

  /**
   * Returns the HTTP status code.
   * @return the HTTP status
   */
  public int getCode() {
    return code;
  }

  /**
   * Returns the response headers from the failed HTTP request, or null if not available.
   *
   * @return the response headers, or null
   * @since 4.2.0
   */
  public ResponseHeaders getHeaders() {
    return headers;
  }
}
