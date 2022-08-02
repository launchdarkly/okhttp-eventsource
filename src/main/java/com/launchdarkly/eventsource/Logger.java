package com.launchdarkly.eventsource;

/**
 * Deprecated interface for a custom logger that an application can provide to receive EventSource logging.
 * <p>
 * This has been superseded by {@link EventSource.Builder#logger(com.launchdarkly.logging.LDLogger)},
 * which uses the <a href="https://github.com/launchdarkly/java-logging">com.launchdarkly.logging</a>
 * facade, providing many options for customizing logging behavior. The {@link Logger} interface
 * defined by {@code okhttp-eventsource} will be removed in a future major version release
 * <p>
 * If you do not provide a logger, the default is to send log output to SLF4J.
 *
 * @since 2.3.0
 * @deprecated use {@link EventSource.Builder#logger(com.launchdarkly.logging.LDLogger)}
 */
@Deprecated
public interface Logger {
  /**
   * Logs a debug message. Debug output includes verbose details that applications will normally
   * not want to see.
   * <p>
   * Because debug messages can include variable text that might be long or require overhead to
   * compute, this method uses the same simple placeholder syntax as SLF4J: when the string "{}"
   * appears in {@code format}, it should be replaced with the result of calling {@code toString()}
   * on the parameter value. For efficiency, the logger should avoid doing this computation
   * unless it is actually going to show the debug output.
   * <p>
   * The logging methods for other log levels use only simple message strings, because those
   * methods are not called as frequently and will have minimal overhead of string concatenation.
   * 
   * @param format a format string that may include a placeholder
   * @param param the value to be substited for the placeholder
   */
  void debug(String format, Object param);
  
  /**
   * Logs a debug message with two parameters.
   * <p>
   * This is the same as {@link #debug(String, Object)}, but allows the format string to have two
   * "{}" placeholders, corresponding to the two parameters in the same order.
   * 
   * @param format a format string that may include placeholders
   * @param param1 the first parameter value
   * @param param2 the second parameter value
   */
  void debug(String format, Object param1, Object param2);
  
  /**
   * Logs an informational message. This output describes normal operation of the EventSource.
   * 
   * @param message the message
   */
  void info(String message);
  
  /**
   * Logs a warning message. This indicates a possibly noteworthy condition that does not
   * necessarily require intervention.
   * 
   * @param message the message
   */
  void warn(String message);
  
  /**
   * Logs a message about a serious error. {@code EventSource} does not currently use this level,
   * since connection errors can always be retried; it is reserved for future use.
   * 
   * @param message the message
   */
  void error(String message);
}
