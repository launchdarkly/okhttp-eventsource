package com.launchdarkly.eventsource;

import com.launchdarkly.testhelpers.httptest.Handler;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;

import org.junit.Rule;
import org.junit.Test;

import static com.launchdarkly.eventsource.TestUtils.interruptOnAnotherThread;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests of basic EventSource behavior using real HTTP requests.
 */
@SuppressWarnings("javadoc")
public class HttpConnectStrategyWithEventSourceTest {
  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();

  @Test
  public void eventSourceReadsEvents() throws Exception {
    Handler response = Handlers.all(
        Handlers.SSE.start(),
        Handlers.SSE.event("a", "data1"),
        Handlers.SSE.event("b", "data2"),
        Handlers.hang()
        );
    
    try (HttpServer server = HttpServer.start(response)) {
      try (EventSource es = new EventSource.Builder(server.getUri()).build()) {
        es.start();
        
        assertThat(es.readAnyEvent(), equalTo(
            new MessageEvent("a", "data1", null, es.getOrigin())));
  
        assertThat(es.readAnyEvent(), equalTo(
            new MessageEvent("b", "data2", null, es.getOrigin())));
      }
    }
  }

  @Test
  public void eventSourceReconnectsAfterSocketClosed() throws Exception {
    Handler response1 = Handlers.all(
        Handlers.SSE.start(),
        Handlers.SSE.event("message", "first")
        );
    Handler response2 = Handlers.all(
        Handlers.SSE.start(),
        Handlers.SSE.event("message", "second"),
        Handlers.SSE.leaveOpen()
        );
    Handler allResponses = Handlers.sequential(response1, response2);

    try (HttpServer server = HttpServer.start(allResponses)) {
      try (EventSource es = new EventSource.Builder(server.getUri())
          .errorStrategy(ErrorStrategy.alwaysContinue())
          .retryDelay(1, null)
          .build()) {
        es.start();
        
        assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "first", null, es.getOrigin())));
        
        assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
        
        assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
        
        assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "second", null, es.getOrigin())));
      }
    }
  }

  @Test
  public void eventSourceReconnectsAfterExternallyInterrupted() throws Exception {
    Handler response1 = Handlers.all(
        Handlers.SSE.start(),
        Handlers.SSE.event("message", "first"),
        Handlers.SSE.leaveOpen()
        );
    Handler response2 = Handlers.all(
        Handlers.SSE.start(),
        Handlers.SSE.event("message", "second"),
        Handlers.SSE.leaveOpen()
        );
    Handler allResponses = Handlers.sequential(response1, response2);

    try (HttpServer server = HttpServer.start(allResponses)) {
      try (EventSource es = new EventSource.Builder(server.getUri())
          .errorStrategy(ErrorStrategy.alwaysContinue())
          .retryDelay(1, null)
          .build()) {
        es.start();
        
        assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "first", null, es.getOrigin())));
        
        interruptOnAnotherThread(es);

        assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByCallerException())));
        
        assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
        
        assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "second", null, es.getOrigin())));
      }
    }
  }
}
