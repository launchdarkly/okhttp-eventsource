package com.launchdarkly.eventsource;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import static com.launchdarkly.eventsource.Helpers.UTF8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

@SuppressWarnings("javadoc")
@RunWith(Parameterized.class)
public class BufferedLineParserTest {
  // This test uses many permutations of how long the input lines are, how long are
  // the chunks that are returned by each read of the (fake) stream, how big the
  // read buffer is, and what the line terminators are, with a mix of single-byte
  // and multi-byte UTF8 characters, to verify that our parsing logic doesn't have
  // edge cases that fail.
  
  private static final String[] lineEnds = new String[] { "\r", "\n", "\r\n" };
  private static final String[] lineEndNames = new String[] { "CR", "LF", "CRLF" };
  
  private final int chunkSize;
  private final String lineEnd;
  
  @Parameterized.Parameters(name="terminator={2}, chunk size={0}")
  public static Iterable<Object[]> parameters() {
    ImmutableList.Builder<Object[]> ret = ImmutableList.builder();
    for (int chunkSize: new int[] { 1, 2, 3, 200 }) {
      for (int i = 0; i < lineEnds.length; i++) {
        ret.add(new Object[] { chunkSize, lineEnds[i], lineEndNames[i]});
      }
    }
    return ret.build();
  }
  
  public BufferedLineParserTest(int chunkSize, String lineEnd, String description) {
    this.chunkSize = chunkSize;
    this.lineEnd = lineEnd;
  }
  
  @Test
  public void parseLinesShorterThanBuffer() throws Exception {
    parseLinesWithLengthsAndBufferSize(20, 25, 100);
  }
  
  @Test
  public void parseLinesLongerThanBuffer() throws Exception {
    parseLinesWithLengthsAndBufferSize(75, 150, 50);
  }
  
  @Test
  public void parseLinesWithMixedLineEndings() throws Exception {
    ImmutableList<String> lines = makeLines(10, 10, 20);
    ImmutableList.Builder<String> linesWithEnds = ImmutableList.builder();
    int whichEnding = 0;
    for (; whichEnding < lineEnds.length; whichEnding++) {
      if (lineEnd.equals(lineEnds[whichEnding])) {
        break;
      }
    }
    for (String line: lines) {
      linesWithEnds.add(line + lineEnds[(++whichEnding) % lineEnds.length]);
    }
    parseLinesWithBufferSize(lines, linesWithEnds.build(), 100);
  }
  
  private void parseLinesWithLengthsAndBufferSize(int minLength, int maxLength, int bufferSize) throws Exception {
    ImmutableList<String> lines = makeLines(20, minLength, maxLength);
    Iterable<String> linesWithEnds = Iterables.transform(lines, line -> line + lineEnd);
    parseLinesWithBufferSize(lines, linesWithEnds, bufferSize);
  }
  
  private void parseLinesWithBufferSize(
      Iterable<String> lines,
      Iterable<String> linesWithEnds,
      int bufferSize
      ) throws Exception {
    Iterable<String> chunks =
        chunkSize == 0 ? linesWithEnds  // this special value means one chunk per line
            : Splitter.fixedLength(chunkSize).split(Joiner.on("").join(linesWithEnds));
    
    InputStream input = new FakeInputStream(chunks);
    BufferedLineParser p = new BufferedLineParser(input, bufferSize);
    
    ImmutableList.Builder<String> received = ImmutableList.builder();
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    while (true) {
      boolean haveLine = p.read();
      buf.write(p.getBuffer(), p.getChunkOffset(), p.getChunkSize());
      if (haveLine) {
        String line = buf.toString(UTF8.name());
        received.add(line);
        buf.reset();
      } else if (p.isEof()) {
        break;
      }
    }
    ImmutableList<String> actualLines = received.build();
    assertThat(actualLines, contains(Iterables.toArray(lines, String.class)));
  }
  
  private static ImmutableList<String> makeLines(int count, int minLength, int maxLength) {
    String allChars = makeUtf8CharacterSet();
    ImmutableList.Builder<String> ret = ImmutableList.builder();
    for (int i = 0; i < count; i++) {
      int length = minLength + ((maxLength - minLength) * i) / count;
      StringBuilder s = new StringBuilder();
      for (int j = 0; j < length; j++) {
        char ch = allChars.charAt((i + j) % allChars.length());
        s.append(ch);
      }
      ret.add(s.toString());
    }
    return ret.build();
  }
  
  private static String makeUtf8CharacterSet() {
    // Here we're mixing in some multi-byte characters so that we will sometimes end up
    // dividing a character across chunks.
    String singleByteCharacters = "01234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    String multiByteCharacters = "ØĤǶȵ϶ӾﺯᜅጺᏡ";
    StringBuilder s = new StringBuilder();
    int mi = 0;
    for (int si = 0; si < singleByteCharacters.length(); si++) {
      s.append(singleByteCharacters.charAt(si));
      if (si % 5 == 4) {
        s.append(multiByteCharacters.charAt((mi++) % multiByteCharacters.length()));
      }
    }
    return s.toString();
  }
  
  private static class FakeInputStream extends InputStream {
    private static byte[][] chunks;
    private int curChunk = 0;
    private int posInChunk = 0;
    
    public FakeInputStream(Iterable<String> stringChunks) {
      chunks = new byte[Iterables.size(stringChunks)][];
      int i = 0;
      for (String s: stringChunks) {
        chunks[i++] = s.getBytes(Charset.forName("UTF-8")); 
      }
    }
    
    @Override
    public int read() throws IOException {
      // TODO Auto-generated method stub
      return 0;
    }
    
    @Override
    public int read(byte[] b) throws IOException {
      return read(b, 0, b.length);
    }
    
    @Override
    public int read(byte[] b, int offset, int length) throws IOException {
      if (curChunk >= chunks.length) {
        return -1;
      }
      int remaining = chunks[curChunk].length - posInChunk;
      if (remaining <= length) {
        System.arraycopy(chunks[curChunk], posInChunk, b, offset, remaining);
        curChunk++;
        posInChunk = 0;
        return remaining;
      }
      System.arraycopy(chunks[curChunk], posInChunk, b, offset, length);
      posInChunk += length;
      return length;
    }
  }
}
