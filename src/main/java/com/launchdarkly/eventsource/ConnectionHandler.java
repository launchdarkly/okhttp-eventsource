package com.launchdarkly.eventsource;

import java.time.Duration;

interface ConnectionHandler {
  void setReconnectionTime(Duration reconnectionTime);
  void setLastEventId(String lastEventId);
}