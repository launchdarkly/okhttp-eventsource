package com.launchdarkly.eventsource;

/**
 * Exception class that means the remote server returned an HTTP error.
 */
@SuppressWarnings("serial")
public class UnsuccessfulResponseException extends Exception {

  private final int code;

  /**
   * Constructs an exception instance.
   * @param code the HTTP status
   */
  public UnsuccessfulResponseException(int code) {
    super("Unsuccessful response code received from stream: " + code);
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
