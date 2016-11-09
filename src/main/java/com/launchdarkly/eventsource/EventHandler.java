package com.launchdarkly.eventsource;

public interface EventHandler {
  void onOpen() throws Exception;
  void onMessage(String event, MessageEvent messageEvent) throws Exception;
  void onComment(String comment) throws Exception;
  void onError(Throwable t);
}