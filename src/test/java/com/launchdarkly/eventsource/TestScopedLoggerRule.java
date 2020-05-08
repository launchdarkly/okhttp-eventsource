package com.launchdarkly.eventsource;

import org.junit.rules.TestName;

import java.text.SimpleDateFormat;
import java.util.Date;

@SuppressWarnings("javadoc")
public class TestScopedLoggerRule extends TestName {
  public Logger getLogger() {
    return new LoggerImpl(getMethodName());
  }
  
  private static class LoggerImpl implements Logger {
    private final String loggerName;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    
    LoggerImpl(String loggerName) {
      this.loggerName = loggerName;
    }
    
    @Override
    public void warn(String message) {
      log("WARN", message);
    }
    
    @Override
    public void info(String message) {
      log("INFO", message);
    }
    
    @Override
    public void error(String message) {
      log("ERROR", message);
    }
    
    @Override
    public void debug(String format, Object param1, Object param2) {
      log("DEBUG", substitute(substitute(format, param1), param2)); 
    }
    
    @Override
    public void debug(String format, Object param) {
      log("DEBUG", substitute(format, param));
    }
    
    private void log(String levelName, String message) {
      System.out.println(dateFormat.format(new Date()) + " [" + loggerName + "] " + levelName + ": " + message);
    }
    
    private static String substitute(String format, Object param) {
      return format.replaceFirst("\\{\\}", param == null ? "" : param.toString());
    }
  }
}
