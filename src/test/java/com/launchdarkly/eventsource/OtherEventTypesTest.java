package com.launchdarkly.eventsource;

import com.launchdarkly.testhelpers.TypeBehavior;

import org.junit.Test;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

@SuppressWarnings("javadoc")
public class OtherEventTypesTest {
  @Test
  public void commentEvent() {
    assertThat(new CommentEvent("x").getText(), equalTo("x"));

    TypeBehavior.checkEqualsAndHashCode(Arrays.asList(
        () -> new CommentEvent("a"),
        () -> new CommentEvent("b"),
        () -> new CommentEvent(null)
        ));

    assertThat(new CommentEvent("a").toString(), equalTo("CommentEvent(a)"));
  }
  
  @Test
  public void startedEvent() {
    TypeBehavior.checkEqualsAndHashCode(Arrays.asList(
        () -> new StartedEvent()
        ));

    assertThat(new StartedEvent().toString(), equalTo("StartedEvent"));
  }
  
  @Test
  public void faultEvent() {
    StreamException inner = new StreamException("");
    assertThat(new FaultEvent(inner).getCause(), sameInstance(inner));
    
    TypeBehavior.checkEqualsAndHashCode(Arrays.asList(
        () -> new FaultEvent(new StreamException("a")),
        () -> new FaultEvent(new StreamException("b")),
        () -> new FaultEvent(null)
        ));

    assertThat(new FaultEvent(new StreamException("a")).toString(),
        equalTo("FaultEvent(" + new StreamException("a").toString() + ")"));
  }
  
  @Test
  public void setRetryDelayEvent() {
    TypeBehavior.checkEqualsAndHashCode(Arrays.asList(
        () -> new SetRetryDelayEvent(1000),
        () -> new SetRetryDelayEvent(1001)
        ));

    assertThat(new SetRetryDelayEvent(1000).toString(),
        equalTo("SetRetryDelayEvent(1000)"));
  }
}
