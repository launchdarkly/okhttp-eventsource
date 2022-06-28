package com.launchdarkly.eventsource;

import java.nio.charset.Charset;

abstract class Helpers {
  static final Charset UTF8 = Charset.forName("UTF-8"); // SSE streams must be UTF-8, per the spec

  private Helpers() {}

  // Returns 2**k, or Integer.MAX_VALUE if 2**k would overflow
  static int pow2(int k) {
    return (k < Integer.SIZE - 1) ? (1 << k) : Integer.MAX_VALUE;
  }
}
