package ssetest;

import java.util.Map;

public abstract class Representations {
  public static class Status {
    String[] capabilities;
  }

  public static class StreamOptions {
    String streamUrl;
    String callbackUrl;
    String tag;
    Map<String, String> headers;
    Integer initialDelayMs;
    Integer readTimeoutMs;
    String lastEventId;
    String method;
    String body;
  }

  public static class Message {
    String kind;
    EventMessage event;
    String comment;
    String error;
    
    public Message(String kind) {
      this.kind = kind;
    }
  }

  public static class EventMessage {
    String type;
    String data;
    String id;
  }
  
  public static class CommandParams {
    String command;
  }
}
