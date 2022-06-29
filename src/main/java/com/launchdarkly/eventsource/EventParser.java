package com.launchdarkly.eventsource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

import static com.launchdarkly.eventsource.Helpers.UTF8;

/**
 * All of the SSE parsing logic is implemented in this class (except for the detection of line
 * endings, which is in BufferedLineParser). 
 */
final class EventParser {
  static final int VALUE_BUFFER_INITIAL_CAPACITY = 1000;
  private static final int MIN_READ_BUFFER_SIZE = 200;
  
  private static final String DATA = "data";
  private static final String EVENT = "event";
  private static final String ID = "id";
  private static final String RETRY = "retry";
  private static final String COMMENT = "";

  private static final Pattern DIGITS_ONLY = Pattern.compile("^[\\d]+$");

  private final EventHandler handler;
  private final ConnectionHandler connectionHandler;
  private final boolean streamEventData;
  private Set<String> expectFields;
  private final Logger logger;
  private final URI origin;

  private BufferedLineParser lineParser;
  
  private ByteArrayOutputStream dataBuffer;  // accumulates "data" lines if we are not using streaming mode
  private ByteArrayOutputStream valueBuffer; // used whenever a field other than "data" has a value longer than one chunk

  private boolean haveData;         // true if we have seen at least one "data" line so far in this event
  private boolean dataLineEnded;    // true if the previous chunk of "data" ended in a line terminator
  private PipedOutputStream writingDataStream; // destination for the current event's data if we are using streaming mode

  private String fieldName;       // name of the field we are currently parsing (might be spread across multiple chunks)
  private String lastEventId;     // value of "id:" field in this event, if any
  private String eventName;       // value of "event:" field in this event, if any
  private boolean skipRestOfLine; // true if we are skipping over an invalid line

  EventParser(
      InputStream inputStream,
      URI origin,
      EventHandler handler,
      ConnectionHandler connectionHandler,
      int readBufferSize,
      boolean streamEventData,
      Set<String> expectFields,
      Logger logger
      ) {
    this.lineParser = new BufferedLineParser(inputStream,
        readBufferSize < MIN_READ_BUFFER_SIZE ? MIN_READ_BUFFER_SIZE : readBufferSize);
    this.handler = handler;
    this.origin = origin;
    this.connectionHandler = connectionHandler;
    this.streamEventData = streamEventData;
    this.expectFields = expectFields;
    this.logger = logger;
    
    dataBuffer = new ByteArrayOutputStream(VALUE_BUFFER_INITIAL_CAPACITY);
  }

  /**
   * Returns true if the input stream has run out.
   * 
   * @return true if at end of stream
   */
  public boolean isEof() {
    return lineParser.isEof();
  }
  
  /**
   * Attempts to get more data from the stream, and dispatches events as appropriate.
   * 
   * @return true if some data was received; false if at end of stream
   * @throws IOException if the stream throws an I/O error
   */
  public boolean processStream() throws IOException {
    // Calling lineParser.read() gives us either a chunk of data that was terminated by a
    // line ending, or a chunk of data that was as much as the input buffer would hold.
    boolean lineEnded = lineParser.read();
    byte[] chunkData = lineParser.getBuffer();
    int chunkOffset = lineParser.getChunkOffset();
    int chunkSize = lineParser.getChunkSize();
    
    if (skipRestOfLine) {
      // If we're in this state, it means we already know we want to ignore this line and
      // not bother buffering or parsing the rest of it - just keep reading till we see a
      // line terminator. We do this if we couldn't find a colon in the first chunk we read,
      // meaning that the field name can't possibly be valid even if there is a colon later.
      skipRestOfLine = !lineEnded;
      return true;
    }
    
    if (chunkSize == 0) {
      if (lineEnded) {
        eventTerminated();
        return true;
      }
      // If no line terminator was reached, then the BufferedLineParser should have read
      // *some* data, so chunkSize should not be zero unless we're at EOF. However, in a
      // test scenario where the stream is something other than an HTTP response, reads
      // might not be blocking and so this may just indicate "no data available right now".
      return false;
    }
    
    int valueOffset = chunkOffset, valueLength = chunkSize;
    if (fieldName == null) { // we haven't yet parsed the field name 
      int nameLength = 0;
      for (; nameLength < chunkSize && chunkData[chunkOffset + nameLength] != ':'; nameLength++) {}
      resetValueBuffer();
      if (nameLength == chunkSize) {
        // We didn't find a colon. Since the capacity of our line buffer is always greater
        // than the length of the longest valid SSE field name plus a colon, the chunk that
        // we have now is either a field name with no value... or, if we haven't yet hit a
        // line terminator, it could be an extremely long field name that didn't fit in the
        // buffer, but in that case it is definitely not a real SSE field since those all
        // have short names, so then we know we can skip the rest of this line.
        if (!lineEnded) {
          skipRestOfLine = true;
          return true;
        }
      }
      fieldName = nameLength == 0 ? "" : new String(chunkData, chunkOffset, nameLength, UTF8);
      if (nameLength < chunkSize) {
        nameLength++;
        if (nameLength < chunkSize && chunkData[chunkOffset + nameLength] == ' ') {
          // Skip exactly one leading space at the start of the value, if any
          nameLength++;
        }
      }
      valueOffset += nameLength;
      valueLength -= nameLength;
    }
    
    if (fieldName.equals(DATA)) {
      if (writingDataStream != null) {
        // We have already started streaming data for this event.
        try {
          if (dataLineEnded) {
            writingDataStream.write('\n');
          }
          writingDataStream.write(chunkData, valueOffset, valueLength);
        } catch (IOException e) {} // ignore error if the pipe has been closed by the reader
      } else {
        // We have not already started streaming data for this event. Should we?
        if (canStreamEventDataNow()) {
          // Yes. Create a pipe and send a lazy event.
          writingDataStream = new PipedOutputStream();
          InputStream pipedInputStream = new PipedInputStream(writingDataStream);
          MessageEvent event = new MessageEvent(
              eventName,
              new InputStreamReader(pipedInputStream),
              lastEventId,
              origin
              );
          dispatchMessage(event);
          try {
            writingDataStream.write(chunkData, valueOffset, valueLength);
          } catch (IOException e) {} // ignore error if the pipe has been closed by the reader
        } else {
          // No, piping the data is not enabled so we'll just accumulate it in another buffer.
          if (dataLineEnded) {
            dataBuffer.write('\n');
          }
          if (valueLength != 0) {
            dataBuffer.write(chunkData, valueOffset, valueLength);
          }
        }
      }
      dataLineEnded = lineEnded;
      haveData = true;
      if (lineEnded) {
        fieldName = null;
      }
      return true;
    }
    
    // For any field other than "data:", we don't do any kind of streaming shenanigans -
    // we just get the whole value as a string. If the whole line fits into the buffer
    // then we can do this in one step; otherwise we'll accumulate chunks in another
    // buffer until the line is done.
    if (!lineEnded) {
      if (valueBuffer == null) {
        valueBuffer = new ByteArrayOutputStream(VALUE_BUFFER_INITIAL_CAPACITY);
      }
      valueBuffer.write(chunkData, valueOffset, valueLength);
      return true;
    }
    String fieldValue;
    if (valueBuffer == null || valueBuffer.size() == 0) {
      fieldValue = chunkSize == 0 ? "" : new String(chunkData, valueOffset, valueLength, UTF8);
    } else {
      // we had accumulated a partial value in a previous read
      valueBuffer.write(chunkData, valueOffset, valueLength);
      fieldValue = valueBuffer.toString(UTF8.name());
      resetValueBuffer();
    }
    
    switch (fieldName) {
    case COMMENT:
      processComment(fieldValue);
      break;
    case EVENT:
      eventName = fieldValue;
      break;
    case ID:
      if (!fieldValue.contains("\u0000")) { // per specification, id field cannot contain a null character
        lastEventId = fieldValue;
        if (lastEventId != null) {
          connectionHandler.setLastEventId(lastEventId);
        }
      }
      break;
    case RETRY:
      if (DIGITS_ONLY.matcher(fieldValue).matches()) {
        connectionHandler.setReconnectionTime(Duration.ofMillis(Long.parseLong(fieldValue)));
      }
      break;
    default:
      // For an unrecognized field name, we do nothing.  
    }
    fieldName = null;
    return true;
  }

  private boolean canStreamEventDataNow() {
    if (!streamEventData) {
      return false;
    }
    if (expectFields != null) {
      if (expectFields.contains(EVENT) && eventName == null) {
        return false;
      }
      if (expectFields.contains(ID) && lastEventId == null) {
        return false;
      }
    }
    return true;
  }
  
  private void processComment(String comment) {
    try {
      handler.onComment(comment);
    } catch (Exception e) {
      logger.warn("Message handler threw an exception: " + e.toString());
      logger.debug("Stack trace: {}", new LazyStackTrace(e));
      handler.onError(e);
    }
  }

  private void eventTerminated() throws UnsupportedEncodingException {
    if (writingDataStream != null) {
      // We've already handed off a lazily-computed event to the handler, and we've already
      // processed all the fields that we encountered. So, just close out the data stream.
      try {
        writingDataStream.close();
      } catch (IOException e) {}
      writingDataStream = null;
      resetState();
      return;
    }
    
    if (!haveData) {
      resetState();
      return;
    }
    
    String dataString = dataBuffer.toString(UTF8.name()); // unfortunately, toString(Charset) isn't available in Java 8
    MessageEvent message = new MessageEvent(eventName, dataString, lastEventId, origin);
    if (lastEventId != null) {
      connectionHandler.setLastEventId(lastEventId);
    }
    dispatchMessage(message);
    
    resetState();
  }
  
  private void dispatchMessage(MessageEvent message) {
    try {
      logger.debug("Dispatching message: {}", message);
      handler.onMessage(message.getEventName(), message);
    } catch (Exception e) {
      logger.warn("Message handler threw an exception: " + e.toString());
      logger.debug("Stack trace: {}", new LazyStackTrace(e));
      handler.onError(e);
    }
  }
  
  private void resetState() {
    haveData = false;
    dataLineEnded = false;
    eventName = null;
    resetValueBuffer();
    if (dataBuffer.size() != 0) {
      if (dataBuffer.size() > VALUE_BUFFER_INITIAL_CAPACITY) {
        dataBuffer = new ByteArrayOutputStream(VALUE_BUFFER_INITIAL_CAPACITY); // don't want it to grow indefinitely
      } else {
        dataBuffer.reset();
      }
    }
  }
  
  private void resetValueBuffer( ) {
    if (valueBuffer != null) {
      if (valueBuffer.size() > VALUE_BUFFER_INITIAL_CAPACITY) {
        valueBuffer = null; // don't want it to grow indefinitely, and might not ever need it again
      } else {
        valueBuffer.reset();
      }
    }
  }
}
