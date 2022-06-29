package com.launchdarkly.eventsource;

import java.io.IOException;
import java.io.InputStream;

/**
 * Buffers a byte stream and returns it in chunks while scanning for line endings, which
 * may be any of CR (\r), LF (\n), or CRLF. The SSE specification allows any of these line
 * endings for any line in the stream.
 * <p>
 * To use this class, we repeatedly call {@code read()} to obtain a piece of data. Rather
 * than copying this back into a buffer provided by the caller, BufferedLineParser exposes
 * its own fixed-size buffer directly and marks the portion being read; the caller is
 * responsible for inspecting this data before the next call to {@code read()}.
 * <p>
 * This class is not thread-safe.
 */
final class BufferedLineParser {
  private final InputStream stream;
  private final byte[] readBuffer;
  private int readBufferCount = 0;
  private int scanPos = 0;
  private int chunkStart = 0;
  private int chunkEnd = 0;
  private boolean lastCharWasCr = false;
  private boolean eof = false;
  
  public BufferedLineParser(InputStream stream, int bufferSize) {
    this.stream = stream;
    this.readBuffer = new byte[bufferSize];
  }
  
  /**
   * Returns true if we have reached the end of the stream.
   * 
   * @return true if at end of stream
   */
  public boolean isEof() {
    return eof;
  }
  
  /**
   * Attempts to read the next chunk. A chunk is terminated either by a line ending, or
   * by reaching the end of the buffer before the next read from the underlying stream.
   * After calling this method, use the getter methods to access the data in the chunk.
   * <p>
   * The method returns {@code true} if the chunk was terminated by a line ending, or
   * {@code false} otherwise. The line ending is not included in the chunk data.
   * 
   * @return true if the parser reached a line ending
   * @throws IOException if the underlying stream threw an exception
   */
  public boolean read() throws IOException {
    if (scanPos > 0 && readBufferCount > scanPos) {
      System.arraycopy(readBuffer, scanPos, readBuffer, 0, readBufferCount - scanPos);
    }
    readBufferCount -= scanPos;
    scanPos = chunkStart = chunkEnd = 0;
    while (true) {
      if (scanPos < readBufferCount && scanForTerminator()) {
        return true;
      }
      if (!readMoreIntoBuffer()) {
        return false;
      }
    }
  }
  
  /**
   * Returns the fixed-size buffer that all chunks are read into.
   * 
   * @return the backing byte array
   */
  public byte[] getBuffer() {
    return readBuffer;
  }
  
  /**
   * Returns the byte offset within the fixed-size buffer where the most recently read
   * chunk begins.
   * 
   * @return the chunk offset
   */
  public int getChunkOffset() {
    return chunkStart;
  }
  
  /**
   * Returns the number of bytes in the most recently read chunk, not including any line ending.
   * 
   * @return the chunk size
   */
  public int getChunkSize() {
    return chunkEnd - chunkStart;
  }
  
  private boolean scanForTerminator() {
    if (lastCharWasCr) {
      // This handles the case where the previous reads ended in CR, so we couldn't tell
      // at that time whether it was just a plain CR or part of a CRLF. We know that the
      // previous line has ended either way, we just need to ensure that if the next byte
      // is LF, we skip it.
      lastCharWasCr = false;
      if (readBuffer[scanPos] == '\n') {
        scanPos++;
        chunkStart++;
      }
    }

    while (scanPos < readBufferCount) {
      byte b = readBuffer[scanPos];
      if (b == '\n' || b == '\r') {
        break;
      }
      scanPos++;
    }
    chunkEnd = scanPos;
    if (scanPos == readBufferCount) {
      // We haven't found a terminator yet; we'll need to read more from the stream.
      return false;
    }
    
    scanPos++;
    if (readBuffer[chunkEnd] == '\r') {
      if (scanPos == readBufferCount) {
        lastCharWasCr = true;
      } else if (readBuffer[scanPos] == '\n') {
        scanPos++;
      }
    }
    return true;
  }
  
  private boolean readMoreIntoBuffer() throws IOException {
    if (readBufferCount == readBuffer.length) {
      return false;
    }
    int readCount = stream.read(readBuffer, readBufferCount, readBuffer.length - readBufferCount);
    if (readCount < 0) {
      eof = true;
      return false; // stream was closed
    }
    readBufferCount += readCount;
    return true;
  }
}
