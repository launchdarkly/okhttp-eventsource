package com.launchdarkly.eventsource;

import com.launchdarkly.testhelpers.httptest.Handler;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;

import org.fest.reflect.reference.TypeRef;
import org.junit.Rule;
import org.junit.Test;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicReference;

import static com.launchdarkly.eventsource.TestUtils.interruptOnAnotherThread;
import static com.launchdarkly.eventsource.TestUtils.interruptOnAnotherThreadAfterDelay;
import static org.fest.reflect.core.Reflection.field;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

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

  @Test
  public void eventSourceReconnectsAfterExternallyInterruptedWithNewEventParser() throws Exception {
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
      AtomicReference<EventSource> holder = new AtomicReference<>();
      try (EventSource es = new EventSource.Builder(server.getUri())
        .errorStrategy(ErrorStrategy.alwaysContinue())
        .retryDelay(1, null)
        .build()) {
        es.start();
        holder.set(es);
        AtomicReference<Closeable> connectionCloser = field("connectionCloser").ofType(new TypeRef<AtomicReference<Closeable>>() {
        }).in(es).get();
        AtomicReference<Closeable> responseCloser = field("responseCloser").ofType(new TypeRef<AtomicReference<Closeable>>() {
        }).in(es).get();

        assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "first", null, es.getOrigin())));

        interruptOnAnotherThread(es).join();

        assertThat(connectionCloser.get(), nullValue());
        // Response should be closed with reading thread.
        assertThat(responseCloser.get(), notNullValue());

        assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByCallerException())));

        // All closed.
        assertThat(connectionCloser.get(), nullValue());

        assertThat(responseCloser.get(), nullValue());


        assertThat(es.readAnyEvent(), equalTo(new StartedEvent()));
        // All recreated
        assertThat(connectionCloser.get(), notNullValue());

        assertThat(responseCloser.get(), notNullValue());

        assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "second", null, es.getOrigin())));
      }
      AtomicReference<Closeable> connectionCloser = field("connectionCloser").ofType(new TypeRef<AtomicReference<Closeable>>() {
      }).in(holder.get()).get();
      AtomicReference<Closeable> responseCloser = field("responseCloser").ofType(new TypeRef<AtomicReference<Closeable>>() {
      }).in(holder.get()).get();

      // All closed by try-with-resource statement
      assertThat(connectionCloser.get(), nullValue());

      assertThat(responseCloser.get(), nullValue());
    }
  }
}
