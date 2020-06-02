package com.launchdarkly.eventsource;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

@SuppressWarnings("javadoc")
public class LazyStackTraceTest {
  @Test
  public void toStringGeneratesStackTrace() {
    Exception e = new Exception();
    LazyStackTrace l = new LazyStackTrace(e);
    String s = l.toString();
    assertThat(s, containsString("toStringGeneratesStackTrace"));
  }
}
