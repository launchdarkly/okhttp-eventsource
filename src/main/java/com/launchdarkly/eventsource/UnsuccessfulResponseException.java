package com.launchdarkly.eventsource;

public class UnsuccessfulResponseException extends Exception {

  private final int code;

  public UnsuccessfulResponseException(int code) {
    super("Unsuccessful response code received from stream: " + code);
    this.code = code;
  }

  public int getCode() {
    return code;
  }
}
