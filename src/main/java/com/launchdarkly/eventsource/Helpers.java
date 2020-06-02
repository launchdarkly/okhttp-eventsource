package com.launchdarkly.eventsource;

abstract class Helpers {
  private Helpers() {}

  // Returns 2**k, or Integer.MAX_VALUE if 2**k would overflow
  static int pow2(int k) {
    return (k < Integer.SIZE - 1) ? (1 << k) : Integer.MAX_VALUE;
  }
}
