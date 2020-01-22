package com.launchdarkly.eventsource;

import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

@Ignore
@SuppressWarnings("javadoc")
public class ManualTest {
  private static final Logger logger = LoggerFactory.getLogger(ManualTest.class);

  @Test
  public void manualTest() throws InterruptedException {
    EventHandler handler = new EventHandler() {
      public void onOpen() throws Exception {
        logger.info("open");
      }

      @Override
      public void onClosed() throws Exception {

      }

      public void onMessage(String event, MessageEvent messageEvent) throws Exception {
        logger.info(event + ": " + messageEvent.getData());
      }

      public void onError(Throwable t) {
        logger.error("Error: " + t);
      }

      public void onComment(String comment) {
        logger.info("comment: " + comment);
      }
    };
    EventSource source = new EventSource.Builder(handler, URI.create("http://localhost:8080/events/")).build();
    source.start();
    logger.warn("Sleeping...");
    Thread.sleep(10000L);
    logger.debug("Stopping source");
    source.close();
    logger.debug("Stopped");
  }
}
