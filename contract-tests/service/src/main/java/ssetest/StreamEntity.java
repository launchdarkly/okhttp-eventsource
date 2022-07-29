package ssetest;

import com.launchdarkly.eventsource.*;
import com.launchdarkly.logging.*;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.*;
import ssetest.Representations.*;

public class StreamEntity implements EventHandler {
  private final TestService owner;
  private final String id;
  private final EventSource stream;
  private final StreamOptions options;
  private final AtomicInteger callbackMessageCounter = new AtomicInteger(0);
  private final LDLogger logger;
  private volatile boolean closed;
  
  public StreamEntity(TestService owner, String id, StreamOptions options, LDLogAdapter logAdapter) {
    this.owner = owner;
    this.id = id;
    this.options = options;

    this.logger = LDLogger.withAdapter(logAdapter, options.tag);
    logger.info("Opening stream to {}", options.streamUrl);

    EventSource.Builder eb = new EventSource.Builder(this, URI.create(options.streamUrl))
      .logger(logger.subLogger("stream"));
    if (options.headers != null) {
      Headers.Builder hb = new Headers.Builder();
      for (String name: options.headers.keySet()) {
        hb.add(name, options.headers.get(name));
      }
      eb.headers(hb.build());
    }
    if (options.initialDelayMs != null) {
      eb.reconnectTime(Duration.ofMillis(options.initialDelayMs));
    }
    if (options.readTimeoutMs != null) {
      eb.readTimeout(Duration.ofMillis(options.readTimeoutMs));
    }
    if (options.lastEventId != null) {
      eb.lastEventId(options.lastEventId);
    }
    if (options.method != null) {
      eb.method(options.method);
    }
    if (options.body != null) {
      String contentType = options.headers == null ? null : options.headers.get("content-type");
      eb.body(RequestBody.create(options.body,
        MediaType.parse(contentType == null ? "text/plain; charset=utf-8" : contentType)));
    }
    this.stream = eb.build();
    
    this.stream.start();
  }
  
  public boolean doCommand(String command) {
    logger.info("Test harness sent command: {}", command);
    if (command.equals("restart")) {
      stream.restart();
      return true;
    }
    return false;
  }
  
  public void close() {
    closed = true;
    stream.close();
    owner.forgetStream(id);
    logger.info("Test ended");
  }
  
  public void onOpen() {}
  
  public void onClosed() {}
  
  public void onMessage(String name, MessageEvent e) {
    logger.info("Received event from stream ({})", name);
    Message m = new Message("event");
    m.event = new EventMessage();
    m.event.type = name;
    m.event.data = e.getData();
    m.event.id = e.getLastEventId();
    writeMessage(m);
  }
  
  public void onComment(String comment) {
    Message m = new Message("comment");
    m.comment = comment;
    writeMessage(m);
  }
  
  public void onError(Throwable t) {
    logger.info("Received error from stream: {}", t.toString());
    Message m = new Message("error");
    m.error = t.toString();
    writeMessage(m);
  }
  
  private void writeMessage(Message m) {
    if (closed) {
      return;
    }
    int counter = callbackMessageCounter.incrementAndGet();
    String url = options.callbackUrl + "/" + counter;
    String json = owner.gson.toJson(m);
    Request request = new Request.Builder().url(url)
        .method("POST", RequestBody.create(json, MediaType.get("application/json"))).build();
    try {
      Response resp = owner.client.newCall(request).execute();
      if (resp.code() >= 300) {
        logger.error("Callback post to {} returned status {}", url, resp.code());
      }
    } catch (Exception e) {
      logger.error("Callback post to {} failed: {}", url, e.getClass());
    }
  }
}
