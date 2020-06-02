package com.launchdarkly.eventsource;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import okhttp3.Headers;

/**
 * A simple Jetty-based test framework for verifying end-to-end HTTP behavior.
 * <p>
 * Previous versions of the library used okhttp's MockWebServer for end-to-end tests, but MockWebServer
 * does not support actual streaming responses so the tests could not control when the stream got
 * disconnected.
 */
@SuppressWarnings("javadoc")
public final class StubServer implements Closeable {  
  private final Server server;
  private final BlockingQueue<RequestInfo> requests = new LinkedBlockingQueue<>();
  
  /**
   * Starts an HTTP server that uses the specified handler.
   * 
   * @param handler a {@link Handler} implementation
   * @return a started server
   */
  public static StubServer start(Handler handler) {
    return new StubServer(handler);
  }
  
  private StubServer(final Handler handler) {
    server = new Server(0);
    
    server.setHandler(new AbstractHandler() {
      @Override
      public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
          throws IOException, ServletException {
        RequestInfo requestInfo = new RequestInfo(request);
        requests.add(requestInfo);
        handler.handle(request, response);
        baseRequest.setHandled(true);
      }
    });
    server.setStopTimeout(100); // without this, Jetty does not interrupt worker threads on shutdown
    
    try {
      server.start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  
  /**
   * Shuts down the server.
   * <p>
   * All active request handler threads will be interrupted.
   */
  @Override
  public void close() {
    try {
      server.stop();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  
  /**
   * Returns the server's base URI.
   * 
   * @return the base URI
   */
  public URI getUri() {
    return server.getURI();
  }
  
  /**
   * Returns the next queued request, blocking until one is available.
   * 
   * @return the request information
   */
  public RequestInfo awaitRequest() {
    try {
      return requests.take();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
  
  /**
   * Returns the next queued request, or null if none is available within the specified timeout.
   * 
   * @param timeout the maximum time to wait
   * @return the request information or null
   */
  public RequestInfo awaitRequest(Duration timeout) {
    try {
      return requests.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      return null;
    }
  }
  
  /**
   * Interface for StubServer's simplified request handler mechanism.
   */
  public static interface Handler {
    /**
     * Handle a request.
     * 
     * @param request the request
     * @param response the response
     */
    public void handle(HttpServletRequest request, HttpServletResponse response);
  }
  
  /**
   * The properties of a received request.
   * <p>
   * Note that this fully reads the request body, so request handlers cannot make use of the body.
   */
  public static final class RequestInfo {
    private final String method;
    private final String path;
    private final Headers headers;    
    private final String body;
    
    public RequestInfo(HttpServletRequest request) {
      this.method = request.getMethod();
      this.path = request.getRequestURI();
      
      Headers.Builder buildHeaders = new Headers.Builder();
      Enumeration<String> headerNames = request.getHeaderNames();
      while (headerNames.hasMoreElements()) {
        String name = headerNames.nextElement();
        Enumeration<String> values = request.getHeaders(name);
        while (values.hasMoreElements()) {
          buildHeaders.add(name, values.nextElement());
        }
      }
      headers = buildHeaders.build();
      
      StringBuilder s = new StringBuilder();
      try {
        try (BufferedReader reader = request.getReader()) {
          char[] buf = new char[1000];
          int count = -1;
          while ((count = reader.read(buf)) > 0) {
            s.append(buf, 0, count);
          }
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      body = s.toString();
    }
    
    public String getMethod() {
      return method;
    }
    
    public String getPath() {
      return path;
    }
    
    public String getHeader(String name) {
      return headers.get(name);
    }
    
    public List<String> getHeaders(String name) {
      return headers.values(name); 
    }
        
    public String getBody() {
      return body;
    }
  }

  /**
   * Interface for use with {@link Handlers#interruptible}.
   */
  public static interface InterruptibleHandler extends Handler {
    /**
     * Causes the handler to stop what it is doing and terminate the response as soon as possible.
     */
    void interrupt();
  }
  
  /**
   * Factory methods for StubServer handlers.
   */
  public static abstract class Handlers {
    /**
     * Special value to use with {@link #stream(String, BlockingQueue)} to signal that the stream should be
     * closed at this point, rather than waiting for more chunks.
     * <p>
     * The value of this constant is unimportant, we check for reference equality so what matters is that
     * you use this specific String.
     */
    public static final String CLOSE_STREAM = new String("**EOF**");

    /**
     * Provides a handler that returns the specified HTTP status, with no content.
     * 
     * @param status the status code
     * @return the handler
     */
    public static Handler returnStatus(final int status) {
      return new Handler() {
        public void handle(HttpServletRequest req, HttpServletResponse resp) {
          resp.setStatus(status);
        }
      };
    }
    
    /**
     * Provides a handler that delegates to a series of handlers for each request, in the order given.
     * If there are more requests than the number of handlers, the last handler is used for the rest.
     * 
     * @param firstHandler the first handler
     * @param moreHandlers additional handlers
     * @return the delegating handler
     */
    public static Handler forRequestsInSequence(final Handler firstHandler, final Handler... moreHandlers) {
      final AtomicInteger counter = new AtomicInteger(0);
      return new Handler() {
        public void handle(HttpServletRequest req, HttpServletResponse resp) {
          int i = counter.getAndIncrement();
          Handler h = i == 0 ? firstHandler :
            (i >= moreHandlers.length ? moreHandlers[moreHandlers.length - 1] : moreHandlers[i - 1]);
          h.handle(req, resp);
        }
      };
    }
    
    /**
     * Provides a handler that does not send a response, but does not close the socket.
     * 
     * @return the handler
     */
    public static Handler hang() {
      return new Handler() {
        public void handle(HttpServletRequest request, HttpServletResponse response) {
          while (true) {
            try {
              Thread.sleep(1000);
            } catch (InterruptedException e) {
              break;
            }
          }
        }
      };
    }
    
    /**
     * Provides the ability to interrupt the worker thread for a handler at any time. This can be used
     * to terminate a stream. If multiple requests are in progress for the same handler, interrupting it
     * causes all of them to terminate.
     * 
     * @param realHandler the handler to delegate to
     * @return a wrapper handler that provides an {@link InterruptibleHandler#interrupt()} method
     */
    public static InterruptibleHandler interruptible(final Handler realHandler) {
      final AtomicBoolean interrupted = new AtomicBoolean(false);
      final Object interruptLock = new Object();

      return new InterruptibleHandler() {
        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response) {
          final Thread writerThread = Thread.currentThread();
          Thread interrupterThread = new Thread(new Runnable() {
            public void run() {
              while (true) {
                synchronized (interruptLock) {
                  if (interrupted.get()) {
                    break;
                  }
                  try {
                    interruptLock.wait();
                  }
                  catch (InterruptedException e) {
                    return;
                  }
                }
              }
              writerThread.interrupt();
            }
          });
          interrupterThread.start();
          realHandler.handle(request, response);
        }
        
        @Override
        public void interrupt() {
          synchronized (interruptLock) {
            interrupted.set(true);
            interruptLock.notifyAll();
          }
        }
      };
    }
    
    /**
     * Provides a handler that streams a chunked response. It will continue waiting for more chunks
     * to be pushed onto the queue unless you push the special constant {@link #CLOSE_STREAM}, which
     * signals the end of the response.
     * 
     * @param contentType value for the Content-Type header
     * @param streamProducer a {@link StreamProducer} that will provide the response
     * @return the handler
     */
    public static Handler stream(final String contentType, final BlockingQueue<String> chunks) {
      return new Handler() {
        public void handle(HttpServletRequest req, HttpServletResponse resp) {
          resp.setStatus(200);
          resp.setHeader("Content-Type", contentType);
          resp.setHeader("Transfer-Encoding", "chunked");
          try {
            resp.flushBuffer();
            PrintWriter w = resp.getWriter();
            while (true) {
              try {
                String chunk = chunks.take();
                if (chunk == CLOSE_STREAM) {
                  break;
                }
                w.write(chunk);
                w.flush();
              } catch (InterruptedException e) {
                break;
              }
            }
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        };
      };
    }

    /**
     * Provides content for {@link #stream(String, BlockingQueue)} that is a single chunk of data.
     * 
     * @param body the response body
     * @param leaveOpen true to leave the stream open after sending this data, false to close it
     * @return a stream chunk queue
     */
    public static BlockingQueue<String> chunkFromString(final String body, final boolean leaveOpen) {
      BlockingQueue<String> ret = new LinkedBlockingQueue<>();
      ret.add(body);
      if (!leaveOpen) {
        ret.add(CLOSE_STREAM);
      }
      return ret;
    }
    
    /**
     * Provides content for {@link #stream(String, BlockingQueue)} that is a string broken up into
     * multiple chunks of equal size.
     * 
     * @param body the response body
     * @param chunkSize the number of characters per chunk
     * @param chunkDelay optional delay between chunks
     * @param leaveOpen true to leave the stream open after sending this data, false to close it
     * @return a stream chunk queue
     */
    public static BlockingQueue<String> chunksFromString(final String body, final int chunkSize,
        final Duration chunkDelay, final boolean leaveOpen) {
      final BlockingQueue<String> ret = new LinkedBlockingQueue<>();
      final String[] chunks = new String[(body.length() + chunkSize - 1) / chunkSize];
      for (int i = 0; i < chunks.length; i++) {
        int p = i * chunkSize;
        chunks[i] = body.substring(p, Math.min(body.length(), p + chunkSize));
      }
      if (chunkDelay.isZero() || chunkDelay.isNegative()) {
        for (int i = 0; i < chunks.length; i++) {
          ret.add(chunks[i]);
        }
        if (!leaveOpen) {
          ret.add(CLOSE_STREAM);
        }
      } else {
        new Thread(() -> {
          for (int i = 0; i < chunks.length; i++) {
            ret.add(chunks[i]);
            try {
              Thread.sleep(chunkDelay.toMillis());
            } catch (InterruptedException e) {
              break;
            }
          }
          if (!leaveOpen) {
            ret.add(CLOSE_STREAM);
          }
        }).start();
      }
      return ret;
    }
  }
}
