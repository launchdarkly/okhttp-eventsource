package com.launchdarkly.eventsource;


import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

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
  final Semaphore semaphore; // visible for tests

  AsyncEventHandler(Executor executor, EventHandler eventSourceHandler, Logger logger, Semaphore semaphore) {
    this.executor = executor;
    this.eventSourceHandler = eventSourceHandler;
    this.logger = logger;
    this.semaphore = semaphore;
  }

  public void onOpen() {
    acquire();
    executor.execute(() -> {
      try {
        eventSourceHandler.onOpen();
      } catch (Exception e) {
        handleUnexpectedError(e);
      } finally {
        release();
      }
    });
  }

  public void onClosed() {
    acquire();
    executor.execute(() -> {
      try {
        eventSourceHandler.onClosed();
      } catch (Exception e) {
        handleUnexpectedError(e);
      } finally {
        release();
      }
    });
  }

  public void onComment(final String comment) {
    acquire();
    executor.execute(() -> {
      try {
        eventSourceHandler.onComment(comment);
      } catch (Exception e) {
        handleUnexpectedError(e);
      } finally {
        release();
      }
    });
  }

  public void onMessage(final String event, final MessageEvent messageEvent) {
    acquire();
    executor.execute(() -> {
      try {
        eventSourceHandler.onMessage(event, messageEvent);
      } catch (Exception e) {
        handleUnexpectedError(e);
      } finally {
        release();
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

  private boolean acquire() {
    if (semaphore != null) {
      try {
        semaphore.acquire();
      } catch (InterruptedException e) {
        throw new RejectedExecutionException("Thread interrupted while waiting for event thread semaphore", e);
      }
    }
    return true;
  }

  private void release() {
    if (semaphore != null) {
      semaphore.release();
    }
  }
}