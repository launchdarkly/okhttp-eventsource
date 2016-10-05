package com.launchdarkly.eventsource;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.net.URI;

import static org.mockito.Mockito.mock;

public class EventSourceTest {
  public EventSource eventSource;
  public EventHandler eventHandler;

  @Before
  public void setUp() {
    eventHandler = mock(EventHandler.class);
    eventSource  = new EventSource.Builder(eventHandler, URI.create("http://www.example.com")).build();
  }

  @Test
  public void respectsMaximumBackoffTime() {
    eventSource.setReconnectionTimeMs(2000);
    assert(eventSource.backoffWithJitter(300) < EventSource.MAX_RECONNECT_TIME_MS);
  }

  @Ignore("Useful for inspecting jitter values empirically")
  public void inspectJitter() {
    for (int i = 0; i < 100; i++) {
      System.out.println("With jitter, retry " + i + ": " + eventSource.backoffWithJitter(i));
    }
  }
}
