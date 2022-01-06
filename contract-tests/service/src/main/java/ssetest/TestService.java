package ssetest;

import com.google.gson.Gson;
import com.launchdarkly.testhelpers.httptest.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import okhttp3.*;

import ssetest.Representations.*;

public class TestService {
  private static final int PORT = 8000;
  
  final Gson gson = new Gson();
  final OkHttpClient client = new OkHttpClient();

  private final Map<String, StreamEntity> streams = new ConcurrentHashMap<String, StreamEntity>();
  private final AtomicInteger streamCounter = new AtomicInteger(0);
  
  public static void main(String[] args) {
    // ((ch.qos.logback.classic.Logger)LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(
    //     Level.valueOf(config.logLevel.toUpperCase()));

    TestService service = new TestService();

    SimpleRouter router = new SimpleRouter()
        .add("GET", "/", ctx -> service.writeJson(ctx, service.getStatus()))
        .add("DELETE", "/", ctx -> service.forceQuit())
        .add("POST", "/", ctx -> service.postCreateStream(ctx))
        .addRegex("POST", Pattern.compile("/streams/(.*)"), ctx -> service.postStreamCommand(ctx))
        .addRegex("DELETE", Pattern.compile("/streams/(.*)"), ctx -> service.deleteStream(ctx));

    HttpServer server = HttpServer.start(PORT, router); 
    server.getRecorder().setEnabled(false); // don't accumulate a request log

    System.out.println("Listening on port " + PORT);
  }

  private Status getStatus() {
    Status rep = new Status();
    rep.capabilities = new String[]{
      "comments",
      "headers",
      "last-event-id",
      "post",
      "read-timeout",
      "report",
      "restart"
    };
    return rep;
  }

  private void forceQuit() {
    System.out.println("Test harness has told us to quit");
    System.exit(0);
  }
  
  private void postCreateStream(RequestContext ctx) {
    StreamOptions opts = readJson(ctx, StreamOptions.class);

    String streamId = String.valueOf(streamCounter.incrementAndGet());
    StreamEntity stream = new StreamEntity(this, streamId, opts);

    streams.put(streamId, stream);
    
    ctx.addHeader("Location", "/streams/" + streamId);
  }

  private void postStreamCommand(RequestContext ctx) {
    CommandParams params = readJson(ctx, CommandParams.class);
    
    String streamId = ctx.getPathParam(0);
    StreamEntity stream = streams.get(streamId);
    if (stream == null) {
      ctx.setStatus(404);
    } else {
      if (!stream.doCommand(params.command)) {
        ctx.setStatus(400);
      }
    }  
  }
  
  private void deleteStream(RequestContext ctx) {
    String streamId = ctx.getPathParam(0);
    StreamEntity stream = streams.get(streamId);
    if (stream == null) {
      ctx.setStatus(404);
    } else {
      stream.close();
    }
  }
  
  void forgetStream(String id) {
    streams.remove(id);
  }
  
  private <T> T readJson(RequestContext ctx, Class<T> paramsClass) {
    return gson.fromJson(ctx.getRequest().getBody(), paramsClass);
  }
  
  private void writeJson(RequestContext ctx, Object data) {
    String json = gson.toJson(data);
    Handlers.bodyJson(json).apply(ctx);
  }
}
