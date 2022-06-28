package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.Stubs.MessageSink;
import com.launchdarkly.eventsource.Stubs.StubConnectionHandler;

import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@SuppressWarnings("javadoc")
public class EventParserDataStreamingTest {
  private static final URI ORIGIN = URI.create("http://host.com:99/foo");

  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();
  
  @Test
  public void singleLineDataInSingleChunk() throws Exception {
    String streamData = "data: line1\n\n";
    MessageSink sink = new MessageSink();
    startParser(streamData, 20, sink);

    MessageEvent e = sink.awaitEvent();
    assertThat(e.isStreamingData(), is(true));
    assertThat(readFully(e.getDataReader()), equalTo("line1"));
  }

  @Test
  public void singleLineDataInMultipleChunks() throws Exception {
    String streamData = "data: abcdefghijklmnopqrstuvwxyz\n\n";
    MessageSink sink = new MessageSink();
    startParser(streamData, 20, sink);
    
    MessageEvent e = sink.awaitEvent();
    assertThat(e.isStreamingData(), is(true));
    assertThat(readFully(e.getDataReader()), equalTo("abcdefghijklmnopqrstuvwxyz"));
  }

  @Test
  public void multiLineDataInSingleChunk() throws Exception {
    String streamData = "data: line1\ndata: line2\n\n";
    MessageSink sink = new MessageSink();
    startParser(streamData, 100, sink);

    MessageEvent e = sink.awaitEvent();
    assertThat(e.isStreamingData(), is(true));
    assertThat(readFully(e.getDataReader()), equalTo("line1\nline2"));
  }

  @Test
  public void multiLineDataInMultipleChunks() throws Exception {
    String streamData = "data: abcdefghijklmnopqrstuvwxyz\ndata: 1234567890\n\n";
    MessageSink sink = new MessageSink();
    startParser(streamData, 20, sink);

    MessageEvent e = sink.awaitEvent();
    assertThat(e.isStreamingData(), is(true));
    assertThat(readFully(e.getDataReader()), equalTo("abcdefghijklmnopqrstuvwxyz\n1234567890"));
  }

  @Test
  public void eventNameAndIdArePreservedIfTheyAreBeforeData() throws Exception {
    String streamData = "event: hello\nid: id1\ndata: line1\n\n";
    MessageSink sink = new MessageSink();
    startParser(streamData, 100, sink);

    MessageEvent e = sink.awaitEvent();
    assertThat(e.isStreamingData(), is(true));
    assertThat(readFully(e.getDataReader()), equalTo("line1"));
    assertThat(e.getEventName(), equalTo("hello"));
    assertThat(e.getLastEventId(), equalTo("id1"));

    sink.assertNoMoreEvents();
  }

  @Test
  public void eventNameAndIdAreIgnoredIfTheyAreAfterData() throws Exception {
    String streamData = "data: line1\nevent: hello\nid: id1\n\n";
    MessageSink sink = new MessageSink();
    startParser(streamData, 100, sink);

    MessageEvent e = sink.awaitEvent();
    assertThat(e.isStreamingData(), is(true));
    assertThat(readFully(e.getDataReader()), equalTo("line1"));
    assertThat(e.getEventName(), equalTo(MessageEvent.DEFAULT_EVENT_NAME));
    assertThat(e.getLastEventId(), nullValue());
  
    sink.assertNoMoreEvents();
}

  private void startParser(String streamData, int bufferSize, MessageSink sink) {
    new Thread(() -> {
      EventParser parser = new EventParser(
          new ByteArrayInputStream(streamData.getBytes()),
          ORIGIN,
          sink,
          new StubConnectionHandler(),
          bufferSize,
          true,
          testLogger.getLogger()
          );
      while (!parser.isEof()) {
        try {
          parser.processStream();
        } catch (IOException e) {}
      }
    }).run();
  }
  
  private String readFully(Reader reader) {
    char[] chunk = new char[1000];
    StringBuilder sb = new StringBuilder();
    while (true) {
      try {
        int n = reader.read(chunk);
        if (n < 0) {
          return sb.toString();
        }
        sb.append(new String(chunk, 0, n));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
