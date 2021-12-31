package com.launchdarkly.eventsource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;

/**
 * Buffers a UTF-8 stream and consumes it one line at a time, where a line is terminated by
 * any of CR (\r), LF (\n), or CRLF. The SSE specification allows any of these line endings
 * for any line in the stream.
 * <p>
 * This behavior is similar to okio's BufferedSource, except that BufferedSource only allows
 * LF or CRLF and not plain CR. Also, there are additional methods in BufferedSource that
 * aren't necessary for our purposes, so the logic here is specialized for scanning lines.
 * <p>
 * A fixed-size buffer is used for reads. If the lines are shorter than the buffer, there
 * are no further allocations. 
 * <p>
 * This class is not thread-safe.
 */
class BufferedUtf8LineReader {
  private static final Charset UTF8 = Charset.forName("UTF-8"); // SSE streams must be UTF-8, per the spec
  
  private final InputStream stream;
  private final byte[] readBuffer;
  private int readBufferCount = 0;
  private ByteArrayOutputStream pendingUnterminatedLine = null;
  private int scanPos = 0;
  private int lineStart = 0;
  private boolean lastCharWasCr = false;
  
  public BufferedUtf8LineReader(InputStream stream, int bufferSize) {
    this.stream = stream;
    this.readBuffer = new byte[bufferSize];
  }
  
  /**
   * Attempts to read the next line. If there is not already a full line in the buffer, it
   * will read from the stream as many times as necessary till a line terminator is seen.
   * 
   * @return the line, not including the terminator; null if the stream has ended
   * @throws IOException if the stream threw an exception
   */
  public String readLine() throws IOException {
    while (true) {
      String line = getLineFromBuffer();
      if (line != null) {
        return line;
      }
      if (!readMoreIntoBuffer()) {
        return null;
      }
    }
  }
  
  private String getLineFromBuffer() {
    if (lastCharWasCr && scanPos < readBufferCount) {
      if (scanPos == 0 && readBuffer[0] == '\n') {
        // This handles the case where the previous reads ended in CR, so we couldn't tell
        // at that time whether it was just a plain CR or part of a CRLF.
        scanPos++;
        lineStart++; 
      }
      lastCharWasCr = false;
    }

    while (scanPos < readBufferCount) {
      byte b = readBuffer[scanPos];
      if (b == '\n' || b == '\r') {
        break;
      }
      scanPos++;
    }
    if (scanPos == readBufferCount) {
      // We haven't found a terminator yet; we'll need to read more from the stream. In
      // the meantime, if there's any already-parsed content in the buffer prior to this
      // line, we can remove it to free up space in the buffer.
      if (scanPos > lineStart && lineStart > 0) {
        System.arraycopy(readBuffer, lineStart, readBuffer, 0, readBufferCount - lineStart);
        readBufferCount -= lineStart;
        scanPos = readBufferCount;
        lineStart = 0;
      }
      return null;
    }
    
    int lineEnd = scanPos;
    scanPos++;
    if (readBuffer[lineEnd] == '\r') {
      if (scanPos == readBufferCount) {
        lastCharWasCr = true;
      } else if (readBuffer[scanPos] == '\n') {
        scanPos++;
      }
    }
    
    String line;
    if (pendingUnterminatedLine != null) {
      pendingUnterminatedLine.write(readBuffer, lineStart, lineEnd - lineStart);
      try {
        line = pendingUnterminatedLine.toString("UTF-8");
      } catch (UnsupportedEncodingException e) {
        // COVERAGE: This should be impossible since UTF-8 should always be supported, but the
        // semantics of toString(String) require it. Once we no longer need to support Java 8, we
        // can replace the string "UTF-8" with the Charset object UTF8.
        line = null;
      }
      pendingUnterminatedLine = null;
    } else {
      line = lineEnd == lineStart ? "" : new String(readBuffer, lineStart, lineEnd - lineStart, UTF8);
    }
    if (scanPos == readBufferCount) {
      scanPos = lineStart = 0;
      readBufferCount = 0;
    } else {
      lineStart = scanPos;
    }
    
    return line;
  }
  
  private boolean readMoreIntoBuffer() throws IOException {
    if (readBufferCount == readBuffer.length) {
      // We don't want readBuffer to grow permanently, because maybe not all lines are long.
      // So, move the current content into pendingUnterminatedLine.
      if (pendingUnterminatedLine == null) {
        pendingUnterminatedLine = new ByteArrayOutputStream(readBufferCount * 2);
      }
      pendingUnterminatedLine.write(readBuffer, lineStart, scanPos - lineStart);
      lineStart = scanPos = readBufferCount = 0;
    }
    int readCount = stream.read(readBuffer, readBufferCount, readBuffer.length - readBufferCount);
    if (readCount < 0) {
      return false; // stream was closed
    }
    readBufferCount += readCount;
    return true;
  }
}
