package com.launchdarkly.eventsource;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.net.URI;
import java.time.Duration;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;


/**
 * Adapted from https://github.com/aslakhellesoy/eventsource-java/blob/master/src/test/java/com/github/eventsource/client/EventStreamParserTest.java
 */
@SuppressWarnings("javadoc")
public class EventParserTest {
  private static final URI ORIGIN = URI.create("http://host.com:99/foo");
  private EventHandler eventHandler;
  private ConnectionHandler connectionHandler;
  private EventParser parser;

  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();
  
  @Before
  public void setUp() throws Exception {
    eventHandler = mock(EventHandler.class);
    connectionHandler = mock(ConnectionHandler.class);
    parser = new EventParser(ORIGIN, eventHandler, connectionHandler, testLogger.getLogger());
  }

  @Test
  public void dispatchesSingleLineMessage() throws Exception {
    parser.line("data: hello");
    parser.line("");

    verify(eventHandler).onMessage(eq("message"), eq(new MessageEvent("hello", null, ORIGIN)));
  }

  @Test
  public void doesntFireMultipleTimesIfSeveralEmptyLines() throws Exception {
    parser.line("data: hello");
    parser.line("");
    parser.line("");

    verify(eventHandler).onMessage(eq("message"), eq(new MessageEvent("hello", null, ORIGIN)));
    verifyNoMoreInteractions(eventHandler);
  }

  @Test
  public void dispatchesSingleLineMessageWithId() throws Exception {
    parser.line("data: hello");
    parser.line("id: 1");
    parser.line("");

    verify(eventHandler).onMessage(eq("message"), eq(new MessageEvent("hello", "1", ORIGIN)));
  }

  @Test
  public void dispatchesSingleLineMessageWithCustomEvent() throws Exception {
    parser.line("data: hello");
    parser.line("event: beeroclock");
    parser.line("");

    verify(eventHandler).onMessage(eq("beeroclock"), eq(new MessageEvent("hello", null, ORIGIN)));
  }

  @Test
  public void sendsCommentsForLinesStartingWithColon() throws Exception {
    parser.line(": first comment");
    parser.line("data: hello");
    parser.line(": second comment");
    parser.line("");

    verify(eventHandler).onMessage(eq("message"), eq(new MessageEvent("hello", null, ORIGIN)));
    verify(eventHandler).onComment(eq("first comment"));
    verify(eventHandler).onComment(eq("second comment"));
  }

  @Test
  public void dispatchesSingleLineMessageWithoutColon() throws Exception {
    parser.line("data");
    parser.line("");

    verify(eventHandler).onMessage(eq("message"), eq(new MessageEvent("", null, ORIGIN)));
  }

  @Test
  public void setsRetryTimeToSevenSeconds() throws Exception {
    parser.line("retry: 7000");
    parser.line("");

    verify(connectionHandler).setReconnectionTime(Duration.ofMillis(7000));
  }

  @Test
  public void doesntSetRetryTimeUnlessEntireValueIsNumber() throws Exception {
    parser.line("retry: 7000L");
    parser.line("");

    verifyNoMoreInteractions(eventHandler);
  }

  @Test
  public void ignoresUnknownFieldName() throws Exception {
    parser.line("data: hello");
    parser.line("badfield: whatever");
    parser.line("id: 1");
    parser.line("");

    verify(eventHandler).onMessage(eq("message"), eq(new MessageEvent("hello", "1", ORIGIN)));
  }

  @Test
  public void usesTheEventIdOfPreviousEventIfNoneSet() throws Exception {
    parser.line("data: hello");
    parser.line("id: reused");
    parser.line("");
    parser.line("data: world");
    parser.line("");

    verify(eventHandler).onMessage(eq("message"), eq(new MessageEvent("hello", "reused", ORIGIN)));
    verify(eventHandler).onMessage(eq("message"), eq(new MessageEvent("world", "reused", ORIGIN)));
  }

  @Test
  public void filtersOutFirstSpace() throws Exception {
    parser.line("data: {\"foo\": \"bar baz\"}");
    parser.line("");

    verify(eventHandler).onMessage(eq("message"), eq(new MessageEvent("{\"foo\": \"bar baz\"}", null, ORIGIN)));
    verifyNoMoreInteractions(eventHandler);
  }

  @Test
  public void keepsDataIntact() throws Exception {
    parser.line("data:{\"foo\": \"bar baz\"}");
    parser.line("");

    verify(eventHandler).onMessage(eq("message"), eq(new MessageEvent("{\"foo\": \"bar baz\"}", null, ORIGIN)));
    verifyNoMoreInteractions(eventHandler);
  }

  @Test
  public void dispatchesEmptyData() throws Exception {
    parser.line("data:");
    parser.line("");

    verify(eventHandler).onMessage(eq("message"), eq(new MessageEvent("", null, ORIGIN)));
    verifyNoMoreInteractions(eventHandler);
  }
  
  @Test
  public void catchesAndRedispatchesErrorFromHandlerOnMessage() throws Exception {
    RuntimeException err = new RuntimeException("sorry");
    doThrow(err).when(eventHandler).onMessage(eq("message"), any(MessageEvent.class));
    
    parser.line("data: hello");
    parser.line("");

    verify(eventHandler).onError(err);
  }
  
  @Test
  public void errorFromHandlerOnMessageIsLogged() throws Exception {
    Logger mockLogger = mock(Logger.class);
    EventParser ep = new EventParser(ORIGIN, eventHandler, connectionHandler, mockLogger);
    
    RuntimeException err = new RuntimeException("sorry");
    doThrow(err).when(eventHandler).onMessage(eq("message"), any(MessageEvent.class));
    
    ep.line("data: hello");
    ep.line("");

    verify(mockLogger).debug("Parsing line: {}", "data: hello");
    verify(mockLogger).debug("Parsing line: {}", "");
    verify(mockLogger).debug("Dispatching message: \"{}\", {}", "message", new MessageEvent("hello", null, ORIGIN));
    verify(mockLogger).warn("Message handler threw an exception: " + err.toString());
    verify(mockLogger).debug(eq("Stack trace: {}"), any(LazyStackTrace.class));
  }
  
  @Test
  public void catchesAndRedispatchesErrorFromHandlerOnComment() throws Exception {
    RuntimeException err = new RuntimeException("sorry");
    doThrow(err).when(eventHandler).onComment(any(String.class));
    
    parser.line(": comment");

    verify(eventHandler).onError(err);
  }
}
