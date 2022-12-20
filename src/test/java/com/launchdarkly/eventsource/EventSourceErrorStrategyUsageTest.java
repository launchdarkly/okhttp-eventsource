package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.MockConnectStrategy.PipedStreamRequestHandler;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

import static com.launchdarkly.eventsource.MockConnectStrategy.rejectConnection;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithDataAndThenStayOpen;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithStream;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.fail;

/**
 * These tests verify that EventSource interacts with ErrorStrategy methods in the
 * expected way, regardless of what ErrorStrategy implementation is being used.
 *
 * Details of how EventSource handles connection retries are covered by 
 * EventSourceReconnectTest. 
 */
@SuppressWarnings("javadoc")
public class EventSourceErrorStrategyUsageTest {
  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();

  private EventSource.Builder baseBuilder(MockConnectStrategy mock) {
    return new EventSource.Builder(mock)
        .retryDelay(1, null)
        .logger(testLogger.getLogger());
  }
  
  private static StreamException[] makeAllStreamExceptions() {
    return new StreamException[] {
      new StreamClosedByCallerException(),
      new StreamClosedByServerException(),
      new StreamIOException(new IOException("")),
      new StreamHttpErrorException(401),
      new StreamHttpErrorException(500),
      new StreamException("unknown")
    };
  }
  
  @Test
  public void alwaysThrowStrategy() {
    ErrorStrategy strategy = ErrorStrategy.alwaysThrow();
    for (StreamException e: makeAllStreamExceptions()) {
      ErrorStrategy.Result result = strategy.apply(e);
      assertThat(result.getAction(), is(ErrorStrategy.Action.THROW));
      assertThat(result.getNext(), nullValue());
    }
  }
  
  @Test
  public void alwaysContinueStrategy() {
    ErrorStrategy strategy = ErrorStrategy.alwaysContinue();
    for (StreamException e: makeAllStreamExceptions()) {
      ErrorStrategy.Result result = strategy.apply(e);
      assertThat(result.getAction(), is(ErrorStrategy.Action.CONTINUE));
      assertThat(result.getNext(), nullValue());
    }
  }

  @Test
  public void continueWithMaxAttemptsStrategy() {
    int max = 3;
    for (StreamException e: makeAllStreamExceptions()) {
      ErrorStrategy strategy = ErrorStrategy.continueWithMaxAttempts(max);
      for (int i = 0; i < 3; i++) {
        ErrorStrategy.Result result = strategy.apply(e);
        assertThat(result.getAction(), is(ErrorStrategy.Action.CONTINUE));
        strategy = result.getNext() == null ? strategy : result.getNext();
      }
      assertThat(strategy.apply(e).getAction(), is(ErrorStrategy.Action.THROW));
    }
  }

  @Test
  public void continueWithMaxTimeStrategy() throws Exception {
    long maxTime = 50;
    for (StreamException e: makeAllStreamExceptions()) {
      ErrorStrategy strategy = ErrorStrategy.continueWithTimeLimit(maxTime, null);
      
      ErrorStrategy.Result result = strategy.apply(e);
      assertThat(result.getAction(), is(ErrorStrategy.Action.CONTINUE));
      strategy = result.getNext() == null ? strategy : result.getNext();

      result = strategy.apply(e);
      assertThat(result.getAction(), is(ErrorStrategy.Action.CONTINUE));
      strategy = result.getNext() == null ? strategy : result.getNext();
      
      Thread.sleep(maxTime + 1);

      result = strategy.apply(e);
      assertThat(result.getAction(), is(ErrorStrategy.Action.THROW));
      strategy = result.getNext() == null ? strategy : result.getNext();

      result = strategy.apply(e);
      assertThat(result.getAction(), is(ErrorStrategy.Action.THROW));
    }
  }

  @Test
  public void startThrowsIfConnectFailsAndStrategyReturnsThrow() throws StreamException {
    IOException fakeError = new IOException("deliberate error");
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(rejectConnection(fakeError));
    
    try (EventSource es = baseBuilder(mock)
        .errorStrategy(ErrorStrategy.alwaysThrow())
        .build()) {
      try {
        es.start();
        fail("expected exception");
      } catch (StreamIOException e) {
        assertThat(e.getIOException(), is(fakeError));
      }
    }
  }

  @Test
  public void startRetriesIfConnectFailsAndStrategyReturnsContinue() throws StreamException {
    IOException fakeError = new IOException("deliberate error");
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(
        rejectConnection(fakeError),
        rejectConnection(fakeError),
        respondWithStream());
    
    try (EventSource es = baseBuilder(mock)
        .errorStrategy(ErrorStrategy.alwaysContinue())
        .build()) {
      es.start();
    }
  }

  @Test
  public void implicitStartFromReadAnyEventReturnsFaultEventIfStrategyReturnsContinue() throws StreamException {
    IOException fakeError = new IOException("deliberate error");
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(
        rejectConnection(fakeError),
        respondWithStream());
    
    try (EventSource es = baseBuilder(mock)
        .errorStrategy(ErrorStrategy.alwaysContinue())
        .build()) {
      StreamEvent event1 = es.readAnyEvent();
      assertThat(event1, equalTo(new FaultEvent(new StreamIOException(fakeError))));

      StreamEvent event2 = es.readAnyEvent();
      assertThat(event2, equalTo(new StartedEvent()));
    }
  }

  @Test
  public void errorStrategyIsUpdatedForEachRetryDuringStart() throws StreamException {
    IOException fakeError = new IOException("deliberate error");
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(
        rejectConnection(fakeError),
        rejectConnection(fakeError),
        respondWithStream());
    ErrorStrategy continueFirstTimeButThenThrow = new ErrorStrategy() {
      @Override
      public Result apply(StreamException exception) {
        return new Result(ErrorStrategy.Action.CONTINUE,
            ErrorStrategy.alwaysThrow()); // alwaysThrow is used after the first retry
      }
    };
    
    try (EventSource es = baseBuilder(mock)
        .errorStrategy(continueFirstTimeButThenThrow)
        .build()) {
      try {
        es.start();
        fail("expected exception");
      } catch (StreamIOException e) {
        assertThat(e.getIOException(), is(fakeError));
      }
    }
  }

  @Test
  public void readThrowsIfReadFailsAndStrategyReturnsThrow() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream = respondWithStream();
    mock.configureRequests(stream);
    
    try (EventSource es = baseBuilder(mock)
        .errorStrategy(ErrorStrategy.alwaysThrow())
        .build()) {
      es.start();
      stream.close();
      try {
        es.readMessage();
        fail("expected exception");
      } catch (StreamClosedByServerException e) {}
    }
  }

  @Test
  public void readRetriesIfReadFailsAndStrategyReturnsContinue() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream1 = respondWithStream();
    mock.configureRequests(
        stream1,
        respondWithDataAndThenStayOpen("data: event1\n\n"));
    
    try (EventSource es = baseBuilder(mock)
        .errorStrategy(ErrorStrategy.alwaysContinue())
        .build()) {
      es.start();
      stream1.close();
      
      assertThat(es.readMessage(),
          Matchers.equalTo(new MessageEvent("message", "event1", null, es.getOrigin())));
    }
  }

  @Test
  public void errorStrategyIsUpdatedForEachRetryDuringRead() throws Exception {
    IOException fakeError = new IOException("deliberate error");
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream1 = respondWithStream();
    mock.configureRequests(
        stream1,
        rejectConnection(fakeError),
        respondWithDataAndThenStayOpen("data: event1\n\n"));

    ErrorStrategy continueFirstTimeButThenThrow = new ErrorStrategy() {
      @Override
      public Result apply(StreamException exception) {
        return new Result(ErrorStrategy.Action.CONTINUE,
            ErrorStrategy.alwaysThrow()); // alwaysThrow is used after the first retry
      }
    };
    
    try (EventSource es = baseBuilder(mock)
        .errorStrategy(continueFirstTimeButThenThrow)
        .build()) {
      es.start();
      stream1.close();
      
      try {
        es.readMessage();
        fail("expected exception");
      } catch (StreamIOException e) {
        assertThat(e.getIOException(), is(fakeError));
      }
    }
  }
}
