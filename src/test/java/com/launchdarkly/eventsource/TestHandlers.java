package com.launchdarkly.eventsource;

import com.launchdarkly.testhelpers.httptest.Handler;
import com.launchdarkly.testhelpers.httptest.Handlers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("javadoc")
public abstract class TestHandlers {
  public static Handler streamThatStaysOpen(String... events)
  {
    return Handlers.all(
      Handlers.SSE.start(),
      ctx -> {
        for (String e : events) {
          Handlers.SSE.event(e).apply(ctx);
        }
      },
      Handlers.SSE.leaveOpen()
      );
  }
  
  public static Handler chunksFromString(String body, int chunkSize, long delayMillis, boolean leaveOpen) {
    List<Handler> handlers = new ArrayList<>();
    handlers.add(Handlers.SSE.start());
    
    int numChunks = (body.length() + chunkSize - 1) / chunkSize;
    for (int i = 0; i < numChunks; i++) {
      int p = i * chunkSize;
      String chunk = body.substring(p, Math.min(body.length(), p + chunkSize));
      handlers.add(Handlers.writeChunkString(chunk));
      if (delayMillis > 0) {
        handlers.add(Handlers.delay(Duration.ofMillis(delayMillis)));
      }
    }
    
    if (leaveOpen) {
      handlers.add(Handlers.SSE.leaveOpen());
    }
    return Handlers.all(handlers.toArray(new Handler[0]));
  }
}
