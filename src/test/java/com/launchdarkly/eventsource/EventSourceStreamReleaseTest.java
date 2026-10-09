package com.launchdarkly.eventsource;

import org.junit.Rule;
import org.junit.Test;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.eventsource.MockConnectStrategy.ORIGIN;
import static com.launchdarkly.eventsource.TestUtils.interruptOnAnotherThread;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Tests that EventSource closes the input stream of a connection when it stops using
 * the connection. It must close the stream on the reading thread, after the connection
 * closer has run.
 */
@SuppressWarnings("javadoc")
public class EventSourceStreamReleaseTest {
  private static final String CLOSER_RAN = "closer ran";
  private static final String STREAM_CLOSED = "input stream closed";
  private static final String MESSAGE = "data: first\n\n";

  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();

  // Provides one stream that stays open until the connection closer runs, and records
  // when the closer runs and when the input stream is closed.
  private static class TrackedStreamHandler extends MockConnectStrategy.RequestHandler {
    final List<String> actions = Collections.synchronizedList(new ArrayList<>());
    volatile Thread streamClosingThread;
    private final PipedOutputStream writeStream = new PipedOutputStream();
    private final InputStream readStream;

    TrackedStreamHandler(String data) throws IOException {
      readStream = new FilterInputStream(new PipedInputStream(writeStream)) {
        @Override
        public void close() throws IOException {
          actions.add(STREAM_CLOSED);
          streamClosingThread = Thread.currentThread();
          super.close();
        }
      };
      writeStream.write(data.getBytes());
    }

    @Override
    protected InputStream getStream() {
      return readStream;
    }

    @Override
    protected Closeable makeCloser() {
      return () -> {
        actions.add(CLOSER_RAN);
        writeStream.close();
      };
    }

    void endStream() throws IOException {
      writeStream.close();
    }
  }

  private EventSource startEventSource(TrackedStreamHandler handler) throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(handler, MockConnectStrategy.respondWithDataAndThenStayOpen(MESSAGE));
    EventSource es = new EventSource.Builder(mock)
        .errorStrategy(ErrorStrategy.alwaysContinue())
        .retryDelayStrategy(RetryDelayStrategy.defaultStrategy().initialDelay(1, TimeUnit.MILLISECONDS))
        .logger(testLogger.getLogger())
        .build();
    es.start();
    assertThat(es.readAnyEvent(), equalTo(new MessageEvent("message", "first", null, ORIGIN)));
    return es;
  }

  @Test
  public void interruptOnReadingThreadClosesInputStreamAfterCloser() throws Exception {
    TrackedStreamHandler handler = new TrackedStreamHandler(MESSAGE);
    try (EventSource es = startEventSource(handler)) {
      es.interrupt();

      assertThat(handler.actions, contains(CLOSER_RAN, STREAM_CLOSED));
      assertThat(handler.streamClosingThread, sameInstance(Thread.currentThread()));
    }
  }

  @Test
  public void stopOnReadingThreadClosesInputStreamAfterCloser() throws Exception {
    TrackedStreamHandler handler = new TrackedStreamHandler(MESSAGE);
    try (EventSource es = startEventSource(handler)) {
      es.stop();

      assertThat(handler.actions, contains(CLOSER_RAN, STREAM_CLOSED));
    }
  }

  @Test
  public void closeOnReadingThreadClosesInputStreamAfterCloser() throws Exception {
    TrackedStreamHandler handler = new TrackedStreamHandler(MESSAGE);
    EventSource es = startEventSource(handler);
    es.close();

    assertThat(handler.actions, contains(CLOSER_RAN, STREAM_CLOSED));
  }

  @Test
  public void interruptFromAnotherThreadClosesInputStreamOnReadingThread() throws Exception {
    TrackedStreamHandler handler = new TrackedStreamHandler(MESSAGE);
    try (EventSource es = startEventSource(handler)) {
      interruptOnAnotherThread(es);

      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByCallerException())));
      assertThat(handler.actions, contains(CLOSER_RAN, STREAM_CLOSED));
      assertThat(handler.streamClosingThread, sameInstance(Thread.currentThread()));
    }
  }

  @Test
  public void endOfStreamClosesInputStream() throws Exception {
    TrackedStreamHandler handler = new TrackedStreamHandler(MESSAGE);
    try (EventSource es = startEventSource(handler)) {
      handler.endStream();

      assertThat(es.readAnyEvent(), equalTo(new FaultEvent(new StreamClosedByServerException())));
      assertThat(handler.actions, contains(CLOSER_RAN, STREAM_CLOSED));
    }
  }
}
