package com.launchdarkly.eventsource;


import java.util.concurrent.Executor;

/**
 * Adapted from https://github.com/aslakhellesoy/eventsource-java/blob/master/src/main/java/com/github/eventsource/client/impl/AsyncEventSourceHandler.java
 */
class AsyncEventHandler implements EventHandler {
  private final Executor executor;
  private final EventHandler eventSourceHandler;

  AsyncEventHandler(Executor executor, EventHandler eventSourceHandler) {
    this.executor = executor;
    this.eventSourceHandler = eventSourceHandler;
  }

  public void onOpen() {
    executor.execute(() -> {
      try {
        eventSourceHandler.onOpen();
      } catch (Exception e) {
        onError(e);
      }
    });
  }

  public void onClosed() {
    executor.execute(() -> {
      try {
        eventSourceHandler.onClosed();
      } catch (Exception e) {
        onError(e);
      }
    });
  }

  public void onComment(final String comment) {
    executor.execute(() -> {
      try {
        eventSourceHandler.onComment(comment);
      } catch (Exception e) {
        onError(e);
      }
    });
  }

  public void onMessage(final String event, final MessageEvent messageEvent) {
    executor.execute(() -> {
      try {
        eventSourceHandler.onMessage(event, messageEvent);
      } catch (Exception e) {
        onError(e);
      }
    });
  }

  public void onError(final Throwable error) {
    executor.execute(() -> {
      try {
        eventSourceHandler.onError(error);
      } catch (Throwable ignored) {
      }
    });
  }
}