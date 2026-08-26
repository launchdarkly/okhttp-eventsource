package com.launchdarkly.eventsource;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

@SuppressWarnings("javadoc")
public class RetryDelayStrategyTest {
  @Test
  public void withBaseDelayMillisDefaultsToIdentityForCustomStrategies() {
    // Strategies that do not override withBaseDelayMillis (i.e., have no notion of
    // a base delay) return themselves unchanged when the wire retry hint fires.
    RetryDelayStrategy s = new RetryDelayStrategy() {
      @Override public long getDelayMillis() { return 500; }
      @Override public RetryDelayStrategy getNext() { return this; }
    };
    assertThat(s.withBaseDelayMillis(1234), sameInstance(s));
    assertThat(s.getDelayMillis(), equalTo(500L));
  }
}
