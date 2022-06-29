package com.launchdarkly.eventsource;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * This class wraps a exception so that we can log its stacktrace in a debug message without
 * actually computing the stacktrace unless the logger has enabled debug output.
 * <p>
 * We should only use this for cases where the stacktrace may actually be informative, such
 * as an unchecked exception thrown from an application's message handler. For I/O exceptions
 * where the source of the exception can be clearly indicated by the log message, we should
 * not log stacktraces.
 */
final class LazyStackTrace {
  private final Throwable throwable;
  
  LazyStackTrace(Throwable throwable) {
    this.throwable = throwable;
  }
  
  @Override
  public String toString() {
    StringWriter sw = new StringWriter();
    throwable.printStackTrace(new PrintWriter(sw));
    return sw.toString();
  }
}
