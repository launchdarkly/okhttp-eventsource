package com.launchdarkly.eventsource.background;

import com.launchdarkly.eventsource.CommentEvent;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.FaultEvent;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.StartedEvent;
import com.launchdarkly.eventsource.StreamClosedByCallerException;
import com.launchdarkly.eventsource.StreamEvent;
import com.launchdarkly.eventsource.StreamException;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;

import java.io.Closeable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A wrapper for {@link EventSource} that reads the stream on a worker thread,
 * pushing events to a handler that the caller provides.
 * <p>
 * A BackgroundEventSource instance manages two worker threads: one to start the
 * stream and request events from it, and another to call handler methods. These are
 * decoupled so that if a handler method is slow to return, it does not completely
 * block reading of the stream.
 * <p>
 * This is the same asynchronous model that was used by EventSource prior to the
 * 4.0.0 release. Code that was written against earlier versions of EventSource
 * can be adapted to use BackgroundEventSource as follows:
 * <pre><code>
 *     // before (version 3.x)
 *     EventHandler myHandler = new EventHandler() {
 *       public void onMessage(String event, MessageEvent messageEvent) {
 *         // ...
 *       }
 *     };
 *     EventSource es = new EventSource.Builder(uri, myHandler)
 *       .connectTimeout(5, TimeUnit.SECONDS)
 *       .threadPriority(Thread.MAX_PRIORITY)
 *       .build();
 *     es.start();
 *     
 *     // after (version 4.x)
 *     BackgroundEventHandler myHandler = new BackgroundEventHandler() {
 *       public void onMessage(String event, MessageEvent messageEvent) {
 *         // ... these methods are the same as for EventHandler before
 *       }
 *     };
 *     BackgroundEventSource bes = new BackgroundEventSource.Builder(myHandler,
 *         new EventSource.Builder(
 *           ConnectStrategy.http()
 *             .connectTimeout(5, TimeUnit.SECONDS)
 *             // connectTimeout and other HTTP options are now set through
 *             // HttpConnectStrategy
 *           )
 *       )
 *       .threadPriority(Thread.MAX_PRIORITY)
 *         // threadPriority, and other options related to worker threads,
 *         // are now properties of BackgroundEventSource
 *       .build();
 *     bes.start();
 * </code></pre>
 * 
 * @since 4.0.0
 */
public class BackgroundEventSource implements Closeable {
  /**
   * Default value for {@link Builder#threadBaseName(String)}.
   */
  public static final String DEFAULT_THREAD_BASE_NAME = "EventSource";
  
  private final EventSource eventSource;
  private final BackgroundEventHandler handler;
  private final ConnectionErrorHandler connectionErrorHandler;
  private final Executor streamExecutor;
  private final Executor eventsExecutor;
  private final boolean shouldCloseStreamExecutor;
  private final boolean shouldCloseEventsExecutor;
  private final Semaphore eventThreadSemaphore;
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final LDLogger logger;

  BackgroundEventSource(Builder builder) {
    this.eventSource = builder.eventSourceBuilder.build();
    this.handler = builder.handler;
    this.connectionErrorHandler = builder.connectionErrorHandler;

    if (builder.eventsExecutor == null) {
      this.eventsExecutor = Executors.newSingleThreadExecutor(
          makeSimpleDaemonThreadFactory("okhttp-eventsource-events", builder.threadBaseName,
              builder.threadPriority));
      this.shouldCloseEventsExecutor = true;
    } else {
      this.eventsExecutor = builder.eventsExecutor;
      this.shouldCloseEventsExecutor = false;
    }

    if (builder.streamExecutor == null) {
      this.streamExecutor = Executors.newSingleThreadExecutor(
          makeSimpleDaemonThreadFactory("okhttp-eventsource-stream", builder.threadBaseName,
              builder.threadPriority));
      this.shouldCloseStreamExecutor = true;
    } else {
      this.streamExecutor = builder.streamExecutor;
      this.shouldCloseStreamExecutor = false;
    }

    if (builder.maxEventTasksInFlight > 0) {
      eventThreadSemaphore = new Semaphore(builder.maxEventTasksInFlight);
    } else {
      eventThreadSemaphore = null;
    }
    logger = eventSource.getLogger();
  }
  
  /**
   * Starts the worker thread to consume the stream and dispatch events. This also starts
   * the underlying {@link EventSource} if it has not already been started.
   * <p>
   * This method has no effect if the BackgroundEventSource has already been started, or
   * if {@link #close()} has been called.
   */
  public void start() {
    synchronized (this) {
      if (closed.get() || started.get()) {
        return;
      }
      started.set(true);
      streamExecutor.execute(new Runnable() {
        @Override
        public void run() {
          logger.debug("BackgroundEventSource started");
          while (pollAndDispatchEvent()) {}
          // If pollAndDispatchEvent returned false, it's time to shut down. We'll spin a
          // new thread to do the shutdown, so the streamExecutor is not still trying to
          // run this task.
          new Thread(new Runnable() {
            public void run() {
              close();
            }
          }).start();
        }
      });
    }
  }
  
  /**
   * Returns the underlying EventSource.
   * 
   * @return the EventSource
   */
  public EventSource getEventSource() {
    return eventSource;
  }
  
  /**
   * Shuts down the BackgroundEventSource and the underlying EventSource.
   * <p>
   * The BackgroundEventSource cannot be started again after doing this.
   */
  public void close() {
    synchronized (this) {
      if (closed.getAndSet(true)) {
        return;
      }
    }
    logger.debug("BackgroundEventSource stopping");
    eventSource.close();
    if (shouldCloseStreamExecutor && streamExecutor instanceof ExecutorService) {
      ((ExecutorService)streamExecutor).shutdown();
      try {
        ((ExecutorService)streamExecutor).awaitTermination(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {}
    }
    if (shouldCloseEventsExecutor && eventsExecutor instanceof ExecutorService) {
      ((ExecutorService)eventsExecutor).shutdown();
      try {
        ((ExecutorService)eventsExecutor).awaitTermination(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {}
    }
  }
  
  private boolean pollAndDispatchEvent() {
    StreamEvent event;
    try {
      event = eventSource.readAnyEvent();
    } catch (StreamException e) {
      event = new FaultEvent(e);
    }

    if (event instanceof FaultEvent) {
      StreamException e = ((FaultEvent)event).getCause();
      if (e instanceof StreamClosedByCallerException) {
        return false; // don't bother dispatching anything, just quit
      }
      dispatchEvent(event);
      if (connectionErrorHandler != null) {
        if (connectionErrorHandler.onConnectionError(e) ==
            ConnectionErrorHandler.Action.SHUTDOWN) {
          return false;
        }
      }
    } else {
      dispatchEvent(event);
    }
    
    return true;
  }
  
  private void dispatchEvent(StreamEvent event) {
    if (closed.get()) {
      return; // COVERAGE: this condition can't be reproduced in unit tests
    }
    if (eventThreadSemaphore != null) {
      try {
        eventThreadSemaphore.acquire();
      } catch (InterruptedException e) { // COVERAGE: this condition can't be reproduced in unit tests
        throw new RejectedExecutionException("Thread interrupted while waiting for event thread semaphore", e);
      }
    }
    eventsExecutor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          if (event instanceof MessageEvent) {
            MessageEvent me = (MessageEvent)event;
            try {
              handler.onMessage(me.getEventName(), me);
            } finally {
              me.close();
            }
          } else if (event instanceof CommentEvent) {
            CommentEvent ce = (CommentEvent)event;
            handler.onComment(ce.getText());
          } else if (event instanceof StartedEvent) {
            handler.onOpen();
          } else if (event instanceof FaultEvent) {
            FaultEvent se = (FaultEvent)event;
            if (!(se.getCause() instanceof StreamClosedByCallerException)) {
              handler.onError(se.getCause());
            }
            handler.onClosed();
          }
        } catch (Exception e) {
          logger.warn("Caught unexpected error from EventHandler: {}", LogValues.exceptionSummary(e));
          logger.debug(LogValues.exceptionTrace(e));
          try {
            handler.onError(e);
          } catch (Exception ee) {
            logger.warn("Caught unexpected error from EventHandler.onError(): {}", LogValues.exceptionSummary(ee));
            logger.debug(LogValues.exceptionTrace(ee));
          }
        } finally {
          if (eventThreadSemaphore != null) {
            eventThreadSemaphore.release();
          }
        }
      }
    });
  }

  private ThreadFactory makeSimpleDaemonThreadFactory(
      String categoryName,
      String threadBaseName,
      final int threadPriority
      ) {
    final String baseName = categoryName + "[" + threadBaseName + "]";
    final ThreadGroup threadGroup = new ThreadGroup(baseName);
    final AtomicInteger counter = new AtomicInteger(0);
    threadGroup.setDaemon(true);
    return new ThreadFactory() {
      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(threadGroup, r, baseName + "-" + counter.incrementAndGet());
        t.setDaemon(true);
        if (threadPriority > 0) {
          t.setPriority(threadPriority);
        }
        return t;
      }
    };
  }
  
  /**
   * Builder for configuring {@link BackgroundEventSource}.
   */
  public static class Builder {
    private final EventSource.Builder eventSourceBuilder;
    private final BackgroundEventHandler handler;
    private ConnectionErrorHandler connectionErrorHandler;
    private Executor eventsExecutor;
    private int maxEventTasksInFlight;
    private Executor streamExecutor;
    private String threadBaseName;
    private int threadPriority;
    
    /**
     * Creates a builder.
     *
     * @param handler the event handler
     * @param eventSourceBuilder builder for the underlying EventSource
     */
    public Builder(BackgroundEventHandler handler, EventSource.Builder eventSourceBuilder) {
      if (handler == null) {
        throw new IllegalArgumentException("handler cannot be null");
      }
      if (eventSourceBuilder == null) {
        throw new IllegalArgumentException("eventSourceBuilder cannot be null");
      }
      this.eventSourceBuilder = eventSourceBuilder;
      this.handler = handler;
    }
    
    /**
     * Creates a {@link BackgroundEventSource} with this configuration.
     * <p>
     * The stream is not started until you call {@link BackgroundEventSource#start()}.
     *
     * @return the configured {@link BackgroundEventSource}
     */
    public BackgroundEventSource build() {
      return new BackgroundEventSource(this);
    }

    /**
     * Sets the {@link ConnectionErrorHandler} that should process connection errors.
     *
     * @param handler the error handler
     * @return the builder
     */
    public Builder connectionErrorHandler(ConnectionErrorHandler handler) {
      this.connectionErrorHandler = handler;
      return this;
    }

    /**
     * Specifies a custom executor to use for dispatching events.
     * <p>
     * If you do not specify a custom executor, the default is to call
     * {@link Executors#newSingleThreadExecutor(java.util.concurrent.ThreadFactory)}, using
     * a simple thread factory that creates daemon threads whose properties are based on
     * {@link #threadBaseName(String)} and {@link #threadPriority(Integer)}. This executor
     * will be shut down when you close the BackgroundEventSource.
     * <p>
     * If you do specify a custom executor, it will <i>not</i> be shut down when you close
     * the BackgroundEventSource; you are responsible for its lifecycle.
     *
     * @param eventsExecutor an executor, or null to use the default behavior
     * @return the builder
     */
    public Builder eventsExecutor(Executor eventsExecutor) {
      this.eventsExecutor = eventsExecutor;
      return this;
    }
    
    /**
     * Specifies the maximum number of tasks that can be "in-flight" for the thread executing
     * {@link BackgroundEventHandler}. A semaphore will be used to artificially constrain the
     * number of tasks sitting in the queue fronting the event handler thread. When this limit
     * is reached the stream thread will block until the backpressure passes.
     * <p>
     * The default is no maximum.
     *
     * @param maxEventTasksInFlight the maximum number of tasks/messages that can be in-flight
     *   for the {@code BackgorundEventHandler}
     * @return the builder
     */
    public Builder maxEventTasksInFlight(int maxEventTasksInFlight) {
      this.maxEventTasksInFlight = maxEventTasksInFlight;
      return this;
    }

    /**
     * Specifies a custom executor to use for running the stream-reading task.
     * <p>
     * If you do not specify a custom executor, the default is to call
     * {@link Executors#newSingleThreadExecutor(java.util.concurrent.ThreadFactory)}, using
     * a simple thread factory that creates daemon threads whose properties are based on
     * {@link #threadBaseName(String)} and {@link #threadPriority(Integer)}. This executor
     * will be shut down when you close the BackgroundEventSource.
     * <p>
     * If you do specify a custom executor, it will <i>not</i> be shut down when you close
     * the BackgroundEventSource; you are responsible for its lifecycle.
     *
     * @param streamExecutor an executor, or null to use the default behavior
     * @return the builder
     */
    public Builder streamExecutor(Executor streamExecutor) {
      this.streamExecutor = streamExecutor;
      return this;
    }
    
    /**
     * Set the name for this BackgroundEventSource to be used when naming thread pools, when
     * BackgroundEventSource is using its default executors.
     * <p>
     * This is mainly useful when multiple BackgroundEventSource instances exist within the same process.
     * <p>
     * This setting is ignored if you have specified a custom executor with
     * {@link #eventsExecutor(Executor)} or {@link #streamExecutor(Executor)}.
     *
     * @param threadBaseName a string to be used in worker thread names (must not contain spaces)
     * @return the builder
     */
    public Builder threadBaseName(String threadBaseName) {
      this.threadBaseName = threadBaseName == null ? DEFAULT_THREAD_BASE_NAME : threadBaseName;
      return this;
    }
    
    /**
     * Specifies the priority for threads created by BackgroundEventSource using its default
     * executors.
     * <p>
     * If this is left unset, or set to {@code null}, threads will inherit the default priority
     * provided by {@code Executors.defaultThreadFactory()}.
     * <p>
     * This setting is ignored if you have specified a custom executor with
     * {@link #eventsExecutor(Executor)} or {@link #streamExecutor(Executor)}.
     *
     * @param threadPriority the thread priority, or null to use the default
     * @return the builder
     */
    public Builder threadPriority(Integer threadPriority) {
      this.threadPriority = threadPriority == null ? 0 : threadPriority.intValue();
      return this;
    }
  }
}
