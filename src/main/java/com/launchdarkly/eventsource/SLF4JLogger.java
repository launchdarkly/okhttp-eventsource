package com.launchdarkly.eventsource;

import org.slf4j.LoggerFactory;

/**
 * Internal logging adapter that is used by default to integrate with SLF4J.
 */
class SLF4JLogger implements Logger {
  private final org.slf4j.Logger slf4jLogger;
  final String name;
  
  SLF4JLogger(String loggerName) {
    this.slf4jLogger = LoggerFactory.getLogger(loggerName);
    this.name = loggerName;
  }

  @Override
  public void debug(String format, Object param) {
    slf4jLogger.debug(format, param);
  }

  @Override
  public void debug(String format, Object param1, Object param2) {
    slf4jLogger.debug(format, param1, param2);
  }

  @Override
  public void info(String message) {
    slf4jLogger.info(message);
  }

  @Override
  public void warn(String message) {
    slf4jLogger.warn(message);
  }

  @Override
  public void error(String message) {
    // COVERAGE: the unit tests do not exercise this method because the library currently never logs at error level.
    slf4jLogger.error(message);
  }
}
