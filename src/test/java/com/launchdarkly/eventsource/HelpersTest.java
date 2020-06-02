package com.launchdarkly.eventsource;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

@SuppressWarnings("javadoc")
public class HelpersTest {
  @Test
  public void pow2() {
    assertEquals(1024, Helpers.pow2(10));
    assertEquals(2147483647, Helpers.pow2(31));
    assertEquals(2147483647, Helpers.pow2(32));
  }
}
