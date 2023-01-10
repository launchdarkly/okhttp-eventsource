package com.launchdarkly.eventsource;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.Reader;

import static com.launchdarkly.eventsource.TestUtils.makeStringOfLength;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public class EventParserStreamingDataTest extends EventParserBaseTest {
  @Test
  public void singleLineDataInSingleChunk() throws Exception {
    String streamData = "data: line1\n\n";
    initParser(20, true);
    processData(streamData);

    MessageEvent e = awaitMessageEvent();
    assertThat(e.isStreamingData(), is(true));
    assertThat(readFully(e.getDataReader()), equalTo("line1"));
    
    assertEof();
  }

  @Test
  public void singleLineDataInMultipleChunks() throws Exception {
    String streamData = "data: abcdefghijklmnopqrstuvwxyz\n\n";
    initParser(20, true);
    processData(streamData);
    
    MessageEvent e = awaitMessageEvent();
    assertThat(e.isStreamingData(), is(true));
    assertThat(readFully(e.getDataReader()), equalTo("abcdefghijklmnopqrstuvwxyz"));
    
    assertEof();
  }

  @Test
  public void multiLineDataInSingleChunk() throws Exception {
    String streamData = "data: line1\ndata: line2\n\n";
    initParser(100, true);
    processData(streamData);

    MessageEvent e = awaitMessageEvent();
    assertThat(e.isStreamingData(), is(true));
    assertThat(readFully(e.getDataReader()), equalTo("line1\nline2"));

    assertEof();
  }

  @Test
  public void multiLineDataInMultipleChunks() throws Exception {
    String streamData = "data: abcdefghijklmnopqrstuvwxyz\ndata: 1234567890\n\n";
    initParser(20, true);
    processData(streamData);

    MessageEvent e = awaitMessageEvent();
    assertThat(e.isStreamingData(), is(true));
    assertThat(readFully(e.getDataReader()), equalTo("abcdefghijklmnopqrstuvwxyz\n1234567890"));

    assertEof();
  }
  
  @Test
  public void eventNameAndIdArePreservedIfTheyAreBeforeData() throws Exception {
    String streamData = "event: hello\nid: id1\ndata: line1\n\n";
    initParser(100, true);
    processData(streamData);

    MessageEvent e = awaitMessageEvent();
    assertThat(e.isStreamingData(), is(true));
    assertThat(readFully(e.getDataReader()), equalTo("line1"));
    assertThat(e.getEventName(), equalTo("hello"));
    assertThat(e.getLastEventId(), equalTo("id1"));

    assertEof();
  }

  @Test
  public void eventNameAndIdAreIgnoredIfTheyAreAfterData() throws Exception {
    String streamData = "data: line1\nevent: hello\nid: id1\n\n";
    initParser(100, true);
    processData(streamData);

    MessageEvent e = awaitMessageEvent();
    assertThat(e.isStreamingData(), is(true));
    assertThat(readFully(e.getDataReader()), equalTo("line1"));
    assertThat(e.getEventName(), equalTo(MessageEvent.DEFAULT_EVENT_NAME));
    assertThat(e.getLastEventId(), nullValue());
    
    assertEof();
  }
  
  @Test
  public void canRequireEventName() throws Exception {
    String streamData = "data: line1\nevent: hello\nid: id1\n\n" +
        "event: world\ndata: line2\nid: id2\n\n";
    initParser(100, true, "event");
    processData(streamData);

    MessageEvent e1 = awaitMessageEvent();
    assertThat(e1.isStreamingData(), is(false));
    assertThat(readFully(e1.getDataReader()), equalTo("line1"));
    assertThat(e1.getEventName(), equalTo("hello"));
    assertThat(e1.getLastEventId(), equalTo("id1"));

    MessageEvent e2 = awaitMessageEvent();
    assertThat(e2.isStreamingData(), is(true));
    assertThat(readFully(e2.getDataReader()), equalTo("line2"));
    assertThat(e2.getEventName(), equalTo("world"));
    assertThat(e2.getLastEventId(), equalTo("id1")); // "id: id2" was ignored because it came after "data:"

    assertEof();
  }

  @Test
  public void canRequireEventId() throws Exception {
    String streamData = "data: line1\nevent: hello\nid: id1\n\n" +
        "id: id2\ndata: line2\nevent: world\n\n";
    initParser(100, true, "id");
    processData(streamData);

    MessageEvent e1 = awaitMessageEvent();
    assertThat(e1.isStreamingData(), is(false));
    assertThat(readFully(e1.getDataReader()), equalTo("line1"));
    assertThat(e1.getEventName(), equalTo("hello"));
    assertThat(e1.getLastEventId(), equalTo("id1"));

    MessageEvent e2 = awaitMessageEvent();
    assertThat(e2.isStreamingData(), is(true));
    assertThat(readFully(e2.getDataReader()), equalTo("line2"));
    assertThat(e2.getEventName(), equalTo(MessageEvent.DEFAULT_EVENT_NAME));
    assertThat(e2.getLastEventId(), equalTo("id2"));

    assertEof();
  }

  @Test
  public void chunkSizeIsGreaterThanReaderBufferSize() throws Exception {
    String s = makeStringOfLength(11000);
    String streamData = "data: " + s + "\n\n";
    initParser(10000, true);
    processData(streamData);

    MessageEvent e1 = awaitMessageEvent();
    assertThat(e1.isStreamingData(), is(true));
    assertThat(readFully(e1.getDataReader()), equalTo(s));
  }

  @Test
  public void invalidLineWithinEvent() throws Exception {
    initParser(20, true);
    processData("data: data1\nignorethis: meaninglessline\ndata: data2\n\n");

    MessageEvent e1 = awaitMessageEvent();
    assertThat(e1.isStreamingData(), is(true));
    assertThat(readFully(e1.getDataReader()), equalTo("data1\ndata2"));
  }

  @Test
  public void incompletelyReadEventIsSkippedIfAnotherMessageIsRead() throws Exception {
    String streamData = "data: hello1\ndata: hello2\nevent: hello\nid: id1\n\n" +
        "data: world\n\n";
    initParser(100, true);
    processData(streamData);

    MessageEvent e1 = awaitMessageEvent();
    assertThat(e1.isStreamingData(), is(true));
    assertThat(readUpToLimit(e1.getDataReader(), 2), equalTo("he"));

    MessageEvent e2 = awaitMessageEvent();
    assertThat(readFully(e2.getDataReader()), equalTo("world"));

    assertEof();
  }

  @Test
  public void streamIsClosedImmediatelyAfterEndOfEvent() throws Exception {
    String streamData = "data: hello\n\n";
    initParser(100, true);
    processData(streamData);

    MessageEvent e1 = awaitMessageEvent();
    assertThat(e1.isStreamingData(), is(true));
    assertThat(readUpToLimit(e1.getDataReader(), 5), equalTo("hello"));

    closeStream();
    
    assertThat(e1.getDataReader().read(), equalTo(-1)); // normal EOF, not an error
  }

  @Test
  public void streamIsClosedBeforeEndOfEventAtEndOfLine() throws Exception {
    String streamData = "data: hello\n";
    initParser(100, true);
    processData(streamData);

    MessageEvent e1 = awaitMessageEvent();
    assertThat(e1.isStreamingData(), is(true));
    assertThat(readUpToLimit(e1.getDataReader(), 5), equalTo("hello"));

    closeStream();
    
    try {
      e1.getDataReader().read();
      fail("expected exception");
    } catch (StreamClosedWithIncompleteMessageException e) {}
  }

  @Test
  public void streamIsClosedBeforeEndOfEventWithinLine() throws Exception {
    String streamData = "data: hello";
    initParser(100, true);
    processData(streamData);

    MessageEvent e1 = awaitMessageEvent();
    assertThat(e1.isStreamingData(), is(true));
    assertThat(readUpToLimit(e1.getDataReader(), 5), equalTo("hello"));

    closeStream();
    
    try {
      e1.getDataReader().read();
      fail("expected exception");
    } catch (StreamClosedWithIncompleteMessageException e) {}
  }

  @Test
  public void redundantMessageCloseHasNoEffect() throws Exception {
    String streamData = "data: hello\n\ndata: world\n\n";
    initParser(100, true);
    processData(streamData);

    MessageEvent e1 = awaitMessageEvent();
    assertThat(e1.isStreamingData(), is(true));
    assertThat(readUpToLimit(e1.getDataReader(), 2), equalTo("he"));
    e1.close();
    e1.close();

    MessageEvent e2 = awaitMessageEvent();
    assertThat(e2.getData(), equalTo("world"));

    assertEof();
  }

  private String readFully(Reader reader) {
    return readUpToLimit(reader, 0);
  }
  
  private String readUpToLimit(Reader reader, int limit) {
    char[] chunk = new char[1000];
    StringBuilder sb = new StringBuilder();
    while (true) {
      try {
        int n = reader.read(chunk, 0, limit > 0 ? (limit - sb.length()) : chunk.length);
        if (n < 0) {
          break;
        }
        sb.append(new String(chunk, 0, n));
        if (limit > 0 && sb.length() >= limit) {
          break;
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    return sb.toString();
  }
}
