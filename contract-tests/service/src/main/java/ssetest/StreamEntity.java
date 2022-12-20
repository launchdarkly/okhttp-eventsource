package ssetest;

import com.launchdarkly.eventsource.*;
import com.launchdarkly.logging.*;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.*;
import ssetest.Representations.*;

public class StreamEntity {
  private final TestService owner;
  private final String id;
  private final EventSource eventSource;
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

    HttpConnectStrategy connectStrategy = ConnectStrategy.http(URI.create(options.streamUrl));
    if (options.readTimeoutMs != null) {
      connectStrategy = connectStrategy.readTimeout((long)options.readTimeoutMs, null);
    }
    if (options.method != null) {
      connectStrategy = connectStrategy.methodAndBody(options.method, 
        options.body == null ? null :
          RequestBody.create(options.body,
            MediaType.parse(options.headers.get("content-type") == null ?
              "text/plain; charset=utf-8" : options.headers.get("content-type")))
      );
    }
    if (options.headers != null) {
      for (String name: options.headers.keySet()) {
        connectStrategy = connectStrategy.header(name, options.headers.get(name));
      }
    }

    EventSource.Builder eb = new EventSource.Builder(connectStrategy)
      .errorStrategy(ErrorStrategy.alwaysContinue())
      .logger(logger.subLogger("stream"));
    if (options.initialDelayMs != null) {
      eb.retryDelay((long)options.initialDelayMs, null);
    }
    if (options.lastEventId != null) {
      eb.lastEventId(options.lastEventId);
    }

    this.eventSource = eb.build();
    new Thread(() -> {
      for (StreamEvent event: this.eventSource.anyEvents()) {
        handleEvent(event);
      }
    }).start();
  }
  
  public boolean doCommand(String command) {
    logger.info("Test harness sent command: {}", command);
    if (command.equals("restart")) {
      eventSource.interrupt();
      return true;
    }
    return false;
  }
  
  public void close() {
    closed = true;
    eventSource.close();
    owner.forgetStream(id);
    logger.info("Test ended");
  }
  
  private void handleEvent(StreamEvent event) {
    if (event instanceof MessageEvent) {
      onMessage((MessageEvent)event);
    } else if (event instanceof CommentEvent) {
      onComment(((CommentEvent)event).getText());
    } else if (event instanceof FaultEvent) {
      onError(((FaultEvent)event).getCause());
    }
  }
  
  private void onMessage(MessageEvent e) {
    logger.info("Received event from stream ({})", e.getEventName());
    Message m = new Message("event");
    m.event = new EventMessage();
    m.event.type = e.getEventName();
    m.event.data = e.getData();
    m.event.id = e.getLastEventId();
    writeMessage(m);
  }
  
  private void onComment(String comment) {
    Message m = new Message("comment");
    m.comment = comment;
    writeMessage(m);
  }
  
  private void onError(Throwable t) {
    if (t instanceof StreamClosedByCallerException) {
      return; // the SSE contract tests don't want to see this non-error
    }
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
