package com.launchdarkly.eventsource;

import com.launchdarkly.logging.LDLogger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNull;

class Stubs {
  static class LogItem {
    final String action;
    private final String[] params;
    final Throwable error;

    private LogItem(String action, String[] params, Throwable error) {
      this.action = action;
      this.params = params;
      this.error = error;
    }

    private LogItem(String action, String[] params) {
      this(action, params, null);
    }

    public static LogItem opened() {
      return new LogItem("opened", null);
    }
    
    public static LogItem closed() {
      return new LogItem("closed", null);
    }
    
    public static LogItem event(String eventName, String data) {
      return event(eventName, data, null);
    }

    public static LogItem event(String eventName, String data, String eventId) {
      if (eventId == null) {
        
      }
      return new LogItem("event", eventId == null ? new String[] { eventName, data } :
        new String[] { eventName, data, eventId });
    }
    
    public static LogItem comment(String comment) {
      return new LogItem("comment", new String[] { comment });
    }
    
    public static LogItem error(Throwable t) {
      return new LogItem("error", new String[] { t.toString() }, t);
    }
    
    public String toString() {
      StringBuilder sb = new StringBuilder().append(action);
      if (params != null) {
        sb.append("(");
        for (int i = 0; i < params.length; i++) {
          if (i > 0) {
            sb.append(",");
          }
          sb.append(params[i]);
        }
        sb.append(")");
      }
      return sb.toString();
    }
    
    public boolean equals(Object o) {
      return (o instanceof LogItem) && toString().equals(o.toString());
    }
    
    public int hashCode() {
      return toString().hashCode();
    }
  }
  
  static class TestHandler implements EventHandler {
    private final BlockingQueue<LogItem> log = new ArrayBlockingQueue<>(100);
    private final LDLogger logger;
    volatile RuntimeException fakeError = null;
    volatile RuntimeException fakeErrorFromErrorHandler = null;
    
    public TestHandler() {
      this(null);
    }
    
    public TestHandler(LDLogger logger) {
      this.logger = logger;
    }
    
    private void maybeError() {
      if (fakeError != null) {
        throw fakeError;
      }
    }
    
    public void onOpen() throws Exception {
      add(LogItem.opened());
      maybeError();
    }
    
    public void onMessage(String event, MessageEvent messageEvent) throws Exception {
      add(LogItem.event(event, messageEvent.isStreamingData() ? "<streaming>" : messageEvent.getData(),
          messageEvent.getLastEventId()));
      maybeError();
    }
    
    public void onError(Throwable t) {
      add(LogItem.error(t));
      if (fakeErrorFromErrorHandler != null) {
        throw fakeErrorFromErrorHandler;
      }
    }
    
    public void onComment(String comment) throws Exception {
      add(LogItem.comment(comment));
      maybeError();
    }
    
    @Override
    public void onClosed() throws Exception {
      add(LogItem.closed());
      maybeError();
    }

    private void add(LogItem item) {
      if (logger != null) {
        logger.debug("TestHandler received: {}", item);
      }
      log.add(item);
    }
    
    LogItem awaitLogItem() {
      int timeoutSeconds = 5;
      try {
        LogItem item = log.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (item == null) {
          throw new RuntimeException("handler did not get an expected call within " + timeoutSeconds + " seconds");
        }
        return item;
      } catch (InterruptedException e) {
        throw new RuntimeException("thread interrupted while waiting for handler to be called");
      }
    }
    
    void assertNoMoreLogItems() {
      try {
        assertNull(log.poll(100, TimeUnit.MILLISECONDS));
      } catch (InterruptedException e) {}
    }
  }
  
  static class MessageSink implements EventHandler {
    private final BlockingQueue<MessageEvent> queue = new ArrayBlockingQueue<>(100);
    
    @Override
    public void onOpen() throws Exception {}

    @Override
    public void onClosed() throws Exception {}

    @Override
    public void onMessage(String event, MessageEvent messageEvent) throws Exception {
      queue.add(messageEvent);
    }

    @Override
    public void onComment(String comment) throws Exception {}

    @Override
    public void onError(Throwable t) {}
    
    MessageEvent awaitEvent() {
      int timeoutSeconds = 5;
      try {
        MessageEvent e = queue.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (e == null) {
          throw new RuntimeException("did not receive expected message within " + timeoutSeconds + " seconds");
        }
        return e;
      } catch (InterruptedException ex) {
        throw new RuntimeException("thread interrupted while waiting for handler to be called");
      }
    }
    
    void assertNoMoreEvents() {
      try {
        assertNull(queue.poll(100, TimeUnit.MILLISECONDS));
      } catch (InterruptedException e) {}
    }
  }
  
  static class StubConnectionHandler implements ConnectionHandler {
    @Override
    public void setReconnectTimeMillis(long reconnectTimeMillis) {
    }

    @Override
    public void setLastEventId(String lastEventId) {
    }    
  }
}
