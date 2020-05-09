package com.launchdarkly.eventsource;

import okhttp3.Protocol;
import okhttp3.Request;
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
  @Deprecated
  public UnsuccessfulResponseException(int code) {
    this(buildSurrogateResponse(code));
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

  /**
   * Used for backward compatibility only: provide surrogate response for a given code
   */
  @Deprecated
  private static Response buildSurrogateResponse(int code) {
    return new Response.Builder()
      .request(new Request.Builder()
        .url("http://example.com")
        .build()
      )
      .protocol(Protocol.HTTP_1_1)
      .message("")
      .code(code)
      .build();
  }
}
