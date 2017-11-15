package com.launchdarkly.eventsource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Adapted from https://github.com/aslakhellesoy/eventsource-java/blob/master/src/main/java/com/github/eventsource/client/impl/EventStreamParser.java
 */
public class EventParser {
  private static final Logger logger = LoggerFactory.getLogger(EventParser.class);
  private static final String DATA = "data";
  private static final String ID = "id";
  private static final String EVENT = "event";
  private static final String RETRY = "retry";

  private static final String DEFAULT_EVENT = "message";
  private static final String EMPTY_STRING = "";
  private static final Pattern DIGITS_ONLY = Pattern.compile("^[\\d]+$");

  private final EventHandler handler;
  private final ConnectionHandler connectionHandler;
  private final URI origin;

  private StringBuffer data = new StringBuffer();
  private String lastEventId;
  private String eventName = DEFAULT_EVENT;

  EventParser(URI origin, EventHandler handler, ConnectionHandler connectionHandler) {
    this.handler = handler;
    this.origin = origin;
    this.connectionHandler = connectionHandler;
  }

  public void line(String line) {
    logger.debug("Parsing line: " + line);
    int colonIndex;
    if (line.trim().isEmpty()) {
      dispatchEvent();
    } else if (line.startsWith(":")) {
      processComment(line.substring(1).trim());
    } else if ((colonIndex = line.indexOf(":")) != -1) {
      String field = line.substring(0, colonIndex);
      String value = line.substring(colonIndex + 1);
      if (value.charAt(0)==' ') {
        value = value.replaceFirst(" ", EMPTY_STRING);
      }
      processField(field, value);
    } else {
      processField(line.trim(), EMPTY_STRING); // The spec doesn't say we need to trim the line, but I assume that's an oversight.
    }
  }

  private void processComment(String comment) {
    try {
      handler.onComment(comment);
    } catch (Exception e) {
      handler.onError(e);
    }
  }

  private void processField(String field, String value) {
    if (DATA.equals(field)) {
      data.append(value).append("\n");
    } else if (ID.equals(field)) {
      lastEventId = value;
    } else if (EVENT.equals(field)) {
      eventName = value;
    } else if (RETRY.equals(field) && isNumber(value)) {
      connectionHandler.setReconnectionTimeMs(Long.parseLong(value));
    }
  }

  private boolean isNumber(String value) {
    return DIGITS_ONLY.matcher(value).matches();
  }

  private void dispatchEvent() {
    if (data.length() == 0) {
      return;
    }
    String dataString = data.toString();
    if (dataString.endsWith("\n")) {
      dataString = dataString.substring(0, dataString.length() - 1);
    }
    MessageEvent message = new MessageEvent(dataString, lastEventId, origin);
    connectionHandler.setLastEventId(lastEventId);
    try {
      handler.onMessage(eventName, message);
    } catch (Exception e) {
      handler.onError(e);
    }
    data = new StringBuffer();
    eventName = DEFAULT_EVENT;
  }
}