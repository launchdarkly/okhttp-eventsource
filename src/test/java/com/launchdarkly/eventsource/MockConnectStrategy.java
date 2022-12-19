package com.launchdarkly.eventsource;

import com.launchdarkly.logging.LDLogger;

import org.junit.Assert;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A test implementation of {@link ConnectStrategy} that provides input
 * streams to simulate server responses without running an HTTP server.
 */
@SuppressWarnings("javadoc")
public class MockConnectStrategy extends ConnectStrategy {
  public static final URI ORIGIN = URI.create("http://test/origin");
  
  private final List<RequestHandler> requestConfigs = new ArrayList<>();
  private int requestCount = 0;
  
  public final BlockingQueue<ConnectParams> receivedConnectParams = new LinkedBlockingQueue<>();
  public volatile boolean closed;
  
  public static class ConnectParams {
    private String lastEventId;
    
    ConnectParams(String lastEventId) {
      this.lastEventId = lastEventId;
    }
    
    public String getLastEventId() {
      return lastEventId;
    }
  }
  
  public static abstract class RequestHandler {
    public volatile boolean closed;
    
    public ConnectStrategy.Client.Result connect(String lastEventId) throws StreamIOException {
      return new ConnectStrategy.Client.Result(
          getStream(),
          ORIGIN,
          makeCloser());
    }

    protected abstract InputStream getStream() throws StreamIOException;
    
    protected Closeable makeCloser() {
      return new CloserImpl();
    }
    
    class CloserImpl implements Closeable {
      @Override
      public void close() throws IOException {
        closed = true;
      }
    }
  }
  
  public MockConnectStrategy configureRequests(RequestHandler... handlers) {
    for (RequestHandler handler: handlers) {
      requestConfigs.add(handler);
    }
    return this;
  }

  public static RequestHandler rejectConnection() {
    return new ConnectionFailureHandler(new IOException("deliberate error"));
  }
  
  public static RequestHandler rejectConnection(IOException exception) {
    return new ConnectionFailureHandler(exception);
  }
  
  public static RequestHandler respondWithStream(InputStream inputStream) {
    return new StreamRequestHandler(inputStream);
  }

  public static RequestHandler respondWithDataAndThenEnd(String data) {
    return respondWithStream(new ByteArrayInputStream(data.getBytes()));
  }

  public static PipedStreamRequestHandler respondWithDataAndThenStayOpen(String... dataChunks) {
    PipedStreamRequestHandler handler = respondWithStream();
    handler.provideData(dataChunks);
    return handler;
  }
  
  public static PipedStreamRequestHandler respondWithStream() {
    return new PipedStreamRequestHandler();
  }
  
  public ConnectParams expectConnection() {
    try {
      ConnectParams params = receivedConnectParams.poll(1, TimeUnit.SECONDS);
      if (params == null) {
        Assert.fail("timed out waiting for connection attempt");
      }
      return params;
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public void expectNoNewConnection(int timeout, TimeUnit timeoutUnit) {
    try {
      ConnectParams params = receivedConnectParams.poll(1, TimeUnit.SECONDS);
      if (params != null) {
        Assert.fail("did not expect a connection attempt, but got one");
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
  
  @Override
  public Client createClient(LDLogger logger) {
    return new DelegatingClientImpl();
  }

  private class DelegatingClientImpl extends ConnectStrategy.Client {
    @Override
    public void close() throws IOException {
      closed = true;
    }

    public Result connect(String lastEventId) throws StreamIOException {
      if (requestConfigs.size() == 0) {
        throw new IllegalStateException("MockConnectStrategy was not configured for any requests");
      }
      receivedConnectParams.add(new ConnectParams(lastEventId));
      RequestHandler handler = requestConfigs.get(requestCount);
      if (requestCount < requestConfigs.size() - 1) {
        requestCount++; // reuse the last entry for all subsequent requests
      }
      return handler.connect(lastEventId);
    }

    @Override
    public boolean awaitClosed(long timeoutMillis) throws InterruptedException {
      return false;
    }
    
    @Override
    public URI getOrigin() {
      return ORIGIN;
    }
  }
  
  private static class ConnectionFailureHandler extends RequestHandler {
    private final IOException exception;
    
    ConnectionFailureHandler(IOException exception) {
      this.exception = exception;
    }
    
    @Override
    protected InputStream getStream() throws StreamIOException {
      throw new StreamIOException(this.exception);
    }
  }
  
  private static class StreamRequestHandler extends RequestHandler {
    private final InputStream inputStream;
    
    StreamRequestHandler(InputStream inputStream) {
      this.inputStream = inputStream;
    }
    
    @Override
    protected InputStream getStream() throws StreamIOException {
      return inputStream;
    }
  }
  
  public static class PipedStreamRequestHandler extends RequestHandler {
    private final PipedInputStream readStream;
    private final PipedOutputStream writeStream;
    private final BlockingQueue<ChunkInfo> chunks = new LinkedBlockingQueue<>(); 
    private final Thread pipeThread;
    
    private final class ChunkInfo {
      final byte[] data;
      final int delay;
      
      ChunkInfo(byte[] data, int delay) {
        this.data = data;
        this.delay = delay;
      }
    }
    
    PipedStreamRequestHandler() {
      writeStream = new PipedOutputStream();
      try {
        readStream = new PipedInputStream(writeStream);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      pipeThread = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            while (true) {
              ChunkInfo chunk = chunks.take();
              try {
                writeStream.write(chunk.data);
              } catch (IOException e) {}
              if (chunk.delay != 0) {
                Thread.sleep(chunk.delay);
              }
            }
          } catch (InterruptedException e) {}
        }
      });
      pipeThread.start();
    }
    
    @Override
    protected InputStream getStream() throws StreamIOException {
      return readStream;
    }
    
    public void provideData(String... data) {
      for (String chunk: data) {
        chunks.add(new ChunkInfo(chunk.getBytes(), 0));
      }
    }
    
    public void provideDataInChunks(String body, int chunkSize, int delayMillis) {
      int numChunks = (body.length() + chunkSize - 1) / chunkSize;
      for (int i = 0; i < numChunks; i++) {
        int p = i * chunkSize;
        String chunk = body.substring(p, Math.min(body.length(), p + chunkSize));
        chunks.add(new ChunkInfo(chunk.getBytes(), delayMillis));
      }
    }
    
    public void close() throws IOException {
      writeStream.close();
    }
    
    protected Closeable makeCloser() {
      return new Closeable() {
        @Override
        public void close() throws IOException {
          writeStream.close();
        }
      };
    }
  }
}
