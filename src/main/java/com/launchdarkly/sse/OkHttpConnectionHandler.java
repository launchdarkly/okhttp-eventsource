package com.launchdarkly.sse;

public class OkHttpConnectionHandler implements ConnectionHandler {

  private volatile long reconnectionTimeMs;
  private volatile String lastEventId;

  public void setReconnectionTimeMs(long reconnectionTimeMs) {
    this.reconnectionTimeMs = reconnectionTimeMs;
  }

  public void setLastEventId(String lastEventId) {
    this.lastEventId = lastEventId;
  }

  public void run() {

  }
}
