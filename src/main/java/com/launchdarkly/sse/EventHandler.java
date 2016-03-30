package com.launchdarkly.sse;

public interface EventHandler {
  void onOpen() throws Exception;
  void onMessage(String event, MessageEvent messageEvent) throws Exception;
  void onError(Throwable t);
}