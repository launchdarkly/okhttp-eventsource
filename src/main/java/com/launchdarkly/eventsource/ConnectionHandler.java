package com.launchdarkly.eventsource;

interface ConnectionHandler {
  void setReconnectTimeMillis(long reconnecTimeMillis);
  void setLastEventId(String lastEventId);
}