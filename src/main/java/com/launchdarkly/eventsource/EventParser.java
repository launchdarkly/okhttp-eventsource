package com.launchdarkly.eventsource;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Adapted from https://github.com/aslakhellesoy/eventsource-java/blob/master/src/main/java/com/github/eventsource/client/impl/EventStreamParser.java
 */
public class EventParser {
  private static final String DATA = "data";
  private static final String ID = "id";
  private static final String EVENT = "event";
  private static final String RETRY = "retry";

  private static final String DEFAULT_EVENT = "message";
  private static final String EMPTY_STRING = "";
  private static final Pattern DIGITS_ONLY = Pattern.compile("^[\\d]+$");

  private final EventHandler handler;
  private final ConnectionHandler connectionHandler;
  private final Logger logger;
  private final URI origin;

  private StringBuffer data = new StringBuffer();
  private String lastEventId;
  private String eventName = DEFAULT_EVENT;

  EventParser(URI origin, EventHandler handler, ConnectionHandler connectionHandler, Logger logger) {
    this.handler = handler;
    this.origin = origin;
    this.connectionHandler = connectionHandler;
    this.logger = logger;
  }

  /**
   * Accepts a single line of input and updates the parser state. If this completes a valid event,
   * the event is sent to the {@link EventHandler}.
   * @param line an input line
   */
  public void line(String line) {
    logger.debug("Parsing line: {}", line);
    int colonIndex;
    if (line.trim().isEmpty()) {
      dispatchEvent();
    } else if (line.startsWith(":")) {
      processComment(line.substring(1).trim());
    } else if ((colonIndex = line.indexOf(":")) != -1) {
      String field = line.substring(0, colonIndex);
      String value = line.substring(colonIndex + 1);
      if (!value.isEmpty() && value.charAt(0) == ' ') {
        value = value.replaceFirst(" ", EMPTY_STRING);
      }
      processField(field, value);
    } else {
      processField(line, EMPTY_STRING);
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
      if (!value.contains("\u0000")) { // per specification, id field cannot contain a null character
        lastEventId = value;
      }
    } else if (EVENT.equals(field)) {
      eventName = value;
    } else if (RETRY.equals(field) && isNumber(value)) {
      connectionHandler.setReconnectionTime(Duration.ofMillis(Long.parseLong(value)));
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
      logger.debug("Dispatching message: \"{}\", {}", eventName, message);
      handler.onMessage(eventName, message);
    } catch (Exception e) {
      logger.warn("Message handler threw an exception: " + e.toString());
      logger.debug("Stack trace: {}", new LazyStackTrace(e));
      handler.onError(e);
    }
    data = new StringBuffer();
    eventName = DEFAULT_EVENT;
  }
}