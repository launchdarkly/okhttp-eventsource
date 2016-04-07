package com.launchdarkly.eventsource;

import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;

@Ignore
public class ManualTest {
  private static final Logger logger = LoggerFactory.getLogger(ManualTest.class);

  @Test
  public void manualTest() {
    EventHandler handler = new EventHandler() {
      public void onOpen() throws Exception {
        logger.debug("Open");
      }

      public void onMessage(String event, MessageEvent messageEvent) throws Exception {
        logger.debug(event + ": " + messageEvent.getData());

      }

      public void onError(Throwable t) {
        logger.error("Error: " + t);
      }
    };
    EventSource source = new EventSource.Builder(handler, URI.create("http://localhost:8080/events/")).build();
    source.start();
    try {
      Thread.sleep(100000000000L);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    logger.debug("Stopping source");
    try {
      source.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
    logger.debug("Stopped");
  }
}
