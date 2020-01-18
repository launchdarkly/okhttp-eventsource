package com.launchdarkly.eventsource;

import org.junit.Ignore;
import org.junit.Test;

import java.net.URI;
import java.time.Duration;

@Ignore
public class ManualConnectionErrorTest {
  EventSource source;

  EventHandler handler = new EventHandler() {
    public void onOpen() throws Exception {
    }

    @Override
    public void onClosed() throws Exception {
    }

    public void onMessage(String event, MessageEvent messageEvent) throws Exception {
    }

    public void onError(Throwable t) {
      System.out.println("async handler got error: " + t);
    }

    public void onComment(String comment) {
    }
  };

  @Test
  public void testConnectionIsRetriedAfter404() throws Exception {
    // Expected output: multiple connection retries, and "async handler got error" each time.

    source = new EventSource.Builder(handler, URI.create("http://launchdarkly.com/bad-url"))
        .reconnectTime(Duration.ofMillis(10))
        .build();
    
    source.start();
    Thread.sleep(100000L);
  }
  
  @Test
  public void testConnectionIsNotRetriedAfter404IfErrorHandlerSaysToStop() throws Exception {
    // Expected output: "connection handler got error ... 404", followed by "Connection has been explicitly
    // shut down by error handler" and no further connection retries.

    ConnectionErrorHandler connectionErrorHandler = new ConnectionErrorHandler() {
      public Action onConnectionError(Throwable t) {
        System.out.println("connection handler got error: " + t);
        if (t instanceof UnsuccessfulResponseException &&
            ((UnsuccessfulResponseException) t).getCode() == 404) {
          return Action.SHUTDOWN;
        }
        return Action.PROCEED;
      }
    };

    source = new EventSource.Builder(handler, URI.create("http://launchdarkly.com/bad-url"))
        .connectionErrorHandler(connectionErrorHandler)
        .reconnectTime(Duration.ofMillis(10))
        .build();
    
    source.start();
    Thread.sleep(100000L);
  }
}
