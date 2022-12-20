package com.launchdarkly.eventsource.background;

import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.MockConnectStrategy;
import com.launchdarkly.eventsource.ReadyState;
import com.launchdarkly.eventsource.StreamClosedByServerException;
import com.launchdarkly.eventsource.StreamException;
import com.launchdarkly.eventsource.StreamHttpErrorException;
import com.launchdarkly.eventsource.TestScopedLoggerRule;
import com.launchdarkly.eventsource.background.Stubs.LogItem;
import com.launchdarkly.eventsource.background.Stubs.TestHandler;
import com.launchdarkly.logging.LDLogLevel;

import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.eventsource.MockConnectStrategy.rejectConnection;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithDataAndThenEnd;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithDataAndThenStayOpen;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithStream;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

@SuppressWarnings("javadoc")
public class BackgroundEventSourceErrorHandlingTest {
  private final MockConnectStrategy mockConnect = new MockConnectStrategy();

  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();
  
  private void verifyErrorLogged(Throwable t) {
    testLogger.awaitMessageContaining(LDLogLevel.WARN, "Caught unexpected error from EventHandler: " + t);
    testLogger.awaitMessageContaining(LDLogLevel.DEBUG, "at " + this.getClass().getName());
  }
  
  private EventSource.Builder baseEventSourceBuilder() {
    return new EventSource.Builder(mockConnect)
        .retryDelay(1, null)
        .logger(testLogger.getLogger());
  }
  
  @Test
  public void errorFromOnOpenIsCaughtAndLoggedAndRedispatched() {
    Exception fakeError = new Exception("sorry");
    TestHandler testHandler = new TestHandler(testLogger.getLogger()) {
      @Override
      public void onOpen() throws Exception {
        super.onOpen();
        throw fakeError;
      }
    };
    mockConnect.configureRequests(respondWithStream());

    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        testHandler,
        baseEventSourceBuilder()
        ).build()) {
      bes.start();
      
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.opened()));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.error(fakeError)));
      verifyErrorLogged(fakeError);
    }
  }

  @Test
  public void errorFromOnMessageIsCaughtAndLoggedAndRedispatched() {
    Exception fakeError = new Exception("sorry");
    TestHandler testHandler = new TestHandler(testLogger.getLogger()) {
      @Override
      public void onMessage(String eventName, MessageEvent event) throws Exception {
        super.onMessage(eventName, event);
        throw fakeError;
      }
    };
    mockConnect.configureRequests(respondWithDataAndThenStayOpen("data: data1\n\n"));

    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        testHandler,
        baseEventSourceBuilder()
        ).build()) {
      bes.start();
      
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.opened()));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("message", "data1")));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.error(fakeError)));
      verifyErrorLogged(fakeError);
    }
  }

  @Test
  public void errorFromOnCommentIsCaughtAndLoggedAndRedispatched() {
    Exception fakeError = new Exception("sorry");
    TestHandler testHandler = new TestHandler(testLogger.getLogger()) {
      @Override
      public void onComment(String text) throws Exception {
        super.onComment(text);
        throw fakeError;
      }
    };
    mockConnect.configureRequests(respondWithDataAndThenStayOpen(":hello\n"));

    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        testHandler,
        baseEventSourceBuilder()
        ).build()) {
      bes.start();
      
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.opened()));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.comment("hello")));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.error(fakeError)));
      verifyErrorLogged(fakeError);
    }
  }

  @Test
  public void errorFromOnCloseIsCaughtAndLoggedAndRedispatched() {
    Exception fakeError = new Exception("sorry");
    TestHandler testHandler = new TestHandler(testLogger.getLogger()) {
      @Override
      public void onClosed() throws Exception {
        super.onClosed();
        throw fakeError;
      }
    };
    mockConnect.configureRequests(
        respondWithDataAndThenEnd(":hello\n"),
        respondWithStream()
        );

    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        testHandler,
        baseEventSourceBuilder()
        ).build()) {
      bes.start();
      
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.opened()));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.comment("hello")));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.error(new StreamClosedByServerException())));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.closed()));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.error(fakeError)));
      verifyErrorLogged(fakeError);
    }
  }

  @Test
  public void errorFromOnErrorIsCaughtAndLogged() {
    Exception fakeError1 = new Exception("sorry");
    RuntimeException fakeError2 = new RuntimeException("not sorry");
    TestHandler testHandler = new TestHandler(testLogger.getLogger()) {
      @Override
      public void onOpen() throws Exception {
        super.onOpen();
        throw fakeError1;
      }
      @Override
      public void onError(Throwable t) {
        super.onError(t);
        throw fakeError2;
      }
    };
    mockConnect.configureRequests(
        respondWithStream()
        );

    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        testHandler,
        baseEventSourceBuilder()
        ).build()) {
      bes.start();
      
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.opened()));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.error(fakeError1)));
      verifyErrorLogged(fakeError1);
      testLogger.awaitMessageContaining(LDLogLevel.WARN,
          "Caught unexpected error from EventHandler.onError(): " + fakeError2);
      testLogger.awaitMessageContaining(LDLogLevel.DEBUG, "at " + this.getClass().getName());
    }
  }

  @Test
  public void connectionErrorHandlerIsCalled() throws Exception {
    TestHandler testHandler = new TestHandler();
    
    BlockingQueue<Throwable> receivedError = new LinkedBlockingQueue<>();
    ConnectionErrorHandler errorHandler = err -> {
      receivedError.add(err);
      return ConnectionErrorHandler.Action.PROCEED;      
    };
    
    StreamException fakeError = new StreamHttpErrorException(400);
    mockConnect.configureRequests(
        rejectConnection(fakeError),
        respondWithStream()
        );
    
    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        testHandler,
        baseEventSourceBuilder()
        )
        .connectionErrorHandler(errorHandler)
        .build()) {
      bes.start();
      
      Throwable err = receivedError.poll(1, TimeUnit.SECONDS);
      assertThat(err, sameInstance(fakeError));
      
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.error(fakeError)));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.closed()));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.opened()));
    }
  }

  @Test
  public void connectionErrorHandlerCanForceShutdown() throws Exception {
    TestHandler testHandler = new TestHandler();
    
    BlockingQueue<Throwable> receivedError = new LinkedBlockingQueue<>();
    ConnectionErrorHandler errorHandler = err -> {
      receivedError.add(err);
      return ConnectionErrorHandler.Action.SHUTDOWN;      
    };
    
    StreamException fakeError = new StreamHttpErrorException(400);
    mockConnect.configureRequests(
        rejectConnection(fakeError),
        respondWithStream()
        );
    
    try (BackgroundEventSource bes = new BackgroundEventSource.Builder(
        testHandler,
        baseEventSourceBuilder()
        )
        .connectionErrorHandler(errorHandler)
        .build()) {
      bes.start();
      
      Throwable err = receivedError.poll(1, TimeUnit.SECONDS);
      assertThat(err, sameInstance(fakeError));
      
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.error(fakeError)));
      assertThat(testHandler.awaitLogItem(), equalTo(LogItem.closed()));
      testHandler.assertNoMoreLogItems();
      
      assertThat(bes.getEventSource().getState(), equalTo(ReadyState.SHUTDOWN));
    }
  }
}
