package com.launchdarkly.eventsource;

interface ConnectionHandler {
  void setReconnectionTimeMs(long reconnectionTimeMs);
  void setLastEventId(String lastEventId);
}