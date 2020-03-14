package com.launchdarkly.eventsource;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNull;

class Stubs {
  static class LogItem {
    private final String action;
    private final String[] params;
    
    private LogItem(String action, String[] params) {
      this.action = action;
      this.params = params;
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
      return new LogItem("error", new String[] { t.toString() });
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
    public final BlockingQueue<LogItem> log = new ArrayBlockingQueue<>(100);
    
    public void onOpen() throws Exception {
      log.add(LogItem.opened());
    }
    
    public void onMessage(String event, MessageEvent messageEvent) throws Exception {
      log.add(LogItem.event(event, messageEvent.getData(), messageEvent.getLastEventId()));
    }
    
    public void onError(Throwable t) {
      log.add(LogItem.error(t));
    }
    
    public void onComment(String comment) throws Exception {
      log.add(LogItem.comment(comment));
    }
    
    @Override
    public void onClosed() throws Exception {
      log.add(LogItem.closed());
    }

    void assertNoMoreLogItems() {
      try {
        assertNull(log.poll(100, TimeUnit.MILLISECONDS));
      } catch (InterruptedException e) {}
    }
  }
}
