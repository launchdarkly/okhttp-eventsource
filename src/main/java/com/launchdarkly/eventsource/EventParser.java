package com.launchdarkly.eventsource;

import com.launchdarkly.logging.LDLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static com.launchdarkly.eventsource.Helpers.UTF8;
import static com.launchdarkly.eventsource.Helpers.utf8ByteArrayOutputStreamToString;

/**
 * All of the SSE parsing logic is implemented in this class (except for the detection of line
 * endings, which is in BufferedLineParser).
 * <p>
 * The basic usage pattern is that EventSource creates an EventParser as soon as it has made a
 * stream connection, passing in an InputStream. EventParser interacts only with this InputStream
 * and doesn't know anything about HTTP. Then, each time EventSource wants to get an event, it
 * calls EventParser.nextEvent(), which blocks until either an event is available or the stream
 * has failed.
 * <p>
 * EventParser should never be accessed by any thread other than the one that started the
 * stream.
 * <p>
 * The logic is mostly straightforward except for the special "stream event data" mode. In this
 * mode, rather than buffering all the data for an event and returning it once, we return an
 * incomplete event that contains a special input stream. That stream (implemented in
 * EventParser.IncrementalMessageDataInputStream) is a decorator that calls back to the parent
 * EventParser to continue reading and parsing data for the event. Again, the thread that started
 * the stream is the only one that should be doing these reads.
 */
final class EventParser {
  static final int VALUE_BUFFER_INITIAL_CAPACITY = 1000;
  static final int MIN_READ_BUFFER_SIZE = 200;
  
  private static final String DATA = "data";
  private static final String EVENT = "event";
  private static final String ID = "id";
  private static final String RETRY = "retry";
  private static final String COMMENT = "";

  private static final Pattern DIGITS_ONLY = Pattern.compile("^[\\d]+$");

  private final boolean streamEventData;
  private Set<String> expectFields;
  private final LDLogger logger;
  private final URI origin;

  private BufferedLineParser lineParser;
  private byte[] chunkData;
  private int chunkOffset;
  private int chunkSize;
  private boolean lineEnded;
  private int currentLineLength;
  
  private ByteArrayOutputStream dataBuffer;  // accumulates "data" lines if we are not using streaming mode
  private ByteArrayOutputStream valueBuffer; // used whenever a field other than "data" has a value longer than one chunk

  private boolean haveData;         // true if we have seen at least one "data" line so far in this event
  private boolean dataLineEnded;    // true if the previous chunk of "data" ended in a line terminator

  private String fieldName;       // name of the field we are currently parsing (might be spread across multiple chunks)
  private String lastEventId;     // value of "id:" field in this event, if any
  private String eventName;       // value of "event:" field in this event, if any
  private boolean skipRestOfMessage; // true if we started reading a message but want to ignore the rest of it
  private boolean skipRestOfLine; // true if we are skipping over an invalid line
  private IncrementalMessageDataInputStream currentMessageDataStream; // see comments on this inner class
  
  EventParser(
      InputStream inputStream,
      URI origin,
      int readBufferSize,
      boolean streamEventData,
      Set<String> expectFields,
      LDLogger logger
      ) {
    this.lineParser = new BufferedLineParser(inputStream, readBufferSize);
    this.origin = origin;
    this.streamEventData = streamEventData;
    this.expectFields = expectFields;
    this.logger = logger;
    
    dataBuffer = new ByteArrayOutputStream(VALUE_BUFFER_INITIAL_CAPACITY);
  }

  /**
   * Synchronously obtains the next event from the stream-- either parsing
   * already-read data from the read buffer, or reading more data if necessary, until
   * an event is available.
   * <p>
   * This method always either returns an event or throws a StreamException. If it
   * throws an exception, the stream should be considered invalid/closed. 
   * <p>
   * The return value is always a MessageEvent, a CommentEvent, or a
   * SetRetryDelayEvent. StartedEvent and FaultEvent are not returned by this method
   * because they are by-products of state changes in EventSource.
   * 
   * @return the next event (never null)
   * @throws StreamException if the stream throws an I/O error or simply ends; do
   *   not try to use this EventParser instance again after this happens
   */
  public StreamEvent nextEvent() throws StreamException {
    while (true) {
      StreamEvent event = tryNextEvent();
      if (event != null) {
        return event;
      }
    }
  }

  // This inner method exists just to simplify control flow: whenever we need to
  // obtain some more data before continuing, we can just return null so nextEvent
  // will call us again.
  private StreamEvent tryNextEvent() throws StreamException {
    if (currentMessageDataStream != null) {
      // We dispatched an incremental message that has not yet been fully read, so we need
      // to skip the rest of that message before we can proceed.
      IncrementalMessageDataInputStream obsoleteStream = currentMessageDataStream;
      skipRestOfMessage = true;
      currentMessageDataStream = null;
      obsoleteStream.close();
    }
    
    try {
      getNextChunk();
    } catch (IOException e) {
      throw new StreamIOException(e);
    }
    
    if (skipRestOfMessage) {
      // If we're in this state, it means we want to ignore everything we see until the
      // next blank line.
      if (lineEnded && currentLineLength == 0) {
        skipRestOfMessage = false;
        resetState();
      }
      return null; // no event available yet, loop for more data
    }
    
    if (skipRestOfLine) {
      // If we're in this state, it means we already know we want to ignore this line and
      // not bother buffering or parsing the rest of it - just keep reading till we see a
      // line terminator. We do this if we couldn't find a colon in the first chunk we read,
      // meaning that the field name can't possibly be valid even if there is a colon later.
      skipRestOfLine = !lineEnded;
      return null;
    }
    
    if (lineEnded && currentLineLength == 0) {
      // Blank line means end of message-- if we're currently reading a message.
      if (!haveData) {
        resetState();
        return null; // no event available yet, loop for more data
      }
      
      String dataString = utf8ByteArrayOutputStreamToString(dataBuffer);
      MessageEvent message = new MessageEvent(eventName, dataString, lastEventId, origin);
      resetState();
      logger.debug("Received message: {}", message);
      return message;
    }
    
    if (fieldName == null) { // we haven't yet parsed the field name
      fieldName = parseFieldName();
      if (fieldName == null) {
        // We didn't find a colon. Since the capacity of our line buffer is always greater
        // than the length of the longest valid SSE field name plus a colon, the chunk that
        // we have now is either a field name with no value... or, if we haven't yet hit a
        // line terminator, it could be an extremely long field name that didn't fit in the
        // buffer, but in that case it is definitely not a real SSE field since those all
        // have short names, so then we know we can skip the rest of this line.
        skipRestOfLine = !lineEnded;
        return null; // no event available yet, loop for more data
      }
    }
    
    if (fieldName.equals(DATA)) {
      // We have not already started streaming data for this event. Should we?
      if (canStreamEventDataNow()) {
        // We are in streaming data mode, so as soon as we see the start of "data:" we
        // should create a decorator stream and return a message that will read from it.
        // We won't come back to nextEvent() until the caller is finished with that message
        // (or, if they try to read another message before this one has been fully read,
        // the logic at the top of nextEvent() will cause this message to be skipped).
        IncrementalMessageDataInputStream messageDataStream = new IncrementalMessageDataInputStream();
        currentMessageDataStream = messageDataStream;
        MessageEvent message = new MessageEvent(
            eventName,
            new InputStreamReader(messageDataStream),
            lastEventId,
            origin
            );
        logger.debug("Received message: {}", message);
        return message;
      }
      // Streaming data is not enabled, so we'll accumulate this data in a buffer until
      // we've seen the end of the event.
      if (dataLineEnded) {
        dataBuffer.write('\n');
      }
      if (chunkSize != 0) {
        dataBuffer.write(chunkData, chunkOffset, chunkSize);
      }
      dataLineEnded = lineEnded;
      haveData = true;
      if (lineEnded) {
        fieldName = null;
      }
      return null; // no event available yet, loop for more data
    }
    
    // For any field other than "data:", we don't do any kind of streaming shenanigans -
    // we just get the whole value as a string. If the whole line fits into the buffer
    // then we can do this in one step; otherwise we'll accumulate chunks in another
    // buffer until the line is done.
    if (!lineEnded) {
      if (valueBuffer == null) {
        valueBuffer = new ByteArrayOutputStream(VALUE_BUFFER_INITIAL_CAPACITY);
      }
      valueBuffer.write(chunkData, chunkOffset, chunkSize);
      return null; // Don't have a full event yet
    }
    
    String completedFieldName = fieldName;
    fieldName = null; // next line will need a field name
    String fieldValue;
    if (valueBuffer == null || valueBuffer.size() == 0) {
      fieldValue = chunkSize == 0 ? "" : new String(chunkData, chunkOffset, chunkSize, UTF8);
    } else {
      // we had accumulated a partial value in a previous read
      valueBuffer.write(chunkData, chunkOffset, chunkSize);
      fieldValue = utf8ByteArrayOutputStreamToString(valueBuffer);
      resetValueBuffer();
    }
    
    switch (completedFieldName) {
    case COMMENT:
      return new CommentEvent(fieldValue);
    case EVENT:
      eventName = fieldValue;
      break;
    case ID:
      if (!fieldValue.contains("\u0000")) { // per specification, id field cannot contain a null character
        lastEventId = fieldValue;
      }
      break;
    case RETRY:
      if (DIGITS_ONLY.matcher(fieldValue).matches()) {
        return new SetRetryDelayEvent(Long.parseLong(fieldValue));
      }
      break;
    default:
      // For an unrecognized field name, we do nothing.  
    }
    return null;
  }

  private void getNextChunk() throws IOException, StreamClosedByServerException {
    // Calling lineParser.read() gives us either a chunk of data that was terminated by a
    // line ending, or a chunk of data that was as much as the input buffer would hold.
    boolean previousLineEnded = lineEnded;
    lineEnded = lineParser.read();
    chunkData = lineParser.getBuffer();
    chunkOffset = lineParser.getChunkOffset();
    chunkSize = lineParser.getChunkSize();
    if (chunkSize == 0 && lineParser.isEof()) {
      throw new StreamClosedByServerException();
    }
    if (previousLineEnded) {
      currentLineLength = chunkSize;
    } else {
      currentLineLength += chunkSize;
    }
  }
  
  private String parseFieldName() {
    int nameLength = 0;
    for (; nameLength < chunkSize && chunkData[chunkOffset + nameLength] != ':'; nameLength++) {}
    resetValueBuffer();
    if (nameLength == chunkSize && !lineEnded) {
      // The line was longer than the buffer, and we did not find a colon. Since no valid
      // SSE field name would be longer than our buffer, we can consider this line invalid.
      // (But if lineEnded is true, that's OK-- a line consisting of nothing but a field
      // name is OK in SSE-- so we'll fall through below in that case.)
      return null;
    }
    String name = nameLength == 0 ? "" : new String(chunkData, chunkOffset, nameLength, UTF8);
    if (nameLength < chunkSize) {
      nameLength++;
      if (nameLength < chunkSize && chunkData[chunkOffset + nameLength] == ' ') {
        // Skip exactly one leading space at the start of the value, if any
        nameLength++;
      }
    }
    chunkOffset += nameLength;
    chunkSize -= nameLength;
    return name;
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
  
  private void resetState() {
    haveData = false;
    dataLineEnded = false;
    eventName = null;
    fieldName = null;
    resetValueBuffer();
    if (dataBuffer.size() != 0) {
      if (dataBuffer.size() > VALUE_BUFFER_INITIAL_CAPACITY) {
        dataBuffer = new ByteArrayOutputStream(VALUE_BUFFER_INITIAL_CAPACITY); // don't want it to grow indefinitely
      } else {
        dataBuffer.reset();
      }
    }
    currentMessageDataStream = null;
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

  // This class implements the special stream underlying MessageEvent.getDataReader() when we
  // are in "stream event data" mode. In that mode, as soon as EventParser sees the beginning
  // of some event data, it immediately creates an event and embeds this stream decorator
  // inside of it. Reading from the decorator causes EventParser to continue parsing the data,
  // transforming it as per the SSE spec (that is, removing the "data:" field name from each
  // line, and changing all line terminators to a single '\n'). Once we have reached the end
  // of the event, the decorator returns EOF.
  //
  // This inner class has access to all of EventParser's internal state.
  private class IncrementalMessageDataInputStream extends InputStream {
    private boolean haveChunk = true;
    private int readOffset = 0;
    private final AtomicBoolean closed = new AtomicBoolean();
    
    @Override
    public void close() {
      if (closed.getAndSet(true)) {
        return; // already closed
      }
      if (currentMessageDataStream == this) {
        currentMessageDataStream = null;
        skipRestOfMessage = true;
      }
    }
    
    @Override
    public int read() throws IOException {
      // COVERAGE: this method is never actually called because we only read this stream
      // through a Reader, which always calls read(byte[], int, int). However, for
      // correctness we should implement the method.
      byte[] b = new byte[0];
      while (true) {
        int n = read(b, 0, 1);
        if (n < 0) {
          return n;
        }
        if (n == 1) {
          return b[0];
        }
      }
    }
    
    @Override
    public int read(byte[] b) throws IOException {
      // COVERAGE: this method is never actually called because we only read this stream
      // through a Reader, which always calls read(byte[], int, int). However, for
      // correctness we should implement the method.
      return read(b, 0, b.length);
    }
    
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      while (true) { // we will loop until we have either some data or EOF
        if (len <= 0 || closed.get()) {
          return 0; // COVERAGE: no good way to get here in unit tests due to Reader buffering
        }
        
        // Possible states:
        // (A) We are consuming (or skipping) a chunk that was already loaded by lineParser.
        if (haveChunk) {
          if (skipRestOfLine) {
            skipRestOfLine = !lineEnded;
            haveChunk = false;
            continue; // We'll go to (B) in the next loop
          }

          int availableSize = chunkSize - readOffset;
          if (availableSize > len) {
            System.arraycopy(chunkData, chunkOffset + readOffset, b, off, len);
            readOffset += len;
            return len;
          }
          System.arraycopy(chunkData, chunkOffset + readOffset, b, off, availableSize);
          haveChunk = false; // We'll go to (B) on the next call
          readOffset = 0;
          return availableSize;
        }
        
        // (B) We must ask lineParser to give us another chunk of a not-yet-finished line.
        if (!lineEnded) {
          if (!canGetNextChunk()) {
            return -1; // EOF
          }
          haveChunk = true;
          continue; // We'll go to (A) in the next loop
        }
        
        // (C) The previous line was done; ask lineParser to give us the next line (or at
        // least the first chunk of it).
        if (!canGetNextChunk()) {
          return -1; // EOF
        }
        if (lineEnded && chunkSize == 0) {
          // Blank line means end of message - close this stream and return EOF.
          closed.set(true);
          resetState();
          return -1;
        }
        // If it's not a blank line then it should have a field name.
        String fieldName = parseFieldName();
        if (!DATA.equals(fieldName)) {
          // If it's any field other than "data:", there's no way for us to do anything
          // with it at this point-- that's an inherent limitation of streaming data mode.
          // So we'll just skip the line.
          skipRestOfLine = !lineEnded;
          continue; // we'll go to (A) in the next loop
        }
        // We are starting another "data:" line. Since we have already read at least one
        // data line before we get to this point, we should return a linefeed at this point.
        b[0] = '\n';
        haveChunk = true; // We'll go to (A) on the next call
        return 1;
      }
    }
    
    private boolean canGetNextChunk() throws IOException {
      try {
        getNextChunk();
      } catch (StreamClosedByServerException e) {
        close();
        return false;
      }
      return true;
    }
  }
}
