package com.launchdarkly.sse;

interface ConnectionHandler {
  void setReconnectionTimeMs(long reconnectionTimeMs);
  void setLastEventId(String lastEventId);
}