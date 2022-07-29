package com.launchdarkly.eventsource;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogAdapter.Channel;
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
        return new ChannelImpl(customLogger);
      }
    };
    return LDLogger.withAdapter(tempAdapter, "");
  }
  
  private static final class ChannelImpl implements Channel {
    private final Logger wrappedLogger;

    ChannelImpl(Logger wrappedLogger) {
      this.wrappedLogger = wrappedLogger;
    }
    
    @Override
    public void log(LDLogLevel level, String format, Object param1, Object param2) {
      String s = level == LDLogLevel.DEBUG ? null : SimpleFormat.format(format, param1, param2);
      switch (level) {
      case DEBUG:
        wrappedLogger.debug(format, param1, param2);
      case INFO:
        wrappedLogger.info(s);
      case WARN:
        wrappedLogger.warn(s);
      case ERROR:
        wrappedLogger.error(s);
      default:
        break;
      }
    }
    
    @Override
    public void log(LDLogLevel level, String format, Object... params) {
      String s = SimpleFormat.format(format, params);
      switch (level) {
      case DEBUG:
        wrappedLogger.debug(s, null);
      case INFO:
        wrappedLogger.info(s);
      case WARN:
        wrappedLogger.warn(s);
      case ERROR:
        wrappedLogger.error(s);
      default:
        break;
      }
    }
    
    @Override
    public void log(LDLogLevel level, String format, Object param) {
      String s = level == LDLogLevel.DEBUG ? null : SimpleFormat.format(format, param);
      switch (level) {
      case DEBUG:
        wrappedLogger.debug(format, param);
      case INFO:
        wrappedLogger.info(s);
      case WARN:
        wrappedLogger.warn(s);
      case ERROR:
        wrappedLogger.error(s);
      default:
        break;
      }
    }
    
    @Override
    public void log(LDLogLevel level, Object message) {
      switch (level) {
      case DEBUG:
        wrappedLogger.debug("{}", message);
      case INFO:
        wrappedLogger.info(message.toString());
      case WARN:
        wrappedLogger.warn(message.toString());
      case ERROR:
        wrappedLogger.error(message.toString());
      default:
        break;
      }
    }
    
    @Override
    public boolean isEnabled(LDLogLevel level) {
      return true;
    }
  }
}
