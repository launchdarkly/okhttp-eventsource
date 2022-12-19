package com.launchdarkly.eventsource;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

abstract class Helpers {
  static final Charset UTF8 = Charset.forName("UTF-8"); // SSE streams must be UTF-8, per the spec

  private Helpers() {}

  static long millisFromTimeUnit(long duration, TimeUnit timeUnit) {
    return timeUnitOrDefault(timeUnit).toMillis(duration);
  }

  static TimeUnit timeUnitOrDefault(TimeUnit timeUnit) {
    return timeUnit == null ? TimeUnit.MILLISECONDS : timeUnit;
  }
  
  static String utf8ByteArrayOutputStreamToString(ByteArrayOutputStream bytes) {
    try {
      return bytes.toString(UTF8.name()); // unfortunately, toString(Charset) isn't available in Java 8
    } catch (UnsupportedEncodingException e) {
      // COVERAGE: this shouldn't be possible, but if it somehow happens, all we can do is drop the data
      return null;
    }
  }
}
