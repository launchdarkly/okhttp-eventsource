package com.launchdarkly.eventsource;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.SimpleFormat;

@SuppressWarnings("deprecation")
abstract class LoggerBridge {
  // Provides an adapter from the deprecated Logger interface to LDLogger.
  private LoggerBridge() {}
  
  static LDLogger wrapLogger(final Logger customLogger) {
    LDLogAdapter tempAdapter = new LDLogAdapter() {
      @Override
      public Channel newChannel(String name) {
        return new Channel() {
          @Override
          public void log(LDLogLevel level, String format, Object param1, Object param2) {
            String s = level == LDLogLevel.DEBUG ? null : SimpleFormat.format(format, param1, param2);
            switch (level) {
            case DEBUG:
              customLogger.debug(format, param1, param2);
            case INFO:
              customLogger.info(s);
            case WARN:
              customLogger.warn(s);
            case ERROR:
              customLogger.error(s);
            default:
              break;
            }
          }
          
          @Override
          public void log(LDLogLevel level, String format, Object... params) {
            String s = SimpleFormat.format(format, params);
            switch (level) {
            case DEBUG:
              customLogger.debug(s, null);
            case INFO:
              customLogger.info(s);
            case WARN:
              customLogger.warn(s);
            case ERROR:
              customLogger.error(s);
            default:
              break;
            }
          }
          
          @Override
          public void log(LDLogLevel level, String format, Object param) {
            String s = level == LDLogLevel.DEBUG ? null : SimpleFormat.format(format, param);
            switch (level) {
            case DEBUG:
              customLogger.debug(format, param);
            case INFO:
              customLogger.info(s);
            case WARN:
              customLogger.warn(s);
            case ERROR:
              customLogger.error(s);
            default:
              break;
            }
          }
          
          @Override
          public void log(LDLogLevel level, Object message) {
            switch (level) {
            case DEBUG:
              customLogger.debug("{}", message);
            case INFO:
              customLogger.info(message.toString());
            case WARN:
              customLogger.warn(message.toString());
            case ERROR:
              customLogger.error(message.toString());
            default:
              break;
            }
          }
          
          @Override
          public boolean isEnabled(LDLogLevel level) {
            return true;
          }
        };
      }
    };
    return LDLogger.withAdapter(tempAdapter, "");
  }
}
