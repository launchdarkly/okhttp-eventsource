package com.launchdarkly.eventsource.background;

import com.launchdarkly.eventsource.MessageEvent;

@SuppressWarnings("javadoc")
public class BaseBackgroundEventHandler implements BackgroundEventHandler {
  @Override
  public void onOpen() throws Exception {}

  @Override
  public void onClosed() throws Exception {}

  @Override
  public void onMessage(String event, MessageEvent messageEvent) throws Exception {}

  @Override
  public void onComment(String comment) throws Exception {}

  @Override
  public void onError(Throwable t) {}
}
