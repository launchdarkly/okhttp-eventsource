package com.launchdarkly.eventsource;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;
import okio.Okio;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class EventSource implements ConnectionHandler, Closeable
{
  public static final long DEFAULT_RECONNECT_TIME_MS = 1000;

  public static final int RAW = -1;
  public static final int CONNECTING = 0;
  public static final int OPEN = 1;
  public static final int CLOSED = 2;
  public static final int SHUTDOWN = 3;

  private final URI uri;
  private final Headers headers;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private volatile long reconnectTimeMs;
  private volatile String lastEventId;
  private final EventHandler handler;
  private AtomicInteger readyState;
  private final OkHttpClient client;
  private volatile Call call;

  EventSource(Builder builder) {
    this.uri = builder.uri;
    this.headers = addDefaultHeaders(builder.headers);
    this.reconnectTimeMs = builder.reconnectTimeMs;
    this.handler = new AsyncEventHandler(this.executor, builder.handler);
    this.readyState = new AtomicInteger(RAW);
    this.client = builder.client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build();

  }

  public void start() {
    if (!readyState.compareAndSet(RAW, CONNECTING)) {
      return;
    }

    executor.execute(new Runnable() {
      public void run() {
        connect();
      }
    });
  }

  public void close() throws IOException {
    int currentState = readyState.getAndSet(SHUTDOWN);
    if (currentState == SHUTDOWN) {
      return;
    }
    executor.shutdownNow();
    if (call != null) {
      call.cancel();
    }
  }

  private void connect() {
    Request.Builder builder = new Request.Builder().headers(headers).url(uri.toASCIIString()).get();
    if (lastEventId != null && !lastEventId.isEmpty()) {
      builder.addHeader("Last-Event-ID", lastEventId);
    }
    Response response = null;
    try {
      call = client.newCall(builder.build());
      response = call.execute();
      if (response.isSuccessful()) {
        readyState.compareAndSet(CONNECTING, OPEN);
        BufferedSource bs = Okio.buffer(response.body().source());
        EventParser parser = new EventParser(uri, handler, EventSource.this);
        for (String line; !Thread.currentThread().isInterrupted() && (line = bs.readUtf8LineStrict()) != null;) {
          parser.line(line);
        }
      }
      else {
        readyState.set(CLOSED);
        try {
          handler.onError(new UnsuccessfulResponseException(response.code()));
          reconnect();
        } catch (RejectedExecutionException ex) {
          // During shutdown, we tried to send an error message to the event handler
          // Do not reconnect; the executor has been shut down
        }
      }
    } catch (RejectedExecutionException ex) {
      // During shutdown, we tried to send a message to the event handler
      // Do not reconnect; the executor has been shut down
    }
    catch (Exception e) {
        readyState.set(CLOSED);
        try {
          handler.onError(e);
          reconnect();
        } catch (RejectedExecutionException ex) {
          // During shutdown, we tried to send an error message to the event handler
          // Do not reconnect; the executor has been shut down
        }
    } finally {
      if (response != null && response.body() != null) {
        response.body().close();
      }
      if (call != null) {
        call.cancel();
      }
    }
  }

  public void reconnect() {
    try {
      Thread.sleep(reconnectTimeMs);
    } catch (InterruptedException e) {
    }
    if (!readyState.compareAndSet(CLOSED, CONNECTING)) {
      return;
    }
    connect();
  }

  private static final Headers addDefaultHeaders(Headers custom) {
    Headers.Builder builder = new Headers.Builder();

    builder.add("Accept", "text/event-stream").add("Cache-Control", "no-cache");

    for (Map.Entry<String, List<String>> header : custom.toMultimap().entrySet()) {
      for (String value: header.getValue()) {
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

  public static void main(String... args) {
    EventHandler handler = new EventHandler() {
      public void onOpen() throws Exception {
        System.out.println("Open");
      }

      public void onMessage(String event, MessageEvent messageEvent) throws Exception {
        System.out.println(event + ": " + messageEvent.getData());

      }

      public void onError(Throwable t) {
        System.out.println("Error: " + t);
      }
    };
    EventSource source = new Builder(handler, URI.create("http://localhost:8080/events/")).build();
    source.start();
    try {
      Thread.sleep(10000);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    System.out.println("Stopping source");
    try {
      source.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
    System.out.println("Stopped");
  }
}
