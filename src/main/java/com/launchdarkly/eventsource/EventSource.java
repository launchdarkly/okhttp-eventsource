package com.launchdarkly.eventsource;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;
import okio.Okio;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class EventSource implements ConnectionHandler
{
  public static final long DEFAULT_RECONNECT_TIME_MS = 2000;

  public static final int CONNECTING = 0;
  public static final int OPEN = 1;
  public static final int CLOSED = 2;

  private final URI uri;
  private final Headers headers;
  private final ExecutorService executor;
  private volatile long reconnectTimeMs;
  private volatile String lastEventId;
  private volatile Future<?> connectionTask;
  private final EventHandler handler;
  private AtomicInteger readyState;
  private final OkHttpClient client;

  EventSource(Builder builder) {
    this.uri = builder.uri;
    this.headers = addDefaultHeaders(builder.headers);
    this.reconnectTimeMs = builder.reconnectTimeMs;
    this.executor = Executors.newCachedThreadPool();
    this.handler = new AsyncEventHandler(this.executor, builder.handler);
    this.readyState = new AtomicInteger(CLOSED);
    this.client = builder.client;

  }

  public void start() {
    if (!readyState.compareAndSet(CLOSED, CONNECTING)) {
      return;
    }

    connectionTask = executor.submit(new Runnable() {
      public void run() {
        connect();
      }
    });
  }

  public void stop() {
    connectionTask.cancel(true);
    executor.shutdown();
    readyState.set(CLOSED);
  }

  private void connect() {
    Request.Builder builder = new Request.Builder().headers(headers).url(uri.toASCIIString()).get();
    if (lastEventId != null && !lastEventId.isEmpty()) {
      builder.addHeader("Last-Event-ID", lastEventId);
    }
    Response response = null;
    try {
      response = client.newCall(builder.build()).execute();
      if (response.isSuccessful()) {
        readyState.compareAndSet(CONNECTING, OPEN);
        BufferedSource bs = Okio.buffer(response.body().source());
        EventParser parser = new EventParser(uri, handler, EventSource.this);
        for (String line; (line = bs.readUtf8LineStrict()) != null;) {
          parser.line(line);
        }
      }
      else {
        // Log an error
      }
    } catch (Exception e) {
      handler.onError(e);
      readyState.compareAndSet(CONNECTING, CLOSED);
    } finally {
      readyState.compareAndSet(CONNECTING, CLOSED);
      if (response != null && response.body() != null) {
        response.body().close();
      }
      reconnect();
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
    source.stop();
  }


}
