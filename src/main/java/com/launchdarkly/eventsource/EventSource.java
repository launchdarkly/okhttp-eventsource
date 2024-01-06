package com.launchdarkly.eventsource;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.launchdarkly.eventsource.Helpers.millisFromTimeUnit;
import static com.launchdarkly.eventsource.ReadyState.RAW;
import static com.launchdarkly.eventsource.ReadyState.SHUTDOWN;

import okhttp3.HttpUrl;

/**
 * The SSE client.
 * <p>
 * By default, EventSource makes HTTP requests using OkHttp, but it can be configured
 * to read from any input stream. See {@link ConnectStrategy} and {@link HttpConnectStrategy}.
 * <p>
 * Instances are always configured and constructed with {@link Builder}. The client is
 * created in an inactive state.
 * <p>
 * The client uses a pull model where the caller starts the EventSource and then requests
 * data from it synchronously on a single thread. The initial connection attempt is made
 * when you call {@link #start()}, or when you first attempt to read an event.
 *
 * To read events from the stream, you can either request them one at a time by calling
 * {@link #readMessage()} or {@link #readAnyEvent()}, or consume them in a loop by calling
 * {@link #messages()} or {@link #anyEvents()}. The "message" methods assume you are only
 * interested in {@link MessageEvent} data, whereas the "anyEvent" methods also provide
 * other kinds of stream information. These are blocking methods with no timeout; if you
 * need a timeout mechanism, consider reading from the stream on a worker thread and
 * using a queue such as {@link BlockingQueue} to consume the messages elsewhere.
 * <p>
 * If, instead of managing your own thread to read from the stream, you would like to have
 * events pushed to you from a worker thread that the library maintains, use
 * {@link com.launchdarkly.eventsource.background.BackgroundEventSource}.
 * <p>
 * Note that although {@code EventSource} is named after the JavaScript API that is described
 * in the SSE specification, its behavior is not necessarily identical to standard web browser
 * implementations of EventSource: by default, it will automatically retry (with a backoff
 * delay) for some error conditions where a browser will not retry, and it also supports
 * request configuration options (such as request headers and method) that the browser
 * EventSource does not support. However, its interpretation of the stream data is fully
 * conformant with the SSE specification, unless you use the opt-in mode
 * {@link Builder#streamEventData(boolean)} which allows for greater efficiency in some use
 * cases but has some behavioral constraints.
 */
public class EventSource implements Closeable {
  private final LDLogger logger;

  /**
   * The default value for {@link Builder#retryDelay(long, TimeUnit)}: 1 second.
   */
  public static final long DEFAULT_RETRY_DELAY_MILLIS = 1000;
  /**
   * The default value for {@link Builder#retryDelayResetThreshold(long, TimeUnit)}: 60 seconds.
   */
  public static final long DEFAULT_RETRY_DELAY_RESET_THRESHOLD_MILLIS = 60000;
  /**
   * The default value for {@link Builder#readBufferSize(int)}.
   */
  public static final int DEFAULT_READ_BUFFER_SIZE = 1000;

  // Note that some fields have package-private visibility for tests.

  private final Object sleepNotifier = new Object();

  // The following final fields are set from the configuration builder.
  private final ConnectStrategy.Client client;
  final int readBufferSize;
  final ErrorStrategy baseErrorStrategy;
  final RetryDelayStrategy baseRetryDelayStrategy;
  final long retryDelayResetThresholdMillis;
  final boolean streamEventData;
  final Set<String> expectFields;

  // The following mutable fields are not volatile because they should only be
  // accessed from the thread that is reading from EventSource.
  private EventParser eventParser;
  ErrorStrategy currentErrorStrategy;
  RetryDelayStrategy currentRetryDelayStrategy;
  private long connectedTime;
  private long disconnectedTime;
  private StreamEvent nextEvent;

  // These fields are set by the thread that is reading the stream, but can
  // be modified from other threads if they call stop() or interrupt(). We
  // use AtomicReference because we need atomicity in updates.
  private final AtomicReference<Closeable> connectionCloser = new AtomicReference<>();
  private final AtomicReference<Closeable> responseCloser = new AtomicReference<>();
  private final AtomicReference<Thread> readingThread = new AtomicReference<>();
  private final AtomicReference<ReadyState> readyState;

  // These fields are written by other threads if they call stop() or interrupt(),
  // and are read by the thread that is reading the stream.
  private volatile boolean deliberatelyClosedConnection;
  private volatile boolean calledStop;

  // These fields are written by the thread that is reading the stream, and can
  // be read by other threads to inspect the state of the stream.
  volatile long baseRetryDelayMillis; // set at config time but may be changed by a "retry:" value
  private volatile String lastEventId;
  private volatile URI origin;
  private volatile long nextReconnectDelayMillis;

  EventSource(Builder builder) {
    this.logger = builder.logger == null ? LDLogger.none() : builder.logger;
    this.client = builder.connectStrategy.createClient(logger);
    this.origin = client.getOrigin();
    this.lastEventId = builder.lastEventId;
    this.baseErrorStrategy = this.currentErrorStrategy =
        builder.errorStrategy == null ? ErrorStrategy.alwaysThrow() : builder.errorStrategy;
    this.baseRetryDelayStrategy = this.currentRetryDelayStrategy =
        (builder.retryDelayStrategy == null ? RetryDelayStrategy.defaultStrategy() :
          builder.retryDelayStrategy);
    this.baseRetryDelayMillis = builder.retryDelayMillis;
    this.retryDelayResetThresholdMillis = builder.retryDelayResetThresholdMillis;
    this.streamEventData = builder.streamEventData;
    this.expectFields = builder.expectFields;
    this.readBufferSize = builder.readBufferSize;
    this.readyState = new AtomicReference<>(RAW);
  }

  /**
   * Returns the stream URI.
   *
   * @return the stream URI
   * @since 4.0.0
   */
  public URI getOrigin() {
    return origin;
  }

  /**
   * Returns the logger that this EventSource is using.
   *
   * @return the logger
   * @see Builder#logger(LDLogger)
   * @since 4.0.0
   */
  public LDLogger getLogger() {
    return logger;
  }

  /**
   * Returns an enum indicating the current status of the connection.
   *
   * @return a {@link ReadyState} value
   */
  public ReadyState getState() {
    return readyState.get();
  }

  /**
   * Returns the ID value, if any, of the last known event.
   * <p>
   * This can be set initially with {@link Builder#lastEventId(String)}, and is updated whenever an event
   * is received that has an ID. Whether event IDs are supported depends on the server; it may ignore this
   * value.
   *
   * @return the last known event ID, or null
   * @see Builder#lastEventId(String)
   * @since 2.0.0
   */
  public String getLastEventId() {
    return lastEventId;
  }

  /**
   * Returns the current base retry delay.
   * <p>
   * This is initially set by {@link Builder#retryDelay(long, TimeUnit)}, or
   * {@link #DEFAULT_RETRY_DELAY_MILLIS} if not specified. It can be overriden by the
   * stream provider if the stream contains a "retry:" line.
   * <p>
   * The actual retry delay for any given reconnection is computed by applying the
   * configured {@link RetryDelayStrategy} to this value.
   *
   * @return the base retry delay in milliseconds
   * @see #getNextRetryDelayMillis()
   * @since 4.0.0
   */
  public long getBaseRetryDelayMillis() {
    return baseRetryDelayMillis;
  }

  /**
   * Returns the retry delay that will be used for the next reconnection, if the
   * stream has failed.
   * <p>
   * If you have just received a {@link StreamException} or {@link FaultEvent}, this
   * value tells you how long EventSource will sleep before reconnecting, if you tell
   * it to reconnect by calling {@link #start()} or by trying to read another event.
   * The value is computed by applying the configured {@link RetryDelayStrategy} to
   * the current value of {@link #getBaseRetryDelayMillis()}.
   * <p>
   * At any other time, the value is undefined.
   *
   * @return the next retry delay in milliseconds
   * @see #getBaseRetryDelayMillis()
   * @since 4.0.0
   */
  public long getNextRetryDelayMillis() {
    return nextReconnectDelayMillis;
  }

  /**
   * Attempts to start the stream if it is not already active.
   * <p>
   * If there is not an active stream connection, this method attempts to start one using
   * the previously configured parameters. If successful, it returns and you can proceed
   * to read events. You should only read events on the same thread where you called
   * {@link #start()}.
   * <p>
   * If the connection fails, the behavior depends on the configured {@link ErrorStrategy}.
   * The default strategy is to throw a {@link StreamException}, but you can configure it
   * to continue instead, in which case {@link #start()} will keep retrying until the
   * ErrorStrategy says to give up.
   * <p>
   * If the stream was previously active and then failed, {@link #start()} will sleep for
   * some amount of time-- the retry delay-- before trying to make the connection. The
   * retry delay is determined by several factors: see {@link Builder#retryDelay(long, TimeUnit)},
   * {@link Builder#retryDelayStrategy(RetryDelayStrategy)}, and
   * {@link Builder#retryDelayResetThreshold(long, TimeUnit)}.
   * @throws StreamException
   * <p>
   * You do not necessarily need to call this method; it is implicitly called if you try
   * to read an event when the stream is not active. Call it only if you specifically want
   * to confirm that the stream is active before you try to read an event.
   * <p>
   * If the stream is already active, calling this method has no effect.
   *
   * @throws StreamException if the connection attempt failed
   */
  public void start() throws StreamException {
    tryStart(false);
  }

  private FaultEvent tryStart(boolean canReturnFaultEvent) throws StreamException {
    if (eventParser != null) {
      return null;
    }
    readingThread.set(Thread.currentThread());

    while (true) {
      StreamException exception = null;

      if (nextReconnectDelayMillis > 0) {
        long delayNow = disconnectedTime == 0 ? nextReconnectDelayMillis :
          (nextReconnectDelayMillis - (System.currentTimeMillis() - disconnectedTime));
        if (delayNow > 0) {
          logger.info("Waiting {} milliseconds before reconnecting", delayNow);
          try {
            synchronized (sleepNotifier) {
              if (!deliberatelyClosedConnection) {
                sleepNotifier.wait(delayNow);
              }
              // If interrupt(), stop(), or close() is called while we're waiting, we will
              // trigger an early exit from this wait by calling sleepNotifier.notify().
            }
          } catch (InterruptedException e) {
            // Thread.interrupt() should also have the effect of making us stop waiting
            logger.debug("EventSource thread was interrupted during start()");
            deliberatelyClosedConnection = true;
            Thread.interrupted(); // clear interrupted state
          }
          // Check if deliberatelyClosedConnection might have been set during that wait
          if (deliberatelyClosedConnection) {
            exception = new StreamClosedByCallerException();
          }
        }
      }

      ConnectStrategy.Client.Result clientResult = null;

      if (exception == null) {
        readyState.set(ReadyState.CONNECTING);

        connectedTime = 0;
        deliberatelyClosedConnection = calledStop = false;

        try {
          clientResult = client.connect(lastEventId);
        } catch (StreamException e) {
          exception = e;
        }
      }

      if (exception != null) {
        disconnectedTime = System.currentTimeMillis();
        computeReconnectDelay();
        if (applyErrorStrategy(exception) == ErrorStrategy.Action.CONTINUE) {
          // The ErrorStrategy told us to CONTINUE rather than throwing an exception.
          if (canReturnFaultEvent) {
            return new FaultEvent(exception);
          }
          // If canReturnFaultEvent is false, it means the caller explicitly called start(),
          // in which case there's no way to return a FaultEvent so we just keep retrying
          // transparently.
          continue;
        }
        // The ErrorStrategy told us to THROW rather than CONTINUE.
        throw exception;
      }


      connectionCloser.set(clientResult.getConnectionCloser());
      responseCloser.set(clientResult.getResponseCloser());

      origin = clientResult.getOrigin() == null ? client.getOrigin() : clientResult.getOrigin();
      connectedTime = System.currentTimeMillis();
      logger.debug("Connected to SSE stream");

      eventParser = new EventParser(
          clientResult.getInputStream(),
          clientResult.getOrigin(),
          readBufferSize,
          streamEventData,
          expectFields,
          logger
          );

      readyState.set(ReadyState.OPEN);

      currentErrorStrategy = baseErrorStrategy;
      return null;
    }
  }

  /**
   * Attempts to receive a message from the stream.
   * <p>
   * If the stream is not already active, this calls {@link #start()} to establish
   * a connection.
   * <p>
   * As long as the stream is active, the method blocks until a message is available.
   * If the stream fails, the default behavior is to throw a {@link StreamException},
   * but you can configure an {@link ErrorStrategy} to allow the client to retry
   * transparently instead.
   * <p>
   * This method must be called from the same thread that first started using the
   * stream (that is, the thread that called {@link #start()} or read the first event).
   *
   * @return an SSE message
   * @throws StreamException if there is an error and retry is not enabled
   * @see #readAnyEvent()
   * @see #messages()
   * @since 4.0.0
   */
  public MessageEvent readMessage() throws StreamException {
    while (true) {
      StreamEvent event = readAnyEvent();
      if (event instanceof MessageEvent) {
        return (MessageEvent)event;
      }
    }
  }

  /**
   * Attempts to receive an event of any kind from the stream.
   * <p>
   * This is similar to {@link #readMessage()}, except that instead of specifically
   * requesting a {@link MessageEvent} it also applies to the other subclasses of
   * {@link StreamEvent}: {@link StartedEvent}, {@link FaultEvent}, and {@link
   * CommentEvent}. Use this method if you want to be informed of any of those
   * occurrences.
   * <p>
   * The error behavior is the same as {@link #readMessage()}, except that if the
   * {@link ErrorStrategy} is configured to let the client continue with an
   * automatic retry, you will receive a {@link FaultEvent} describing the error
   * first, and then a {@link StartedEvent} once the stream is reconnected.
   * <p>
   * This method must be called from the same thread that first started using the
   * stream (that is, the thread that called {@link #start()} or read the first event).
   *
   * @return an event
   * @throws StreamException if there is an error and retry is not enabled
   * @see #readMessage()
   * @see #anyEvents()
   * @since 4.0.0
   */
  public StreamEvent readAnyEvent() throws StreamException {
    return requireEvent();
  }

  /**
   * Returns an iterable sequence of SSE messages.
   * <p>
   * This is similar to calling {@link #readMessage()} in a loop. If the stream
   * has not already been started, it also starts the stream.
   * <p>
   * The error behavior is different from {@link #readMessage()}: if an error occurs
   * and the {@link ErrorStrategy} does not allow the client to continue, it simply
   * stops iterating, rather than throwing an exception. If you need to be able to
   * specifically detect errors, use {@link #readMessage()}.
   * <p>
   * This method must be called from the same thread that first started using the
   * stream (that is, the thread that called {@link #start()} or read the first event).
   *
   * @return a sequence of SSE messages
   * @see #readAnyEvent()
   * @see #messages()
   * @since 4.0.0
   */
  public Iterable<MessageEvent> messages() {
    return new Iterable<MessageEvent>() {
      @Override
      public Iterator<MessageEvent> iterator() {
        return new IteratorImpl<>(MessageEvent.class);
      }
    };
  }

  /**
   * Returns an iterable sequence of events.
   * <p>
   * This is similar to calling {@link #readAnyEvent()} in a loop. If the stream
   * has not already been started, it also starts the stream.
   * <p>
   * The error behavior is different from {@link #readAnyEvent()}: if an error occurs
   * and the {@link ErrorStrategy} does not allow the client to continue, it simply
   * stops iterating, rather than throwing an exception. If you need to be able to
   * specifically detect errors, use {@link #readAnyEvent()} (or, use the
   * {@link ErrorStrategy} mechanism to cause errors to be reported as
   * {@link FaultEvent}s).
   * <p>
   * This method must be called from the same thread that first started using the
   * stream (that is, the thread that called {@link #start()} or read the first event).
   *
   * @return a sequence of events
   * @see #readAnyEvent()
   * @see #messages()
   * @since 4.0.0
   */
  public Iterable<StreamEvent> anyEvents() {
    return new Iterable<StreamEvent>() {
      @Override
      public Iterator<StreamEvent> iterator() {
        return new IteratorImpl<>(StreamEvent.class);
      }
    };
  }

  /**
   * Stops the stream connection if it is currently active.
   * <p>
   * Unlike the reading methods, you are allowed to call this method from any
   * thread. If you are reading events on a different thread, and automatic
   * retries are not enabled by an {@link ErrorStrategy}, the other thread will
   * receive a {@link StreamClosedByCallerException}.
   * <p>
   * The difference between this method and {@link #stop()} is only relevant if
   * automatic retries are enabled. In this case, if you are using the
   * {@link #messages()} or {@link #anyEvents()} iterator to read events, calling
   * {@link #interrupt()} will cause the stream to be closed and then immediately
   * reconnected, whereas {@link #stop()} will close it and then the iterator
   * will end. In either case, if you explicitly try to read another event it
   * will start the stream again.
   * <p>
   * If the stream is not currently active, calling this method has no effect.
   * <p>
   * Note for Android developers: since it is generally undesirable to perform
   * any network activity from the main thread, be aware that {@link #interrupt()},
   * {@link #stop()}, and {@link #close()} all cause an immediate close of the
   * connection (if any), which happens on the same thread that called the method.
   *
   * @since 4.0.0
   * @see #stop()
   * @see #start()
   */
  public void interrupt() {
    closeCurrentStream(true, false);
  }

  /**
   * Stops the stream connection if it is currently active.
   * <p>
   * Unlike the reading methods, you are allowed to call this method from any
   * thread. If you are reading events on a different thread, and automatic
   * retries are not enabled by an {@link ErrorStrategy}, the other thread will
   * receive a {@link StreamClosedByCallerException}.
   * <p>
   * The difference between this method and {@link #interrupt()} is only relevant if
   * automatic retries are enabled. In this case, if you are using the
   * {@link #messages()} or {@link #anyEvents()} iterator to read events, calling
   * {@link #interrupt()} will cause the stream to be closed and then immediately
   * reconnected, whereas {@link #stop()} will close it and then the iterator
   * will end. In either case, if you explicitly try to read another event it
   * will start the stream again.
   * <p>
   * If the stream is not currently active, calling this method has no effect.
   * <p>
   * Note for Android developers: since it is generally undesirable to perform
   * any network activity from the main thread, be aware that {@link #interrupt()},
   * {@link #stop()}, and {@link #close()} all cause an immediate close of the
   * connection (if any), which happens on the same thread that called the method.
   *
   * @since 4.0.0
   * @see #interrupt()
   * @see #start()
   */
  public void stop() {
    closeCurrentStream(true, true);
  }

  /**
   * Permanently shuts down the EventSource.
   * <p>
   * This is similar to {@link #stop()} except that it also releases any resources that
   * the EventSource was maintaining in general, such as an HTTP connection pool. Do
   * not try to use the EventSource after closing it.
   * <p>
   * Note for Android developers: since it is generally undesirable to perform
   * any network activity from the main thread, be aware that {@link #interrupt()},
   * {@link #stop()}, and {@link #close()} all cause an immediate close of the
   * connection (if any), which happens on the same thread that called the method.
   */
  @Override
  public void close() {
    ReadyState currentState = readyState.getAndSet(SHUTDOWN);
    if (currentState == SHUTDOWN) {
      return;
    }

    closeCurrentStream(true, true);
    try {
      client.close();
    } catch (IOException e) {}
  }

  /**
   * Blocks until all underlying threads have terminated and resources have been released.
   *
   * @param timeout maximum time to wait for everything to shut down, in whatever time
   *   unit is specified by {@code timeUnit}
   * @param timeUnit the time unit, or {@code TimeUnit.MILLISECONDS} if null
   * @return {@code true} if all thread pools terminated within the specified timeout,
   *   {@code false} otherwise
   * @throws InterruptedException if this thread is interrupted while blocking
   */
  public boolean awaitClosed(long timeout, TimeUnit timeUnit) throws InterruptedException {
    return client.awaitClosed(millisFromTimeUnit(timeout, timeUnit));
  }

  // Iterator implementation used by messages() and anyEvents()
  private class IteratorImpl<T extends StreamEvent> implements Iterator<T> {
    private final Class<T> filterClass;

    IteratorImpl(Class<T> filterClass) {
      this.filterClass = filterClass;
      calledStop = false;
    }

    public boolean hasNext() {
      while (true) {
        if (nextEvent != null && filterClass.isAssignableFrom(nextEvent.getClass())) {
          return true;
        }
        if (calledStop) {
          calledStop = false;
          return false;
        }
        try {
          nextEvent = requireEvent();
        } catch (StreamException e) {
          return false;
        }
      }
    }

    public T next() {
      while (nextEvent == null || !filterClass.isAssignableFrom(nextEvent.getClass()) && hasNext()) {}
      @SuppressWarnings("unchecked")
      T event = (T)nextEvent;
      nextEvent = null;
      return event;
    }
  }

  private StreamEvent requireEvent() throws StreamException {
    readingThread.set(Thread.currentThread());

    try {
      while (true) {
        // Reading an event implies starting the stream if it isn't already started.
        // We might also be restarting since we could have been interrupted at any time.
        if (eventParser == null) {
          FaultEvent faultEvent = tryStart(true);
          return faultEvent == null ? new StartedEvent() : faultEvent;
        }
        StreamEvent event = eventParser.nextEvent();
        if (event instanceof SetRetryDelayEvent) {
          // SetRetryDelayEvent means the stream contained a "retry:" line. We don't
          // surface this to the caller, we just apply the new delay and move on.
          baseRetryDelayMillis = ((SetRetryDelayEvent)event).getRetryMillis();
          resetRetryDelayStrategy();
          continue;
        }
        if (event instanceof MessageEvent) {
          MessageEvent me = (MessageEvent)event;
          if (me.getLastEventId() != null) {
            lastEventId = me.getLastEventId();
          }
        }
        return event;
      }
    } catch (StreamException e) {
      readyState.set(ReadyState.CLOSED);
      if (deliberatelyClosedConnection) {
        // If the stream was explicitly closed from another thread, that'll likely show up as
        // an I/O error, but we don't want to report it as one.
        e = new StreamClosedByCallerException();
        deliberatelyClosedConnection = false;
      }
      disconnectedTime = System.currentTimeMillis();
      closeCurrentStream(false, false);
      eventParser = null;
      computeReconnectDelay();
      if (applyErrorStrategy(e) == ErrorStrategy.Action.CONTINUE) {
        return new FaultEvent(e);
      }
      throw e;
    }
  }

  private void resetRetryDelayStrategy() {
    logger.debug("Resetting retry delay strategy to initial state");
    currentRetryDelayStrategy = baseRetryDelayStrategy;
  }

  private ErrorStrategy.Action applyErrorStrategy(StreamException e) {
    ErrorStrategy.Result errorStrategyResult = currentErrorStrategy.apply(e);
    if (errorStrategyResult.getNext() != null) {
      currentErrorStrategy = errorStrategyResult.getNext();
    }
    return errorStrategyResult.getAction();
  }

  private void computeReconnectDelay() {
    if (retryDelayResetThresholdMillis > 0 && connectedTime != 0) {
      long connectionDurationMillis = System.currentTimeMillis() - connectedTime;
      if (connectionDurationMillis >= retryDelayResetThresholdMillis) {
        resetRetryDelayStrategy();
      }
    }
    RetryDelayStrategy.Result result =
        currentRetryDelayStrategy.apply(baseRetryDelayMillis);
    nextReconnectDelayMillis = result.getDelayMillis();
    if (result.getNext() != null) {
      currentRetryDelayStrategy = result.getNext();
    }
  }

  private boolean closeCurrentStream(boolean deliberatelyInterrupted, boolean shouldStopIterating) {
    Closeable oldConnectionCloser = this.connectionCloser.getAndSet(null);

    Thread oldReadingThread = readingThread.getAndSet(null);
    if (oldConnectionCloser == null && oldReadingThread == null) {
      return false;
    }

    synchronized (sleepNotifier) { // this synchronization prevents a race condition in start()
      if (deliberatelyInterrupted) {
        this.deliberatelyClosedConnection = true;
      }
      if (shouldStopIterating) {
        this.calledStop = true;
      }
      if (oldConnectionCloser != null) {
        try {
          oldConnectionCloser.close();
          logger.debug("Closed request");
        } catch (IOException e) {
          logger.warn("Unexpected error when closing connection: {}", LogValues.exceptionSummary(e));
        }
      }
      if (oldReadingThread == Thread.currentThread()) {
        Closeable oldResponseCloser = this.responseCloser.getAndSet(null);
        eventParser = null;
        // Response can only be closed from reading thread. Otherwise, it will cause
        // java.lang.IllegalStateException: Unbalanced enter/exit raised from okhttp
        // since closing response will drain remaining chunks if exists, resulting in concurrent buffer source reading.
        // which may conflict with reading thread.
        if (oldResponseCloser != null) {
          try {
            oldResponseCloser.close();
            logger.debug("Closed response");
          } catch (IOException e) {
            logger.warn("Unexpected error when closing response: {}", LogValues.exceptionSummary(e));
          }
        }
        readyState.compareAndSet(ReadyState.OPEN, ReadyState.CLOSED);
        readyState.compareAndSet(ReadyState.CONNECTING, ReadyState.CLOSED);
        // If the current thread is not the reading thread, these fields will be updated the
        // next time the reading thread tries to do a read.
      }

      sleepNotifier.notify(); // in case we're sleeping in a reconnect delay, wake us up
    }
    return true;
  }

  /**
   * Builder for configuring {@link EventSource}.
   */
  public static final class Builder {
    private final ConnectStrategy connectStrategy; // final because it's mandatory, set at constructor time
    private ErrorStrategy errorStrategy;
    private RetryDelayStrategy retryDelayStrategy;
    private long retryDelayMillis = DEFAULT_RETRY_DELAY_MILLIS;
    private long retryDelayResetThresholdMillis = DEFAULT_RETRY_DELAY_RESET_THRESHOLD_MILLIS;
    private String lastEventId;
    private int readBufferSize = DEFAULT_READ_BUFFER_SIZE;
    private LDLogger logger = null;
    private boolean streamEventData;
    private Set<String> expectFields = null;

    /**
     * Creates a new builder, specifying how it will connect to a stream.
     * <p>
     * The {@link ConnectStrategy} will handle all details of how to obtain an
     * input stream for the EventSource to consume. By default, this is
     * {@link HttpConnectStrategy}, which makes HTTP requests. To customize the
     * HTTP behavior, you can use methods of {@link HttpConnectStrategy}:
     * <pre><code>
     *     EventSource.Builder builder = new EventSource.Builder(
     *       ConnectStrategy.http(myStreamUri)
     *         .headers(myCustomHeaders)
     *         .connectTimeout(10, TimeUnit.SECONDS)
     *     );
     * </code></pre>
     * <p>
     * Or, if you want to consume an input stream from some other source, you can
     * create your own subclass of {@link ConnectStrategy}.
     *
     * @param connectStrategy the object that will manage the input stream;
     *   must not be null
     * @since 4.0.0
     * @see #Builder(URI)
     * @see #Builder(HttpUrl)
     * @throws IllegalArgumentException if the argument is null
     */
    public Builder(ConnectStrategy connectStrategy) {
      if (connectStrategy == null) {
        throw new IllegalArgumentException("connectStrategy must not be null");
      }
      this.connectStrategy = connectStrategy;
    }

    /**
     * Creates a new builder that connects via HTTP, specifying only the stream URI.
     * <p>
     * Use this method if you do not need to configure any HTTP-related properties
     * besides the URI. To specify a custom HTTP configuration instead, use
     * {@link #Builder(ConnectStrategy)} with {@link ConnectStrategy#http(URI)}.
     *
     * @param uri the stream URI
     * @throws IllegalArgumentException if the argument is null, or if the endpoint
     *   is not HTTP or HTTPS
     * @see #Builder(ConnectStrategy)
     * @see #Builder(URL)
     * @see #Builder(HttpUrl)
     */
    public Builder(URI uri) {
      this(ConnectStrategy.http(uri));
    }

    /**
     * Creates a new builder that connects via HTTP, specifying only the stream URI.
     * <p>
     * This is the same as {@link #Builder(URI)}, but using the {@link URL} type.
     *
     * @param url the stream URL
     * @throws IllegalArgumentException if the argument is null, or if the endpoint
     *   is not HTTP or HTTPS
     * @see #Builder(ConnectStrategy)
     * @see #Builder(URI)
     * @see #Builder(HttpUrl)
     */
    public Builder(URL url) {
      this(ConnectStrategy.http(url));
    }

    /**
     * Creates a new builder that connects via HTTP, specifying only the stream URI.
     * <p>
     * This is the same as {@link #Builder(URI)}, but using the OkHttp type
     * {@link HttpUrl}.
     *
     * @param url the stream URL
     * @throws IllegalArgumentException if the argument is null, or if the endpoint
     *   is not HTTP or HTTPS
     *
     * @since 1.9.0
     * @see #Builder(ConnectStrategy)
     * @see #Builder(URI)
     * @see #Builder(URL)
     */
    public Builder(HttpUrl url) {
      this(ConnectStrategy.http(url));
    }

    /**
     * Specifies a strategy for determining whether to handle errors transparently
     * or throw them as exceptions.
     * <p>
     * By default, any failed connection attempt, or failure of an existing connection,
     * will be thrown as a {@link StreamException} when you try to use the stream. You
     * may instead use alternate {@link ErrorStrategy} implementations, such as
     * {@link ErrorStrategy#alwaysContinue()}, or a custom implementation, to allow
     * EventSource to continue after an error.
     *
     * @param errorStrategy the object that will control error handling; if null,
     *   defaults to {@link ErrorStrategy#alwaysThrow()}
     * @return the builder
     * @since 4.0.0
     */
    public Builder errorStrategy(ErrorStrategy errorStrategy) {
      this.errorStrategy = errorStrategy;
      return this;
    }

    /**
     * Sets the ID value of the last event received.
     * <p>
     * This will be sent to the remote server on the initial connection request, allowing the server to
     * skip past previously sent events if it supports this behavior. Once the connection is established,
     * this value will be updated whenever an event is received that has an ID. Whether event IDs are
     * supported depends on the server; it may ignore this value.
     *
     * @param lastEventId the last event identifier
     * @return the builder
     * @since 2.0.0
     */
    public Builder lastEventId(String lastEventId) {
      this.lastEventId = lastEventId;
      return this;
    }

    /**
     * Sets the base delay between connection attempts.
     * <p>
     * The actual delay may be slightly less or greater, depending on the strategy specified by
     * {@link #retryDelayStrategy(RetryDelayStrategy)}. The default behavior is to increase the
     * delay exponentially from this base value on each attempt, up to a configured maximum,
     * substracting a random jitter; for more details, see {@link DefaultRetryDelayStrategy}.
     * <p>
     * If you set the base delay to zero, the backoff logic will not apply-- multiplying by
     * zero gives zero every time. Therefore, use a zero delay with caution since it could
     * cause a reconnect storm during a service interruption.
     *
     * @param retryDelay the base delay, in whatever time unit is specified by {@code timeUnit}
     * @param timeUnit the time unit, or {@code TimeUnit.MILLISECONDS} if null
     * @return the builder
     * @see EventSource#DEFAULT_RETRY_DELAY_MILLIS
     * @see #retryDelayStrategy(RetryDelayStrategy)
     * @see #retryDelayResetThreshold(long, TimeUnit)
     */
    public Builder retryDelay(long retryDelay, TimeUnit timeUnit) {
      retryDelayMillis = millisFromTimeUnit(retryDelay, timeUnit);
      return this;
    }

    /**
     * Specifies a strategy for determining the retry delay after an error.
     * <p>
     * Whenever EventSource tries to start a new connection after a stream failure,
     * it delays for an amount of time that is determined by two parameters: the
     * base retry delay ({@link #retryDelay(long, TimeUnit)}), and the retry delay
     * strategy which transforms the base retry delay in some way. The default behavior
     * is to apply an exponential backoff and jitter. You may instead use a modified
     * version of {@link DefaultRetryDelayStrategy} to customize the backoff and
     * jitter, or a custom implementation with any other logic.
     *
     * @param retryDelayStrategy the object that will control retry delays; if null,
     *   defaults to {@link RetryDelayStrategy#defaultStrategy()}
     * @return the builder
     * @see #retryDelay(long, TimeUnit)
     * @see #retryDelayResetThreshold(long, TimeUnit)
     * @since 4.0.0
     */
    public Builder retryDelayStrategy(RetryDelayStrategy retryDelayStrategy) {
      this.retryDelayStrategy = retryDelayStrategy;
      return this;
    }

    /**
     * Sets the minimum amount of time that a connection must stay open before the EventSource resets
     * its delay strategy.
     * <p>
     * When using the default strategy ({@link RetryDelayStrategy#defaultStrategy()}), this means that
     * the delay before each reconnect attempt will be greater than the last delay unless the current
     * connection lasted longer than the threshold, in which case the delay will start over at the
     * initial minimum value. This prevents long delays from occurring on connections that are only
     * rarely restarted.
     *
     * @param retryDelayResetThreshold the minimum time that a connection must stay open to avoid resetting
     *   the delay, in whatever time unit is specified by {@code timeUnit}
     * @param timeUnit the time unit, or {@code TimeUnit.MILLISECONDS} if null
     * @return the builder
     * @see EventSource#DEFAULT_RETRY_DELAY_RESET_THRESHOLD_MILLIS
     * @since 4.0.0
     */
    public Builder retryDelayResetThreshold(long retryDelayResetThreshold, TimeUnit timeUnit) {
      this.retryDelayResetThresholdMillis = millisFromTimeUnit(retryDelayResetThreshold, timeUnit);
      return this;
    }

    /**
     * Specifies the fixed size of the buffer that EventSource uses to parse incoming data.
     * <p>
     * EventSource allocates a single buffer to hold data from the stream as it scans for
     * line breaks. If no lines of data from the stream exceed this size, it will keep reusing
     * the same space; if a line is longer than this size, it creates a temporary
     * {@code ByteArrayOutputStream} to accumulate data for that line, which is less efficient.
     * Therefore, if an application expects to see many lines in the stream that are longer
     * than {@link EventSource#DEFAULT_READ_BUFFER_SIZE}, it can specify a larger buffer size
     * to avoid unnecessary heap allocations.
     *
     * @param readBufferSize the buffer size
     * @return the builder
     * @throws IllegalArgumentException if the size is less than or equal to zero
     * @see EventSource#DEFAULT_READ_BUFFER_SIZE
     * @since 2.4.0
     */
    public Builder readBufferSize(int readBufferSize) {
      if (readBufferSize <= 0) {
        throw new IllegalArgumentException("readBufferSize must be greater than zero");
      }
      this.readBufferSize = readBufferSize;
      return this;
    }

    /**
     * Specifies a custom logger to receive EventSource logging.
     * <p>
     * This method uses the {@link LDLogger} type from
     * <a href="https://github.com/launchdarkly/java-logging">com.launchdarkly.logging</a>, a
     * facade that provides several logging implementations as well as the option to forward
     * log output to SLF4J or another framework. Here is an example of configuring it to use
     * the basic console logging implementation, and to tag the output with the name "logname":
     * <pre><code>
     *   // import com.launchdarkly.logging.*;
     *
     *   builder.logger(
     *      LDLogger.withAdapter(Logs.basic(), "logname")
     *   );
     * </code></pre>
     * <p>
     * If you do not provide a logger, the default is there is no log output.
     *
     * @param logger an {@link LDLogger} implementation, or null for no logging
     * @return the builder
     * @since 2.7.0
     */
    public Builder logger(LDLogger logger) {
      this.logger = logger;
      return this;
    }

    /**
     * Specifies whether EventSource should send a {@link MessageEvent} to the handler as soon as it receives the
     * beginning of the event data, allowing the handler to read the data incrementally with
     * {@link MessageEvent#getDataReader()}.
     * <p>
     * The default for this property is {@code false}, meaning that EventSource will always read the entire event into
     * memory before dispatching it to the handler.
     * <p>
     * If you set it to {@code true}, it will instead call the handler as soon as it sees a {@code data} field--
     * setting {@link MessageEvent#getDataReader()} to a {@link java.io.Reader} that reads directly from the data as
     * it arrives. The EventSource will perform any necessary parsing under the covers, so that for instance if there
     * are multiple {@code data:} lines in the event, the Reader will emit a newline character between
     * each and will not see the "data:" field names. The Reader will report "end of stream" as soon
     * as the event is terminated normally by a blank line. If the stream is closed before normal termination of
     * the event, the Reader will throw a {@link StreamClosedWithIncompleteMessageException}.
     * <p>
     * This mode is designed for applications that expect very large data items to be delivered over SSE. Use it
     * with caution, since there are several limitations:
     * <ul>
     * <li> The {@link MessageEvent} is constructed as soon as a {@code data:} field appears, so it will only include
     * fields that appeared <i>before</i> {@code data:}. In other words, if the SSE server happens to send {@code data:}
     * first and {@code event:} second, {@link MessageEvent#getEventName()} will <i>not</i> contain the value of
     * {@code event:} but will be {@link MessageEvent#DEFAULT_EVENT_NAME} instead; similarly, an {@code id:} field will
     * be ignored if it appears after {@code data:} in this mode. Therefore, you should only use this mode if the
     * server's behavior is predictable in this regard.</li>
     * <li> The SSE protocol specifies that an event should be processed only if it is terminated by a blank line, but
     * in this mode the handler will receive the event as soon as a {@code data:} field appears-- so, if the stream
     * happens to cut off abnormally without a trailing blank line, technically you will be receiving an incomplete
     * event that should have been ignored. You will know this has happened ifbecause reading from the Reader throws
     * a {@link StreamClosedWithIncompleteMessageException}.</li>
     * </ul>
     *
     * @param streamEventData true if events should be dispatched immediately with asynchronous data rather than
     *   read fully before dispatch
     * @return the builder
     * @see #expectFields(String...)
     * @since 2.6.0
     */
    public Builder streamEventData(boolean streamEventData) {
      this.streamEventData = streamEventData;
      return this;
    }

    /**
     * Specifies that the application expects the server to send certain fields in every event.
     * <p>
     * This setting makes no difference unless you have enabled {@link #streamEventData(boolean)} mode. In that case,
     * it causes EventSource to only use the streaming data mode for an event <i>if</i> the specified fields have
     * already been received; otherwise, it will buffer the whole event (as if {@link #streamEventData(boolean)} had
     * not been enabled), to ensure that those fields are not lost if they appear after the {@code data:} field.
     * <p>
     * For instance, if you had called {@code expectFields("event")}, then EventSource would be able to use streaming
     * data mode for the following SSE response--
     * <pre><code>
     *     event: hello
     *     data: here is some very long streaming data
     * </code></pre>
     * <p>
     * --but it would buffer the full event if the server used the opposite order:
     * <pre><code>
     *     data: here is some very long streaming data
     *     event: hello
     * </code></pre>
     * <p>
     * Such behavior is not automatic because in some applications, there might never be an {@code event:} field,
     * and EventSource has no way to anticipate this.
     *
     * @param fieldNames a list of SSE field names (case-sensitive; any names other than "event" and "id" are ignored)
     * @return the builder
     * @see #streamEventData(boolean)
     * @since 2.6.0
     */
    public Builder expectFields(String... fieldNames) {
      if (fieldNames == null || fieldNames.length == 0) {
        expectFields = null;
      } else {
        expectFields = new HashSet<>();
        for (String f: fieldNames) {
          if (f != null) {
            expectFields.add(f);
          }
        }
      }
      return this;
    }

    /**
     * Constructs an {@link EventSource} using the builder's current properties.
     * @return the new EventSource instance
     */
    public EventSource build() {
      return new EventSource(this);
    }
  }
}
