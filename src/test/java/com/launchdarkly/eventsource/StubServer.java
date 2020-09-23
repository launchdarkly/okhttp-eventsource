package com.launchdarkly.eventsource;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A simple Jetty-based test framework for verifying end-to-end HTTP behavior.
 * <p>
 * Previous versions of the library used okhttp's MockWebServer for end-to-end tests, but MockWebServer
 * does not support actual streaming responses so the tests could not control when the stream got
 * disconnected.
 */
@SuppressWarnings("javadoc")
public class StubServer implements Closeable {
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
    server.setGracefulShutdown(1); // without this, Jetty does not interrupt worker threads on shutdown
    
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
    return URI.create("http://" + server.getConnectors()[0].getName());
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
   * @param timeout the maximum time to wait in milliseconds
   * @return the request information or null
   */
  public RequestInfo awaitRequest(int timeout) {
    try {
      return requests.poll(timeout, TimeUnit.MILLISECONDS);
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
  public static class RequestInfo {
    private final String path;
    private final Map<String, List<String>> headers;    
    private final String body;
    
    public RequestInfo(HttpServletRequest request) {
      this.path = request.getRequestURI();
      
      headers = new HashMap<>();
      Enumeration<String> headerNames = request.getHeaderNames();
      while (headerNames.hasMoreElements()) {
        String name = headerNames.nextElement();
        List<String> valuesOut = new ArrayList<>();
        Enumeration<String> values = request.getHeaders(name);
        while (values.hasMoreElements()) {
          valuesOut.add(values.nextElement());
        }
        headers.put(name, valuesOut);
      }
      
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
    
    public String getPath() {
      return path;
    }
    
    public String getHeader(String name) {
      List<String> headers = getHeaders(name);
      return headers == null || headers.isEmpty() ? null : headers.get(0);
    }
    
    public List<String> getHeaders(String name) {
      return headers.get(name); 
    }
    
    public Map<String, List<String>> getHeaders() {
      return headers;
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
     * Interface for use with {@link Handlers#stream(String, StreamProducer)}.
     */
    public static interface StreamProducer {
      /**
       * Pushes chunks of response data onto a queue. Another worker thread will dequeue and send the chunks.
       * 
       * @param chunks the queue for chunks of stream data
       * @return true if the stream should be left open indefinitely afterward, false to close it
       */
      boolean writeStream(BlockingQueue<String> chunks);
    }

    /**
     * Provides a handler that streams a chunked response.
     * 
     * @param contentType value for the Content-Type header
     * @param streamProducer a {@link StreamProducer} that will provide the response
     * @return the handler
     */
    public static Handler stream(final String contentType, final StreamProducer streamProducer) {
      return new Handler() {
        public void handle(HttpServletRequest req, HttpServletResponse resp) {
          resp.setStatus(200);
          resp.setHeader("Content-Type", contentType);
          resp.setHeader("Transfer-Encoding", "chunked");
          try {
            resp.flushBuffer();
            PrintWriter w = resp.getWriter();
            final BlockingQueue<String> chunks = new LinkedBlockingQueue<>();
            final String terminator = "***EOF***"; // value doesn't matter, we're checking for reference equality
            Thread producerThread = new Thread(new Runnable() {
              public void run() {
                boolean leaveOpen = streamProducer.writeStream(chunks);
                if (leaveOpen) {
                  while (true) {
                    try {
                      Thread.sleep(1000);
                    } catch (InterruptedException e) {
                      break;
                    }
                  }
                }
                chunks.add(terminator);
              }
            });
            producerThread.start();
            while (true) {
              try {
                String chunk = chunks.take();
                if (chunk == terminator) {
                  break;
                }
                w.write(chunk);
                w.flush();
              } catch (InterruptedException e) {
                break;
              }
            }
            producerThread.interrupt();
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        };
      };
    }
    
    /**
     * Provides content for {@link #stream(String, StreamProducer)} that is a single chunk of data.
     * 
     * @param body the response body
     * @param leaveOpen true to leave the stream open after sending this data, false to close it
     * @return the stream producer
     */
    public static StreamProducer streamProducerFromString(final String body, final boolean leaveOpen) {
      return streamProducerFromChunkedString(body, body.length(), 0, leaveOpen);
    }

    /**
     * Provides content for {@link #stream(String, StreamProducer)} that is a string broken up into
     * multiple chunks of equal size.
     * 
     * @param body the response body
     * @param chunkSize the number of characters per chunk
     * @param chunkDelay how long to wait between chunks in milliseconds
     * @param leaveOpen true to leave the stream open after sending this data, false to close it
     * @return the stream producer
     */
    public static StreamProducer streamProducerFromChunkedString(final String body, final int chunkSize,
        final int chunkDelay, final boolean leaveOpen) {
      return new StreamProducer() {
        public boolean writeStream(BlockingQueue<String> chunks) {
          for (int p = 0; p < body.length(); p += chunkSize) {
            String chunk = body.substring(p, Math.min(p + chunkSize, body.length()));
            chunks.add(chunk);
            try {
              Thread.sleep(chunkDelay);
            } catch (InterruptedException e) {
              break;
            }
          }
          return leaveOpen;
        }
      };
    }
  }
}
