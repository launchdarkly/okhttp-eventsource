package com.launchdarkly.eventsource;

import com.launchdarkly.eventsource.MockConnectStrategy.PipedStreamRequestHandler;
import com.launchdarkly.eventsource.MockConnectStrategy.RequestHandler;

import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.launchdarkly.eventsource.MockConnectStrategy.ORIGIN;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithDataAndThenEnd;
import static com.launchdarkly.eventsource.MockConnectStrategy.respondWithStream;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;

/**
 * Tests of EventSource's basic properties and parsing behavior. These tests do not
 * use real HTTP requests; instead, they use a ConnectStrategy that provides a
 * preconfigured InputStream. HTTP functionality is tested separately in
 * HttpConnectStrategyTest and HttpConnectStrategyWithEventSourceTest. 
 */
@SuppressWarnings("javadoc")
public class EventSourceReadingTest {
  @Rule public TestScopedLoggerRule testLogger = new TestScopedLoggerRule();

  private EventSource.Builder baseBuilder(MockConnectStrategy mock) {
    return new EventSource.Builder(mock).logger(testLogger.getLogger());
  }

  @Test
  public void expectedStateBeforeStart() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(respondWithStream());
    
    try (EventSource es = baseBuilder(mock).build()) {
      assertThat(es.getState(), equalTo(ReadyState.RAW));
      assertThat(es.getOrigin(), equalTo(ORIGIN));
      assertThat(es.getLastEventId(), nullValue());
      assertThat(es.getBaseRetryDelayMillis(), equalTo(EventSource.DEFAULT_RETRY_DELAY_MILLIS));
    }
  }
  
  @Test
  public void expectedStateAfterStart() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(respondWithStream());
    
    try (EventSource es = baseBuilder(mock).build()) {
      es.start();
      
      assertThat(es.getState(), equalTo(ReadyState.OPEN));
      assertThat(es.getOrigin(), equalTo(ORIGIN));
      assertThat(es.getLastEventId(), nullValue());
      assertThat(es.getBaseRetryDelayMillis(), equalTo(EventSource.DEFAULT_RETRY_DELAY_MILLIS));
    }
  }

  @Test
  public void expectedStateAfterStop() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(respondWithStream());
    
    try (EventSource es = baseBuilder(mock).build()) {
      es.start();
      
      assertThat(es.getState(), equalTo(ReadyState.OPEN));
      
      es.stop();
      
      assertThat(es.getState(), equalTo(ReadyState.CLOSED));
    }
  }
  
  @Test
  public void expectedStateAfterClose() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(respondWithStream());
    
    try (EventSource es = baseBuilder(mock).build()) {
      es.start();
      
      assertThat(es.getState(), equalTo(ReadyState.OPEN));
      
      es.close();
      
      assertThat(es.getState(), equalTo(ReadyState.SHUTDOWN));
    }
  }

  @Test
  public void startOnAlreadyStartedStreamHasNoEffect() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(respondWithStream());
    
    try (EventSource es = baseBuilder(mock).build()) {
      es.start();
      
      mock.expectConnection();
      
      es.start();
      
      mock.expectNoNewConnection(10, TimeUnit.MILLISECONDS);
      assertThat(es.getState(), equalTo(ReadyState.OPEN));
    }
  }

  @Test
  public void eventSourceReadsEvents() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream = respondWithStream();
    mock.configureRequests(stream);
    stream.provideData("event: a\ndata: data1\n\nevent: b\ndata: data2\n\n");
    
    try (EventSource es = baseBuilder(mock).build()) {
      es.start();
      
      assertThat(es.readAnyEvent(), equalTo(
          new MessageEvent("a", "data1", null, ORIGIN)));

      assertThat(es.readAnyEvent(), equalTo(
          new MessageEvent("b", "data2", null, ORIGIN)));
    }
  }
  
  @Test
  public void eventSourceReadsEventsFromChunkedStream() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream = respondWithStream();
    mock.configureRequests(stream);
    
    String body = "data: data-by-itself\n\n" +
        "event: event-with-data\n" +
        "data: abc\n\n" +
        ": this is a comment\n" +
        "event: event-with-more-data-and-id\n" +
        "id: my-id\n" +
        "data: abc\n" +
        "data: def\n\n";
    stream.provideDataInChunks(body, 5, 10);
    // the 10ms delay is to make it more likely that the parser really will receive
    // the data one chunk at a time, rather than it all being buffered together

    try (EventSource es = baseBuilder(mock).build()) {
      es.start();

      assertThat(es.readAnyEvent(), equalTo(
          new MessageEvent("message", "data-by-itself", null, ORIGIN)));

      assertThat(es.readAnyEvent(), equalTo(
          new MessageEvent("event-with-data", "abc", null, ORIGIN)));

      assertThat(es.readAnyEvent(), equalTo(
          new CommentEvent("this is a comment")));

      assertThat(es.readAnyEvent(), equalTo(
          new MessageEvent("event-with-more-data-and-id", "abc\ndef", "my-id", ORIGIN)));
    }
  }

  @Test
  public void initialLastEventIdIsPassedToConnectStrategy() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(respondWithStream());
    String lastId = "123";
    
    try (EventSource es = baseBuilder(mock)
        .lastEventId(lastId)
        .build()) {
      es.start();
      
      MockConnectStrategy.ConnectParams connectParams = mock.expectConnection();
      assertThat(connectParams.getLastEventId(), equalTo(lastId));
    }
  }

  @Test
  public void lastEventIdIsUpdatedFromEvent() throws Exception {
    String newLastId = "099";
    String eventData = "some-data";
    String body = "id: " + newLastId + "\ndata: " + eventData + "\n\n";

    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream = respondWithStream();
    mock.configureRequests(stream);
    stream.provideData(body);

    try (EventSource es = baseBuilder(mock)
        .retryDelay(10, null)
        .build()) {
      es.start();

      MockConnectStrategy.ConnectParams connectParams1 = mock.expectConnection();
      assertThat(connectParams1.getLastEventId(), nullValue());

      assertThat(es.readAnyEvent(), equalTo(
          new MessageEvent("message", eventData, newLastId, ORIGIN)));

      assertThat(es.getLastEventId(), equalTo(newLastId));
      
      es.stop();
      es.start();

      MockConnectStrategy.ConnectParams connectParams2 = mock.expectConnection();
      assertThat(connectParams2.getLastEventId(), equalTo(newLastId));
    }
  }

  @Test
  public void initialRetryDelayIsSetFromBuilder() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();

    try (EventSource es = baseBuilder(mock).retryDelay(6, TimeUnit.SECONDS).build()) {
      assertEquals(6000, es.getBaseRetryDelayMillis());
    }
  }

  @Test
  public void retryDelayIsUpdatedFromEvent() throws Exception {
    String eventData = "some-data";    
    String body = "retry: 300\n" + "\ndata: " + eventData + "\n\n";

    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream = respondWithStream();
    mock.configureRequests(stream);
    stream.provideData(body);

    try (EventSource es = baseBuilder(mock)
        .retryDelay(10, null)
        .build()) {
      es.start();

      MockConnectStrategy.ConnectParams connectParams1 = mock.expectConnection();
      assertThat(connectParams1.getLastEventId(), nullValue());

      assertThat(es.readAnyEvent(), equalTo(
          new MessageEvent("message", eventData, null, ORIGIN)));
      
      assertEquals(300, es.getBaseRetryDelayMillis());
    }
  }
  
  @Test
  public void canIterateMessages() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream = respondWithStream();
    mock.configureRequests(stream);
    stream.provideData("event: a\ndata: data1\n\n:nice\nevent: b\ndata: data2\n\n");
    
    BlockingQueue<MessageEvent> queue = new LinkedBlockingQueue<>();
    Semaphore iteratorFinished = new Semaphore(0);
    
    new Thread(() -> {
      try (EventSource es = baseBuilder(mock).build()) {
        for (MessageEvent m: es.messages()) {
          queue.add(m);
        }
        iteratorFinished.release();
      }
    }).start();
    
    MessageEvent m1 = queue.poll(1, TimeUnit.SECONDS);
    assertThat(m1, equalTo(new MessageEvent("a", "data1", null, ORIGIN)));

    MessageEvent m2 = queue.poll(1, TimeUnit.SECONDS);
    assertThat(m2, equalTo(new MessageEvent("b", "data2", null, ORIGIN)));

    assertThat("iterator should not have finished yet because we didn't close the stream",
        iteratorFinished.tryAcquire(10, TimeUnit.MILLISECONDS), is(false));
    
    stream.close();
    
    assertThat("timed out waiting for iterator on reader thread to finish",
        iteratorFinished.tryAcquire(1, TimeUnit.SECONDS), is(true));
  }

  @Test
  public void canIterateEvents() throws Exception {
    // Same as canIterateMessages(), but we're reading from anyEvents() rather than
    // messages() so we get additional things like StartedEvent and CommentEvent.
    
    MockConnectStrategy mock = new MockConnectStrategy();
    PipedStreamRequestHandler stream = respondWithStream();
    mock.configureRequests(stream);
    stream.provideData("event: a\ndata: data1\n\n:nice\nevent: b\ndata: data2\n\n");
    
    BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
    Semaphore iteratorFinished = new Semaphore(0);
    
    new Thread(() -> {
      try (EventSource es = baseBuilder(mock).build()) {
        for (StreamEvent m: es.anyEvents()) {
          queue.add(m);
        }
        iteratorFinished.release();
      }
    }).start();

    StreamEvent e1 = queue.poll(1, TimeUnit.SECONDS);
    assertThat(e1, equalTo(new StartedEvent()));
    
    StreamEvent e2 = queue.poll(1, TimeUnit.SECONDS);
    assertThat(e2, equalTo(new MessageEvent("a", "data1", null, ORIGIN)));

    StreamEvent e3 = queue.poll(1, TimeUnit.SECONDS);
    assertThat(e3, equalTo(new CommentEvent("nice")));

    StreamEvent e4 = queue.poll(1, TimeUnit.SECONDS);
    assertThat(e4, equalTo(new MessageEvent("b", "data2", null, ORIGIN)));

    assertThat("iterator should not have finished yet because we didn't close the stream",
        iteratorFinished.tryAcquire(10, TimeUnit.MILLISECONDS), is(false));
    
    stream.close();
    
    assertThat("timed out waiting for iterator on reader thread to finish",
        iteratorFinished.tryAcquire(1, TimeUnit.SECONDS), is(true));
  }

  @Test
  public void canExplicitlyReadAnotherEventWhileIteratingEvents() throws Exception {
    MockConnectStrategy mock = new MockConnectStrategy();
    mock.configureRequests(respondWithDataAndThenEnd(
        "data: data1\n\n:nice\ndata: data2\n\ndata:data3\n\n"));
    
    BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
    
    try (EventSource es = baseBuilder(mock).build()) {
      for (StreamEvent event: es.anyEvents()) {
        queue.add(event);
        
        if (event instanceof CommentEvent) {
          queue.add(es.readAnyEvent());
        }
      }
    }
    
    assertThat(queue.take(), equalTo(new StartedEvent()));
    assertThat(queue.take(), equalTo(new MessageEvent("message", "data1", null, ORIGIN)));
    assertThat(queue.take(), equalTo(new CommentEvent("nice")));
    assertThat(queue.take(), equalTo(new MessageEvent("message", "data2", null, ORIGIN)));
  }

  @Test
  public void canIterateMessagesWithAutoRetry() throws Exception {
    // Same as canIterateMessages() except that when auto-retry is enabled,
    // the iterator does not stop when the stream closes-- EventSource will
    // transparently reconnect.
    MockConnectStrategy mock = new MockConnectStrategy();
    
    RequestHandler stream1 = respondWithDataAndThenEnd("event: a\ndata: data1\n\n"); 
    PipedStreamRequestHandler stream2 = respondWithStream();
    stream2.provideData("event: b\ndata: data2\n\n");
    mock.configureRequests(stream1, stream2);
    
    BlockingQueue<MessageEvent> queue = new LinkedBlockingQueue<>();
    Semaphore iteratorFinished = new Semaphore(0);
    
    // Here we want to be able to access the EventSource from outside of the
    // reading thread, to prove that calling stop() on it stops the iterator.
    AtomicReference<EventSource> es = new AtomicReference<>();
    
    try {
      new Thread(() -> {
        es.set(baseBuilder(mock)
            .errorStrategy(ErrorStrategy.alwaysContinue())
            .retryDelay(1, TimeUnit.MILLISECONDS)
            .build());
        for (MessageEvent m: es.get().messages()) {
          queue.add(m);
        }
        iteratorFinished.release();
      }).start();
      
      MessageEvent m1 = queue.poll(1, TimeUnit.SECONDS);
      assertThat(m1, equalTo(new MessageEvent("a", "data1", null, ORIGIN)));
  
      MessageEvent m2 = queue.poll(1, TimeUnit.SECONDS);
      assertThat(m2, equalTo(new MessageEvent("b", "data2", null, ORIGIN)));
  
      assertThat("iterator should not have finished yet because we didn't close the stream",
          iteratorFinished.tryAcquire(10, TimeUnit.MILLISECONDS), is(false));
    } finally { 
      es.get().close();
    }
    
    assertThat("timed out waiting for iterator on reader thread to finish",
        iteratorFinished.tryAcquire(1, TimeUnit.SECONDS), is(true));
  }
  
  @Test
  public void canIterateEventsWithAutoRetry() throws Exception {
    // Same as canIterateEvents() except that when auto-retry is enabled,
    // the iterator does not stop when the stream closes-- EventSource will
    // transparently reconnect.
    MockConnectStrategy mock = new MockConnectStrategy();
    
    RequestHandler stream1 = respondWithDataAndThenEnd("event: a\ndata: data1\n\n"); 
    PipedStreamRequestHandler stream2 = respondWithStream();
    stream2.provideData("event: b\ndata: data2\n\n");
    mock.configureRequests(stream1, stream2);
    
    BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
    Semaphore iteratorFinished = new Semaphore(0);
    
    // Here we want to be able to access the EventSource from outside of the
    // reading thread, to prove that calling stop() on it stops the iterator.
    AtomicReference<EventSource> es = new AtomicReference<>();
    
    try {
      new Thread(() -> {
        es.set(baseBuilder(mock)
            .errorStrategy(ErrorStrategy.alwaysContinue())
            .retryDelay(1, TimeUnit.MILLISECONDS)
            .build());
        for (StreamEvent e: es.get().anyEvents()) {
          queue.add(e);
        }
        iteratorFinished.release();
      }).start();

      StreamEvent e1 = queue.poll(1, TimeUnit.SECONDS);
      assertThat(e1, equalTo(new StartedEvent()));
      
      StreamEvent e2 = queue.poll(1, TimeUnit.SECONDS);
      assertThat(e2, equalTo(new MessageEvent("a", "data1", null, ORIGIN)));

      StreamEvent e3 = queue.poll(1, TimeUnit.SECONDS);
      assertThat(e3, equalTo(new FaultEvent(new StreamClosedByServerException())));

      StreamEvent e4 = queue.poll(1, TimeUnit.SECONDS);
      assertThat(e4, equalTo(new StartedEvent()));

      StreamEvent e5 = queue.poll(1, TimeUnit.SECONDS);
      assertThat(e5, equalTo(new MessageEvent("b", "data2", null, ORIGIN)));

      assertThat("iterator should not have finished yet because we didn't close the stream",
          iteratorFinished.tryAcquire(10, TimeUnit.MILLISECONDS), is(false));
    } finally { 
      es.get().close();
    }
    
    assertThat("timed out waiting for iterator on reader thread to finish",
        iteratorFinished.tryAcquire(1, TimeUnit.SECONDS), is(true));
  }
}
