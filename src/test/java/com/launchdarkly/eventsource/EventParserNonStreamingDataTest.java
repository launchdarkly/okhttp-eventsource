package com.launchdarkly.eventsource;

import org.junit.Before;
import org.junit.Test;

import static com.launchdarkly.eventsource.TestUtils.makeStringOfLength;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@SuppressWarnings("javadoc")
public class EventParserNonStreamingDataTest extends EventParserBaseTest {
  private static final int BUFFER_SIZE = 200;
  
  @Before
  public void setUpNonStreamingParser() {
    initParser(BUFFER_SIZE, false);
  }
  
  @Test
  public void dispatchesSingleLineMessage() throws Exception {
    processLines("data: hello", "");

    assertThat(awaitEvent(), equalTo(new MessageEvent("message", "hello", null, ORIGIN)));
    assertEof();
  }

  @Test
  public void doesntFireMultipleTimesIfSeveralEmptyLines() throws Exception {
    processLines("data: hello", "", "");

    assertThat(awaitEvent(), equalTo(new MessageEvent("message", "hello", null, ORIGIN)));
    assertEof();
  }

  @Test
  public void dispatchesSingleLineMessageWithId() throws Exception {
    processLines("data: hello", "id: 1", "");

    assertThat(awaitEvent(), equalTo(new MessageEvent("message", "hello", "1", ORIGIN)));
    assertEof();
  }

  @Test
  public void dispatchesSingleLineMessageWithCustomEvent() throws Exception {
    processLines("data: hello", "event: beeroclock", "");

    assertThat(awaitEvent(), equalTo(new MessageEvent("beeroclock", "hello", null, ORIGIN)));
    assertEof();
  }

  @Test
  public void sendsCommentsForLinesStartingWithColon() throws Exception {
    processLines(": first comment", "data: hello", ": second comment", "");

    assertThat(awaitEvent(), equalTo(new CommentEvent("first comment")));
    assertThat(awaitEvent(), equalTo(new CommentEvent("second comment")));
    assertThat(awaitEvent(), equalTo(new MessageEvent("message", "hello", null, ORIGIN)));
    // The message is received after the two comments, rather than interleaved between
    // them, because we can't know that the message is actually finished until we see
    // the blank line after the second comment.
    assertEof();
  }

  @Test
  public void dispatchesSingleLineMessageWithoutColon() throws Exception {
    processLines("data", "");

    assertThat(awaitEvent(), equalTo(new MessageEvent("message", "", null, ORIGIN)));
    assertEof();
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
    
    assertThat(awaitEvent(), equalTo(new MessageEvent("hello", "data1", null, ORIGIN)));
    assertThat(awaitEvent(), equalTo(new MessageEvent("message", "data2", null, ORIGIN)));
    assertEof();
  }
  
  @Test
  public void setsRetryTimeToSevenSeconds() throws Exception {
    processLines("retry: 7000",
        ": comment", // so we know when the first line has definitely been processed
        "");

    assertThat(awaitEvent(), equalTo(new SetRetryDelayEvent(7000)));
    assertThat(awaitEvent(), equalTo(new CommentEvent("comment")));
    assertEof();
  }

  @Test
  public void doesntSetRetryTimeUnlessEntireValueIsNumber() throws Exception {
    processLines("retry: 7000L",
        ": comment", // so we know when the first line has definitely been processed
        "");

    assertThat(awaitEvent(), equalTo(new CommentEvent("comment")));
    assertEof();
  }

  @Test
  public void ignoresUnknownFieldName() throws Exception {
    processLines("data: hello", "badfield: whatever", "id: 1", "");

    assertThat(awaitEvent(), equalTo(new MessageEvent("message", "hello", "1", ORIGIN)));
    assertEof();
  }

  @Test
  public void usesTheEventIdOfPreviousEventIfNoneSet() throws Exception {
    processLines("data: hello", "id: reused", "", "data: world", "");

    assertThat(awaitEvent(), equalTo(new MessageEvent("message", "hello", "reused", ORIGIN)));
    assertThat(awaitEvent(), equalTo(new MessageEvent("message", "world", "reused", ORIGIN)));
    assertEof();
  }

  @Test
  public void filtersOutFirstSpace() throws Exception {
    processLines("data: {\"foo\": \"bar baz\"}", "");

    assertThat(awaitEvent(), equalTo(new MessageEvent("message", "{\"foo\": \"bar baz\"}", null, ORIGIN)));
    assertEof();
  }

  @Test
  public void dispatchesEmptyData() throws Exception {
    processLines("data:", "");

    assertThat(awaitEvent(), equalTo(new MessageEvent("message", "", null, ORIGIN)));
    assertEof();
  }
  
  @Test
  public void idIsIgnoredIfItContainsANullCharacter() throws Exception {
    processLines("id: ab\u0000c", "data: x", "");

    assertThat(awaitEvent(), equalTo(new MessageEvent("message", "x", null, ORIGIN)));
    assertEof();
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
    
    assertThat(awaitEvent(), equalTo(new MessageEvent(eventName, data, id, ORIGIN)));
    assertEof();
  }
}
