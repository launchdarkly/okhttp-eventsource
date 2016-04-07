package com.launchdarkly.eventsource;

public enum ReadyState {
  RAW,
  CONNECTING,
  OPEN,
  CLOSED,
  SHUTDOWN;
}
