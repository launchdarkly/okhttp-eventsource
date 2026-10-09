package com.launchdarkly.eventsource;

import org.junit.Rule;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import okhttp3.ConnectionPool;
import okhttp3.Protocol;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Tests of EventSource with real HTTP/2 connections. An HTTP/2 connection stays in use
 * until its response is released, so these tests check that EventSource releases the
 * response when it stops using a stream.
 */
@SuppressWarnings("javadoc")
public class HttpConnectStrategyHttp2Test {
  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();

  // The body is larger than the client reads before the test stops the stream, so the
  // stream is still active at that time.
  private static MockResponse streamResponse(String eventData) {
    StringBuilder body = new StringBuilder("data: " + eventData + "\n\n");
    for (int i = 0; i < 50000; i++) {
      body.append(":\n");
    }
    return new MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body.toString());
  }

  private static MockWebServer startHttp2Server(MockResponse... responses) throws Exception {
    MockWebServer server = new MockWebServer();
    server.setProtocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE));
    for (MockResponse response: responses) {
      server.enqueue(response);
    }
    server.start();
    return server;
  }

  private EventSource makeEventSource(MockWebServer server, ConnectionPool pool) {
    return new EventSource.Builder(
        ConnectStrategy.http(server.url("/").uri())
          .clientBuilderActions(b -> b
              .protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE))
              .connectionPool(pool)))
        .errorStrategy(ErrorStrategy.alwaysContinue())
        .retryDelayStrategy(RetryDelayStrategy.defaultStrategy().initialDelay(1, TimeUnit.MILLISECONDS))
        .logger(testLogger.getLogger())
        .build();
  }

  private static void readUntilMessage(EventSource es, String expectedData) throws Exception {
    for (int i = 0; i < 10; i++) {
      StreamEvent event = es.readAnyEvent();
      if (event instanceof MessageEvent) {
        assertThat(((MessageEvent)event).getData(), equalTo(expectedData));
        return;
      }
    }
    throw new AssertionError("did not receive message \"" + expectedData + "\"");
  }

  @Test
  public void interruptOnReadingThreadReleasesConnection() throws Exception {
    try (MockWebServer server = startHttp2Server(streamResponse("first"))) {
      ConnectionPool pool = new ConnectionPool(5, 1, TimeUnit.MINUTES);
      try (EventSource es = makeEventSource(server, pool)) {
        readUntilMessage(es, "first");
        assertThat(pool.idleConnectionCount(), equalTo(0));

        es.interrupt();

        assertThat(pool.connectionCount(), equalTo(1));
        assertThat(pool.idleConnectionCount(), equalTo(1));
      }
    }
  }

  @Test
  public void reconnectAfterInterruptOnReadingThreadUsesNewConnectionIfPoolKeepsNoIdleConnections()
      throws Exception {
    try (MockWebServer server = startHttp2Server(streamResponse("first"), streamResponse("second"))) {
      ConnectionPool pool = new ConnectionPool(0, 1, TimeUnit.SECONDS);
      try (EventSource es = makeEventSource(server, pool)) {
        readUntilMessage(es, "first");

        es.interrupt();
        assertThat(pool.connectionCount(), equalTo(0));

        readUntilMessage(es, "second");

        RecordedRequest request1 = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest request2 = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request1.getSequenceNumber(), equalTo(0));
        // A sequence number of 0 means that the request was the first one on its connection.
        assertThat(request2.getSequenceNumber(), equalTo(0));
      }
    }
  }

  @Test
  public void stopOnReadingThreadReleasesConnection() throws Exception {
    try (MockWebServer server = startHttp2Server(streamResponse("first"))) {
      ConnectionPool pool = new ConnectionPool(5, 1, TimeUnit.MINUTES);
      try (EventSource es = makeEventSource(server, pool)) {
        readUntilMessage(es, "first");

        es.stop();

        assertThat(pool.idleConnectionCount(), equalTo(1));
      }
    }
  }
}
