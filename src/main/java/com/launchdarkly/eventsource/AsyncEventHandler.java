package com.launchdarkly.eventsource;


import java.util.concurrent.Executor;

/**
 * Adapted from https://github.com/aslakhellesoy/eventsource-java/blob/master/src/main/java/com/github/eventsource/client/impl/AsyncEventSourceHandler.java
 * <p>
 * We use this in conjunction with a <i>single-threaded</i> executor to ensure that messages are handled
 * on a worker thread in the same order that they were received.
 * <p>
 * This class guarantees that runtime exceptions are never thrown back to the EventSource. 
 */
class AsyncEventHandler implements EventHandler {
  private final Executor executor;
  private final EventHandler eventSourceHandler;
  private final Logger logger;

  AsyncEventHandler(Executor executor, EventHandler eventSourceHandler, Logger logger) {
    this.executor = executor;
    this.eventSourceHandler = eventSourceHandler;
    this.logger = logger;
  }

  public void onOpen() {
    executor.execute(() -> {
      try {
        eventSourceHandler.onOpen();
      } catch (Exception e) {
        handleUnexpectedError(e);
      }
    });
  }

  public void onClosed() {
    executor.execute(() -> {
      try {
        eventSourceHandler.onClosed();
      } catch (Exception e) {
        handleUnexpectedError(e);
      }
    });
  }

  public void onComment(final String comment) {
    executor.execute(() -> {
      try {
        eventSourceHandler.onComment(comment);
      } catch (Exception e) {
        handleUnexpectedError(e);
      }
    });
  }

  public void onMessage(final String event, final MessageEvent messageEvent) {
    executor.execute(() -> {
      try {
        eventSourceHandler.onMessage(event, messageEvent);
      } catch (Exception e) {
        handleUnexpectedError(e);
      }
    });
  }

  public void onError(final Throwable error) {
    executor.execute(() -> {
      onErrorInternal(error);
    });
  }
  
  private void handleUnexpectedError(Throwable error) {
    logger.warn("Caught unexpected error from EventHandler: " + error.toString());
    logger.debug("Stack trace: {}", new LazyStackTrace(error));
    onErrorInternal(error);
  }
  
  private void onErrorInternal(Throwable error) {
    try {
      eventSourceHandler.onError(error);
    } catch (Throwable errorFromErrorHandler) {
      logger.warn("Caught unexpected error from EventHandler.onError(): " + errorFromErrorHandler.toString());
      logger.debug("Stack trace: {}", new LazyStackTrace(error));
    }
  }
}