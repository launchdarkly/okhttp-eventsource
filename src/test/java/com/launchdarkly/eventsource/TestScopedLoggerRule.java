package com.launchdarkly.eventsource;

import org.junit.rules.TestName;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("javadoc")
public class TestScopedLoggerRule extends TestName {
  private TestLogger logger;
  
  public TestLogger getLogger() {
    if (logger == null) {
      logger = new TestLogger(getMethodName()); 
    }
    return logger;
  }
  
  public static class TestLogger implements Logger {
    private final String loggerName;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    private final BlockingQueue<String> lines = new ArrayBlockingQueue<>(1000);
    
    TestLogger(String loggerName) {
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
    
    public String awaitMessageMatching(String regex) {
      while (true) {
        try {
          String line = lines.poll(5, TimeUnit.SECONDS);
          if (line == null) {
            break;
          }
          if (Pattern.compile(regex).matcher(line).find()) {
            return line;
          }
        } catch (InterruptedException e) {
          break;
        }
      }
      throw new RuntimeException("did not see a log message matching: " + regex);
    }
    
    private void log(String levelName, String message) {
      String line = levelName + ": " + message;
      lines.add(line);
      System.out.println(dateFormat.format(new Date()) + " [" + loggerName + "] " + line);
    }
    
    private static String substitute(String format, Object param) {
      return format.replaceFirst("\\{\\}", param == null ? "" : Matcher.quoteReplacement(param.toString()));
    }
  }
}
