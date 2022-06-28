package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.Stubs.LogItem;
import com.launchdarkly.eventsource.Stubs.TestHandler;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@SuppressWarnings("javadoc")
public class EventParserBasicTest {
  private static final URI ORIGIN = URI.create("http://host.com:99/foo");
  private static final int BUFFER_SIZE = 200;
  
  private TestHandler testHandler;
  private ConnectionHandler connectionHandler;
  private EventParser parser;
  private PipedOutputStream writeStream;
  private InputStream readStream;
  private TestScopedLoggerRule.TestLogger testLogger;
  private int generatedStringCounter;
  
  @Rule public TestScopedLoggerRule testLoggerRule = new TestScopedLoggerRule();
  
  @Before
  public void setUp() throws Exception {
    testLogger = testLoggerRule.getLogger();
    testHandler = new TestHandler(testLogger);
    connectionHandler = mock(ConnectionHandler.class);
    writeStream = new PipedOutputStream();
    readStream = new PipedInputStream(writeStream);
    parser = new EventParser(
        readStream,
        ORIGIN,
        testHandler,
        connectionHandler,
        BUFFER_SIZE,
        false,
        testLogger
        );
  }

  private void processLines(String... lines) throws Exception {
    new Thread(() -> {
      try {
        for (String line: lines) {
          writeStream.write((line + "\n").getBytes());
          writeStream.flush();
        }
        writeStream.close();
      } catch (IOException e) {}
    }).start();
    while (parser.processStream()) {}
  }
  
  @Test
  public void dispatchesSingleLineMessage() throws Exception {
    processLines("data: hello", "");

    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("message", "hello")));
  }

  @Test
  public void doesntFireMultipleTimesIfSeveralEmptyLines() throws Exception {
    processLines("data: hello", "", "");

    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("message", "hello")));
    testHandler.assertNoMoreLogItems();
  }

  @Test
  public void dispatchesSingleLineMessageWithId() throws Exception {
    processLines("data: hello", "id: 1", "");

    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("message", "hello", "1")));
  }

  @Test
  public void dispatchesSingleLineMessageWithCustomEvent() throws Exception {
    processLines("data: hello", "event: beeroclock", "");

    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("beeroclock", "hello")));
  }

  @Test
  public void sendsCommentsForLinesStartingWithColon() throws Exception {
    processLines(": first comment", "data: hello", ": second comment", "");

    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.comment("first comment")));
    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.comment("second comment")));
    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("message", "hello")));
  }

  @Test
  public void dispatchesSingleLineMessageWithoutColon() throws Exception {
    processLines("data", "");

    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("message", "")));
  }

  @Test
  public void propertiesAreResetBetweenMultipleMessages() throws Exception {
    processLines(
        "event: hello",
        "data: data1",
        "",
        "data: data2",
        ""
        );
    
    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("hello", "data1")));
    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("message", "data2")));
  }
  
  @Test
  public void setsRetryTimeToSevenSeconds() throws Exception {
    processLines("retry: 7000",
        ": comment", // so we know when the first line has definitely been processed
        "");

    testHandler.awaitLogItem(); // the comment line has been processed
    verify(connectionHandler).setReconnectionTime(Duration.ofMillis(7000));
  }

  @Test
  public void doesntSetRetryTimeUnlessEntireValueIsNumber() throws Exception {
    processLines("retry: 7000L",
        ": comment", // so we know when the first line has definitely been processed
        "");

    testHandler.awaitLogItem(); // the comment line has been processed
    verifyNoMoreInteractions(connectionHandler);
  }

  @Test
  public void ignoresUnknownFieldName() throws Exception {
    processLines("data: hello", "badfield: whatever", "id: 1", "");

    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("message", "hello", "1")));
  }

  @Test
  public void usesTheEventIdOfPreviousEventIfNoneSet() throws Exception {
    processLines("data: hello", "id: reused", "", "data: world", "");

    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("message", "hello", "reused")));
    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("message", "world", "reused")));
  }

  @Test
  public void filtersOutFirstSpace() throws Exception {
    processLines("data: {\"foo\": \"bar baz\"}", "");

    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("message", "{\"foo\": \"bar baz\"}")));
  }

  @Test
  public void dispatchesEmptyData() throws Exception {
    processLines("data:", "");

    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("message", "")));
  }
  
  @Test
  public void fieldsCanBeSplitAcrossChunks() throws Exception {
    // This field starts in one chunk and finishes in the next
    String eventName = makeStringOfLength(BUFFER_SIZE + (BUFFER_SIZE / 5));
    
    // This field spans multiple chunks and is also longer than VALUE_BUFFER_INITIAL_CAPACITY,
    // so we're verifying that we correctly recreate the buffer afterward
    String id = makeStringOfLength(EventParser.VALUE_BUFFER_INITIAL_CAPACITY + (BUFFER_SIZE / 5));
    
    // Same idea as above, because we know there is a separate buffer for the data field
    String data = makeStringOfLength(EventParser.VALUE_BUFFER_INITIAL_CAPACITY + (BUFFER_SIZE / 5));
    
    // Here we have a field whose name is longer than the buffer, to test our "skip rest of line" logic
    String longInvalidFieldName = makeStringOfLength(BUFFER_SIZE * 2 + (BUFFER_SIZE / 5))
        .replace(':', '_'); // ensure there isn't a colon within the name
    String longInvalidFieldValue = makeStringOfLength(BUFFER_SIZE * 2 + (BUFFER_SIZE / 5));

    // This one tests the logic where we are able to parse the field name right away, but the value is long
    String shortInvalidFieldName = "whatever";

    processLines(
        "event: " + eventName,
        "data: " + data,
        "id: " + id,
        shortInvalidFieldName + ": " + longInvalidFieldValue,
        longInvalidFieldName + ": " + longInvalidFieldValue,
        ""
        );
    
    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event(eventName, data, id)));
  }
  
  @Test
  public void catchesAndRedispatchesErrorFromHandlerOnMessage() throws Exception {
    RuntimeException err = new RuntimeException("sorry");
    testHandler.fakeError = err;
    
    processLines("data: hello", "");

    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.event("message", "hello")));
    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.error(err)));
    
    testLogger.awaitMessageMatching("WARN: Message handler threw an exception: " + err.toString());
    testLogger.awaitMessageMatching("DEBUG: Stack trace:");
  }
  
  @Test
  public void catchesAndRedispatchesErrorFromHandlerOnComment() throws Exception {
    RuntimeException err = new RuntimeException("sorry");
    testHandler.fakeError = err;
      
    processLines(":hello");

    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.comment("hello")));
    assertThat(testHandler.awaitLogItem(), equalTo(LogItem.error(err)));
    
    testLogger.awaitMessageMatching("WARN: Message handler threw an exception: " + err.toString());
    testLogger.awaitMessageMatching("DEBUG: Stack trace:");
  }
  
  private String makeStringOfLength(int n) {
    int offset = generatedStringCounter++;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append((char)('!' + (i + offset) % ('~' - '!' + 1)));
    }
    return sb.toString();
  }
}
