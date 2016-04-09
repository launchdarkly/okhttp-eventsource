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
  public void manualTest() throws InterruptedException {
    EventHandler handler = new EventHandler() {
      public void onOpen() throws Exception {
        logger.info("Open");
      }

      public void onMessage(String event, MessageEvent messageEvent) throws Exception {
        logger.info(event + ": " + messageEvent.getData());
      }

      public void onError(Throwable t) {
        logger.error("Error: " + t);
      }
    };
    EventSource source = new EventSource.Builder(handler, URI.create("http://localhost:8080/events/")).build();
    source.start();
    logger.warn("Sleeping...");
    Thread.sleep(10000L);
    logger.debug("Stopping source");
    try {
      source.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
    logger.debug("Stopped");
  }
}
