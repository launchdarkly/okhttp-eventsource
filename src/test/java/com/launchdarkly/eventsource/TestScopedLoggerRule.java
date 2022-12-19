package com.launchdarkly.eventsource;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.logging.Logs;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

@SuppressWarnings("javadoc")
public class TestScopedLoggerRule extends TestWatcher {
  private LogCapture logCapture;
  private LDLogger logger;
  
  private void init() {
    if (logCapture == null) {
      logCapture = Logs.capture();
      logger = LDLogger.withAdapter(logCapture, "");
    }
  }
  
  @Override
  protected void failed(Throwable e, Description description) {
    init();
    for (LogCapture.Message message: logCapture.getMessages()) {
      System.out.println("LOG {" + description.getDisplayName() + "} >>> " + message.toStringWithTimestamp());
    }
  }
  
  public LogCapture getLogCapture() {
    init();
    return logCapture;
  }
  
  public LDLogger getLogger() {
    init();
    return logger;
  }
  
  public String awaitMessageContaining(LDLogLevel level, String substring) {
    init();
    while (true) {
      LogCapture.Message m = logCapture.awaitMessage(5000);
      if (m == null) {
        break;
      }
      if (m.getLevel() == level && m.getText().contains(substring)) {
        return m.toString();
      }
    }
    throw new RuntimeException("did not see a log message containing: " + substring);
  }
}
