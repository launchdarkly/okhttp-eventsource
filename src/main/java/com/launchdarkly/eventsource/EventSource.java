package com.launchdarkly.eventsource;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.launchdarkly.eventsource.ReadyState.*;

/**
 * Client for <a href="https://www.w3.org/TR/2015/REC-eventsource-20150203/">Server-Sent Events</a>
 * aka EventSource
 */
public class EventSource implements ConnectionHandler, Closeable {
  private static final Logger logger = LoggerFactory.getLogger(EventSource.class);

  private static final long DEFAULT_RECONNECT_TIME_MS = 1000;
  private final URI uri;
  private final Headers headers;
  private final ExecutorService executor;
  private volatile long reconnectTimeMs = 0;
  private volatile String lastEventId;
  private final EventHandler handler;
  private final AtomicReference<ReadyState> readyState;
  private final OkHttpClient client;
  private volatile Call call;

  EventSource(Builder builder) {
    this.uri = builder.uri;
    this.headers = addDefaultHeaders(builder.headers);
    this.reconnectTimeMs = builder.reconnectTimeMs;
    this.executor = Executors.newCachedThreadPool();
    this.handler = new AsyncEventHandler(this.executor, builder.handler);
    this.readyState = new AtomicReference<>(RAW);
    this.client = builder.client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build();
  }

  public void start() {
    if (!readyState.compareAndSet(RAW, CONNECTING)) {
      logger.info("Start method called on this already-started EventSource object. Doing nothing");
      return;
    }
    logger.debug("readyState change: " + RAW + " -> " + CONNECTING);
    logger.info("Starting EventSource client using URI: " + uri);
    executor.execute(new Runnable() {
      public void run() {
        connect();
      }
    });
  }

  @Override
  public void close() throws IOException {
    ReadyState currentState = readyState.getAndSet(SHUTDOWN);
    logger.debug("readyState change: " + currentState + " -> " + SHUTDOWN);
    if (currentState == SHUTDOWN) {
      return;
    }
    executor.shutdownNow();
    if (call != null) {
      call.cancel();
    }
  }

  private void connect() {
    Response response = null;
    try {
      while (!Thread.currentThread().isInterrupted() && readyState.get() != SHUTDOWN) {
        if (reconnectTimeMs > 0) {
          logger.info("Waiting " + reconnectTimeMs + " milliseconds before connecting..");
          try {
            Thread.sleep(reconnectTimeMs);
          } catch (InterruptedException ignored) {
          }
        }
        ReadyState currentState = readyState.getAndSet(CONNECTING);
        logger.debug("readyState change: " + currentState + " -> " + CONNECTING);
        try {
          Request.Builder builder = new Request.Builder()
              .headers(headers)
              .url(uri.toASCIIString())
              .get();

          if (lastEventId != null && !lastEventId.isEmpty()) {
            builder.addHeader("Last-Event-ID", lastEventId);
          }

          call = client.newCall(builder.build());
          response = call.execute();
          if (response.isSuccessful()) {
            currentState = readyState.getAndSet(OPEN);
            if (currentState != CONNECTING) {
              logger.warn("Unexpected readyState change: " + currentState + " -> " + OPEN);
            } else {
              logger.debug("readyState change: " + currentState + " -> " + OPEN);
            }
            logger.info("Connected to Event Source stream.");
            BufferedSource bs = Okio.buffer(response.body().source());
            EventParser parser = new EventParser(uri, handler, EventSource.this);
            for (String line; !Thread.currentThread().isInterrupted() && (line = bs.readUtf8LineStrict()) != null; ) {
              parser.line(line);
            }
          } else {
            logger.debug("Unsuccessful Response: " + response);
            handler.onError(new UnsuccessfulResponseException(response.code()));
          }
        } catch (EOFException eofe) {
          logger.warn("Connection unexpectedly closed.");
        } catch (IOException ioe) {
          logger.debug("Connection problem.", ioe);
          handler.onError(ioe);
        } finally {
          currentState = readyState.getAndSet(CLOSED);
          logger.debug("readyState change: " + currentState + " -> " + CLOSED);
          if (response != null && response.body() != null) {
            response.body().close();
          }
          if (call != null) {
            call.cancel();
          }
        }
      }
    } catch (RejectedExecutionException ignored) {
      // During shutdown, we tried to send a message to the event handler
      // Do not reconnect; the executor has been shut down
    }
  }

  private static Headers addDefaultHeaders(Headers custom) {
    Headers.Builder builder = new Headers.Builder();

    builder.add("Accept", "text/event-stream").add("Cache-Control", "no-cache");

    for (Map.Entry<String, List<String>> header : custom.toMultimap().entrySet()) {
      for (String value : header.getValue()) {
        builder.add(header.getKey(), value);
      }
    }

    return builder.build();
  }

  public void setReconnectionTimeMs(long reconnectionTimeMs) {
    this.reconnectTimeMs = reconnectionTimeMs;
  }

  public void setLastEventId(String lastEventId) {
    this.lastEventId = lastEventId;
  }

  public static final class Builder {
    private long reconnectTimeMs = DEFAULT_RECONNECT_TIME_MS;
    private final URI uri;
    private final EventHandler handler;
    private Headers headers = Headers.of();
    private OkHttpClient client = new OkHttpClient();

    public Builder(EventHandler handler, URI uri) {
      this.uri = uri;
      this.handler = handler;
    }

    public Builder reconnectTimeMs(long reconnectTimeMs) {
      this.reconnectTimeMs = reconnectTimeMs;
      return this;
    }

    public Builder headers(Headers headers) {
      this.headers = headers;
      return this;
    }

    public Builder client(OkHttpClient client) {
      this.client = client;
      return this;
    }

    public EventSource build() {
      return new EventSource(this);
    }
  }
}
