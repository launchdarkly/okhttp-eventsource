package com.launchdarkly.eventsource;

import org.junit.Before;
import org.junit.Rule;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public abstract class EventParserBaseTest {
  protected static final URI ORIGIN = URI.create("http://host.com:99/foo");
  
  protected EventParser parser;
  private PipedOutputStream writeStream;
  private InputStream readStream;

  @Rule public TestScopedLoggerRule testLoggerRule = new TestScopedLoggerRule();
  
  @Before
  public void setUp() throws Exception {
    writeStream = new PipedOutputStream();
    readStream = new PipedInputStream(writeStream);
  }

  protected void initParser(int bufferSize, boolean streamEventData, String... expectFieldNames) {
    Set<String> expectedFields = null;
    if (expectFieldNames.length != 0) {
      expectedFields = new HashSet<>();
      for (String f: expectFieldNames) {
        expectedFields.add(f);
      }
    }
    parser = new EventParser(
        readStream,
        ORIGIN,
        bufferSize,
        streamEventData,
        expectedFields,
        testLoggerRule.getLogger()
        );
  }
  
  protected void processLines(String... lines) throws Exception {
    new Thread(() -> {
      try {
        for (String line: lines) {
          writeStream.write((line + "\n").getBytes());
          writeStream.flush();
        }
        writeStream.close();
      } catch (IOException e) {}
    }).start();
  }

  protected void processData(String chunk) throws Exception {
    new Thread(() -> {
      try {
        writeStream.write(chunk.getBytes());
        writeStream.flush();
        writeStream.close();
      } catch (IOException e) {}
    }).start();
  }
  
  protected void closeStream() throws IOException {
    writeStream.close();
  }
  
  protected StreamEvent awaitEvent() {
    while (true) {
      try {
        StreamEvent event = parser.nextEvent();
        if (event != null) {
          return event;
        }
      } catch (StreamException e) {
        fail("expected an event but got: " + e);
      }
    }
  }

  protected MessageEvent awaitMessageEvent() {
    StreamEvent event = awaitEvent();
    assertThat(event.getClass(), equalTo(MessageEvent.class));
    return (MessageEvent)event;
  }
  
  protected void assertEof() {
    while (true) {
      try {
        StreamEvent event = parser.nextEvent();
        assertNull("expected end of stream but got an event", event);
      } catch (StreamClosedByServerException e) {
        return;
      } catch (StreamException e) {
        assertNull("expected just an EOF but got: ", e);
      }
    }
  }
}
