package com.launchdarkly.eventsource;

import okhttp3.Response;

/**
 * Exception class that means the remote server returned an HTTP error.
 */
@SuppressWarnings("serial")
public class UnsuccessfulResponseException extends Exception {

  private final Response response;

  /**
   * Constructs an exception instance.
   * @param code the HTTP status
   */
  public UnsuccessfulResponseException(int code) {
    this(new Response.Builder().code(code).build());
  }

  /**
   * Constructs an exception instance.
   * @param response {@link Response} provided by server
   */
  public UnsuccessfulResponseException(Response response) {
    super("Unsuccessful response code received from stream: " + response.code());
    this.response = response;
  }

  /**
   * Returns the HTTP status code.
   * @return the HTTP status
   */
  public int getCode() {
    return response.code();
  }

  /**
   * Returns the HTTP {@link Response}.
   * @return the HTTP {@link Response}
   */
  public Response getResponse() {
    return response;
  }
}
