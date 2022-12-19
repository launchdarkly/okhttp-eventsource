package com.launchdarkly.eventsource;

import com.google.common.collect.ImmutableSet;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.logging.Logs;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import okhttp3.HttpUrl;

@SuppressWarnings("javadoc")
public class EventSourceBuilderTest {
  private static final URI STREAM_URI = URI.create("http://www.example.com/");
  private EventSource.Builder builder;

  @Before
  public void setUp() {
    builder = new EventSource.Builder(STREAM_URI);
  }

  @Test
  public void createWithURL() throws Exception {
    try (EventSource es = new EventSource.Builder(new URL(STREAM_URI.toString())).build()) {
      assertThat(es.getOrigin(), equalTo(STREAM_URI));
    }
  }
  
  @Test
  public void createWithHttpUrl() throws Exception {
    try (EventSource es = new EventSource.Builder(HttpUrl.get(STREAM_URI)).build()) {
      assertThat(es.getOrigin(), equalTo(STREAM_URI));
    }
  }
  
  @Test(expected=IllegalArgumentException.class)
  public void connectStrategyCannotBeNull() {
    new EventSource.Builder((ConnectStrategy)null);
  }

  @Test(expected=IllegalArgumentException.class)
  public void uriCannotBeNull() {
    new EventSource.Builder((URI)null);
  }

  @Test(expected=IllegalArgumentException.class)
  public void urlCannotBeNull() {
    new EventSource.Builder((URL)null);
  }

  @Test(expected=IllegalArgumentException.class)
  public void urlCannotHaveIllegalCharacter() {
    URL url = null;
    try {
      url = new URL("http://domain/a?b=^X");
    } catch (MalformedURLException e) {
      fail("that string should have been valid for a java.net.URL (just not for a URI)");
    }
    new EventSource.Builder(url);
  }

  @Test(expected=IllegalArgumentException.class)
  public void httpUrlCannotBeNull() {
    new EventSource.Builder((HttpUrl)null);
  }
  
  @Test
  public void retryDelayStrategy() {
    try (EventSource es = builder.build()) {
      assertThat(es.baseRetryDelayStrategy, sameInstance(RetryDelayStrategy.defaultStrategy()));
      assertThat(es.currentRetryDelayStrategy, sameInstance(RetryDelayStrategy.defaultStrategy()));
    }

    RetryDelayStrategy customStrategy = RetryDelayStrategy.defaultStrategy().backoffMultiplier(3);
    try (EventSource es = builder.retryDelayStrategy(customStrategy).build()) {
      assertThat(es.baseRetryDelayStrategy, sameInstance(customStrategy));
      assertThat(es.currentRetryDelayStrategy, sameInstance(customStrategy));
    }
  }
  
  @Test
  public void retryDelayResetThreshold() {
    try (EventSource es = builder.build()) {
      assertThat(es.retryDelayResetThresholdMillis,
          equalTo(EventSource.DEFAULT_RETRY_DELAY_RESET_THRESHOLD_MILLIS));
    }

    try (EventSource es = builder.retryDelayResetThreshold(3, TimeUnit.SECONDS).build()) {
      assertThat(es.retryDelayResetThresholdMillis, equalTo(3000L));
    }
  }
  
  @Test
  public void readBufferSize() throws IOException {
    try (EventSource es = builder.build()) {
      assertEquals(EventSource.DEFAULT_READ_BUFFER_SIZE, es.readBufferSize);      
    }
    try (EventSource es = builder.readBufferSize(9999).build()) {
      assertEquals(9999, es.readBufferSize);
    }
    try {
      builder.readBufferSize(0);
      fail("expected exception");
    } catch (IllegalArgumentException e) {}
    try {
      builder.readBufferSize(-1);
      fail("expected exception");
    } catch (IllegalArgumentException e) {}
  }
  
  @Test
  public void hasDefaultLogger() {
    try (EventSource es = builder.build()) {
      assertThat(es.getLogger(), notNullValue());
    }
  }

  @Test
  public void logger() {
    LogCapture logCapture = Logs.capture();
    LDLogger myLogger = LDLogger.withAdapter(logCapture, "logname");
    try (EventSource es = builder.logger(myLogger).build()) {
      assertThat(es.getLogger(), sameInstance(myLogger));
    }
  }

  @Test
  public void streamingData() {
    try (EventSource es = builder.streamEventData(true).build()) {
      assertTrue(es.streamEventData);
    }
  }
  
  @Test
  public void expectFields() {
    try (EventSource es = builder
        .expectFields("a")
        .expectFields() // should overwrite previous setting
        .expectFields("b", "c") // should overwrite previous setting
        .build()) {
      assertEquals(ImmutableSet.of("b", "c"), es.expectFields);
    }
  }
}
