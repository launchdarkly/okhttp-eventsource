package com.launchdarkly.eventsource;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.Logs;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * These tests verify that EventSource interacts with ConnectStrategy methods in the
 * expected way, regardless of what ConnectStrategy implementation is being used.
 *
 * Details of how EventSource parses the InputStream that is returned by ConnectStrategy
 * are covered by EventSourceReadingTest. 
 */
@SuppressWarnings("javadoc")
public class EventSourceConnectStrategyUsageTest {
  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();

  private static class ClientBaseImpl extends ConnectStrategy.Client {
    @Override
    public void close() throws IOException {}
    
    @Override
    public Result connect(String lastEventId) throws StreamException {
      throw new StreamException("did not expect connect() to be called in this test");
    }
    
    @Override
    public boolean awaitClosed(long timeoutMillis) throws InterruptedException {
      return false;
    }

    @Override
    public URI getOrigin() {
      return null;
    }
  }
  
  private static InputStream makeEmptyInputStream() {
    return new ByteArrayInputStream(new byte[0]);
  }
  
  @Test
  public void connectStrategyIsCalledImmediatelyToCreateClient() throws Exception {
    BlockingQueue<ConnectStrategy.Client> createdClient = new LinkedBlockingQueue<>();
    
    ConnectStrategy cs = new ConnectStrategy() {
      @Override
      public Client createClient(LDLogger logger) {
        Client c = new ClientBaseImpl();
        createdClient.add(c);
        return c;
      }
    };
    
    try (EventSource es = new EventSource.Builder(cs).build()) {
      assertThat(createdClient.poll(1, TimeUnit.MILLISECONDS), notNullValue());
    }
  }

  @Test
  public void loggerIsPassedToConnectStrategy() throws Exception {
    LDLogger myLogger = LDLogger.withAdapter(Logs.capture(), "");
    
    BlockingQueue<LDLogger> receivedLogger = new LinkedBlockingQueue<>();
    
    ConnectStrategy cs = new ConnectStrategy() {
      @Override
      public Client createClient(LDLogger logger) {
        receivedLogger.add(logger);
        return new ClientBaseImpl();
      }
    };
    
    try (EventSource es = new EventSource.Builder(cs)
        .logger(myLogger)
        .build()) {
      assertThat(receivedLogger.poll(1, TimeUnit.MILLISECONDS), sameInstance(myLogger));
    }
  }

  @Test
  public void connectIsNotCalledBeforeStart() throws Exception {
    Semaphore calledConnect = new Semaphore(0);
    
    ConnectStrategy cs = new ConnectStrategy() {
      @Override
      public Client createClient(LDLogger logger) {
        return new ClientBaseImpl() {
          @Override
          public Result connect(String lastEventId) throws StreamException {
            calledConnect.release();
            throw new StreamException("deliberate error");
          }
        };
      }
    };
    
    try (EventSource es = new EventSource.Builder(cs).build()) {
      assertThat(calledConnect.tryAcquire(1, TimeUnit.MILLISECONDS), is(false));
    }
  }

  @Test
  public void connectIsCalledOnStart() throws Exception {
    Semaphore calledConnect = new Semaphore(0);
    
    ConnectStrategy cs = new ConnectStrategy() {
      @Override
      public Client createClient(LDLogger logger) {
        return new ClientBaseImpl() {
          @Override
          public Result connect(String lastEventId) throws StreamException {
            calledConnect.release();
            return new Result(makeEmptyInputStream(), null, null);
          }
        };
      }
    };
    
    try (EventSource es = new EventSource.Builder(cs).build()) {
      es.start();
      assertThat(calledConnect.tryAcquire(1, TimeUnit.MILLISECONDS), is(true));
    }
  }

  @Test
  public void clientOriginUriIsReturnedByEventSourceOriginBeforeStart() throws Exception {
    URI origin = URI.create("http://fake");
    
    ConnectStrategy cs = new ConnectStrategy() {
      @Override
      public Client createClient(LDLogger logger) {
        return new ClientBaseImpl() {
          @Override
          public URI getOrigin() {
            return origin;
          }
        };
      }
    };
    
    try (EventSource es = new EventSource.Builder(cs).build()) {
      assertThat(es.getOrigin(), Matchers.equalTo(origin));
    }
  }

  @Test
  public void clientOriginUriIsReturnedByEventSourceOriginAfterStart() throws Exception {
    URI origin = URI.create("http://fake");
    
    ConnectStrategy cs = new ConnectStrategy() {
      @Override
      public Client createClient(LDLogger logger) {
        return new ClientBaseImpl() {
          @Override
          public URI getOrigin() {
            return origin;
          }
          
          @Override
          public Result connect(String lastEventId) throws StreamException {
            return new Result(
                makeEmptyInputStream(),
                null, // *not* overriding the origin URI here
                null
                );
          }
        };
      }
    };
    
    try (EventSource es = new EventSource.Builder(cs).build()) {
      es.start();
      
      assertThat(es.getOrigin(), Matchers.equalTo(origin));
    }
  }

  @Test
  public void clientOriginUriCanBeOverriddenPerRequest() throws Exception {
    URI origin1 = URI.create("http://fake1");
    URI origin2 = URI.create("http://fake2");
    
    ConnectStrategy cs = new ConnectStrategy() {
      @Override
      public Client createClient(LDLogger logger) {
        return new ClientBaseImpl() {
          @Override
          public URI getOrigin() {
            return origin1;
          }
          
          @Override
          public Result connect(String lastEventId) throws StreamException {
            return new Result(
                makeEmptyInputStream(),
                origin2,
                null
                );
          }
        };
      }
    };
    
    try (EventSource es = new EventSource.Builder(cs).build()) {
      es.start();
      
      assertThat(es.getOrigin(), Matchers.equalTo(origin2));
    }
  }
  
  @Test
  public void eventSourceDoesNotImmediatelyReadFromInputStreamOnStart() throws Exception {
    Semaphore calledRead = new Semaphore(0);
    InputStream instrumentedStream = new InputStream() {
      @Override
      public int read() throws IOException {
        calledRead.release();
        return -1;
      }
    };
    ConnectStrategy cs = new ConnectStrategy() {
      @Override
      public Client createClient(LDLogger logger) {
        return new ClientBaseImpl() {
          @Override
          public Result connect(String lastEventId) throws StreamException {
            return new Result(instrumentedStream, null, null);
          }
        };
      }
    };
    
    try (EventSource es = new EventSource.Builder(cs).build()) {
      es.start();
      
      assertThat(calledRead.tryAcquire(1, TimeUnit.MILLISECONDS), is(false));
    }
  }
  
  @Test
  public void eventSourceReadsFromInputStreamOnRead() throws Exception {
    Semaphore calledRead = new Semaphore(0);
    InputStream instrumentedStream = new InputStream() {
      @Override
      public int read() throws IOException {
        calledRead.release();
        return -1;
      }
    };
    ConnectStrategy cs = new ConnectStrategy() {
      @Override
      public Client createClient(LDLogger logger) {
        return new ClientBaseImpl() {
          @Override
          public Result connect(String lastEventId) throws StreamException {
            return new Result(instrumentedStream, null, null);
          }
        };
      }
    };
    
    try (EventSource es = new EventSource.Builder(cs).build()) {
      es.start();
      for (@SuppressWarnings("unused") StreamEvent event: es.anyEvents()) {}

      assertThat(calledRead.tryAcquire(1, TimeUnit.MILLISECONDS), is(true));
    }
  }
  
  @Test
  public void closerIsCalledWhenInterruptingStream() throws Exception {
    Semaphore calledClose = new Semaphore(0);
    ConnectStrategy cs = new ConnectStrategy() {
      @Override
      public Client createClient(LDLogger logger) {
        return new ClientBaseImpl() {
          @Override
          public Result connect(String lastEventId) throws StreamException {
            return new Result(makeEmptyInputStream(), null, new Closeable() {
              @Override
              public void close() throws IOException {
                calledClose.release();
              }
            });
          }
        };
      }
    };
    
    try (EventSource es = new EventSource.Builder(cs).build()) {
      es.start();

      assertThat(calledClose.tryAcquire(1, TimeUnit.MILLISECONDS), is(false));

      es.interrupt();
      
      assertThat(calledClose.tryAcquire(1, TimeUnit.MILLISECONDS), is(true));
    }
  }
  
  @Test
  public void closerIsCalledWhenClosingStream() throws Exception {
    Semaphore calledClose = new Semaphore(0);
    ConnectStrategy cs = new ConnectStrategy() {
      @Override
      public Client createClient(LDLogger logger) {
        return new ClientBaseImpl() {
          @Override
          public Result connect(String lastEventId) throws StreamException {
            return new Result(makeEmptyInputStream(), null, new Closeable() {
              @Override
              public void close() throws IOException {
                calledClose.release();
              }
            });
          }
        };
      }
    };
    
    try (EventSource es = new EventSource.Builder(cs).build()) {
      es.start();

      assertThat(calledClose.tryAcquire(1, TimeUnit.MILLISECONDS), is(false));      
    }
    assertThat(calledClose.tryAcquire(1, TimeUnit.MILLISECONDS), is(true));
  }

  @Test
  public void exceptionFromCloserDoesNotPreventClosingResponse() throws Exception {
    Semaphore calledClose = new Semaphore(0);
    ConnectStrategy cs = new ConnectStrategy() {
      @Override
      public Client createClient(LDLogger logger) {
        return new ClientBaseImpl() {
          @Override
          public Result connect(String lastEventId) throws StreamException {
            return new Result(makeEmptyInputStream(), null, new Closeable() {
              @Override
              public void close() throws IOException {

              }
            }, new Closeable() {
              @Override
              public void close() throws IOException {
                calledClose.release();
                throw new IOException("fake error");
              }
            });
          }
        };
      }
    };

    try (EventSource es = new EventSource.Builder(cs).build()) {
      es.start();

      assertThat(calledClose.tryAcquire(1, TimeUnit.MILLISECONDS), is(false));
    }
    assertThat(calledClose.tryAcquire(1, TimeUnit.MILLISECONDS), is(true));
  }


  @Test
  public void exceptionFromCloserDoesNotPreventClosingStream() throws Exception {
    Semaphore calledClose = new Semaphore(0);
    ConnectStrategy cs = new ConnectStrategy() {
      @Override
      public Client createClient(LDLogger logger) {
        return new ClientBaseImpl() {
          @Override
          public Result connect(String lastEventId) throws StreamException {
            return new Result(makeEmptyInputStream(), null, new Closeable() {
              @Override
              public void close() throws IOException {
                calledClose.release();
                throw new IOException("fake error");
              }
            });
          }
        };
      }
    };
    
    try (EventSource es = new EventSource.Builder(cs).build()) {
      es.start();

      assertThat(calledClose.tryAcquire(1, TimeUnit.MILLISECONDS), is(false));      
    }
    assertThat(calledClose.tryAcquire(1, TimeUnit.MILLISECONDS), is(true));
  }
  
  @Test
  public void awaitClosedIsCalled() throws Exception {
    Semaphore calledAwaitClose = new Semaphore(0);
    ConnectStrategy cs = new ConnectStrategy() {
      @Override
      public Client createClient(LDLogger logger) {
        return new ClientBaseImpl() {
          @Override
          public Result connect(String lastEventId) throws StreamException {
            return new Result(makeEmptyInputStream(), null, null);
          }
          
          @Override
          public boolean awaitClosed(long timeoutMillis) throws InterruptedException {
            calledAwaitClose.release();
            return true;
          }
        };
      }
    };
    
    try (EventSource es = new EventSource.Builder(cs).build()) {
      es.start();
      es.close();
      assertThat(es.awaitClosed(1, null), is(true));
      assertThat(calledAwaitClose.tryAcquire(1, TimeUnit.MILLISECONDS), is(true));      
    }
  }
}
