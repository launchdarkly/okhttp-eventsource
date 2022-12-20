package com.launchdarkly.eventsource.background;

import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MockConnectStrategy;
import com.launchdarkly.eventsource.StreamClosedByServerException;
import com.launchdarkly.eventsource.TestScopedLoggerRule;
import com.launchdarkly.eventsource.background.Stubs.LogItem;
import com.launchdarkly.eventsource.background.Stubs.TestHandler;

import org.junit.Rule;
import org.junit.Test;

import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithDataAndThenEnd;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithDataAndThenStayOpen;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@SuppressWarnings("javadoc")
public class BackgroundEventSourceBasicTest {
  private final MockConnectStrategy mockConnect = new MockConnectStrategy();

  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();
  
  private EventSource.Builder baseEventSourceBuilder() {
    return new EventSource.Builder(mockConnect)
        .retryDelay(1, null)
        .logger(testLogger.getLogger());
  }

  @Test(expected=IllegalArgumentException.class)
  public void handlerCannotBeNull() {
    new BackgroundEventSource.Builder(null, new EventSource.Builder(mockConnect));
  }

  @Test(expected=IllegalArgumentException.class)
  public void eventSourceBuilderCannotBeNull() {
    new BackgroundEventSource.Builder(new TestHandler(), null);
  }

  @Test
  public void eventsAreDispatchedToHandler() {
    TestHandler testHandler = new TestHandler(testLogger.getLogger());
    mockConnect.configureRequests(
        respondWithDataAndThenStayOpen(
            "event: event1\ndata: data1\n\n",
            ":hello\n"
            )
        );
    
    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        testHandler,
        baseEventSourceBuilder()
        ).build()) {
      bes.start();
      
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.opened()));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("event1", "data1")));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.comment("hello")));
    }
  }
  
  @Test
  public void alwaysRetriesConnectionByDefault() {
    TestHandler testHandler = new TestHandler(testLogger.getLogger());
    mockConnect.configureRequests(
        respondWithDataAndThenEnd("data: data1\n\n"),
        respondWithDataAndThenStayOpen("data: data2\n\n")
        );
    
    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        testHandler,
        baseEventSourceBuilder()
        ).build()) {
      bes.start();
      
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.opened()));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("message", "data1")));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.error(new StreamClosedByServerException())));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.closed()));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.opened()));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("message", "data2")));
    }
  }
}
