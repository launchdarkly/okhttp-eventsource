package com.launchdarkly.eventsource;

enum ReadyState {
  RAW,
  CONNECTING,
  OPEN,
  CLOSED,
  SHUTDOWN
}
