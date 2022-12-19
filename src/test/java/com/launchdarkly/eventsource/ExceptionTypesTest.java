package com.launchdarkly.eventsource;

import com.launchdarkly.testhelpers.TypeBehavior;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

@SuppressWarnings("javadoc")
public class ExceptionTypesTest {
  @Test
  public void streamException() {
    Exception inner1 = new Exception("inner1"), inner2 = new Exception("inner2");
    
    TypeBehavior.checkEqualsAndHashCode(Arrays.asList(
        () -> new StreamException("a"),
        () -> new StreamException("b"),
        () -> new StreamException((String)null),
        () -> new StreamException(inner1),
        () -> new StreamException(inner2)
        ));
  }
  
  @Test
  public void streamIOException() {
    IOException inner1 = new IOException("inner1"), inner2 = new IOException("inner2");
    
    TypeBehavior.checkEqualsAndHashCode(Arrays.asList(
        () -> new StreamIOException(inner1),
        () -> new StreamIOException(inner2),
        () -> new StreamIOException(null)
        ));
  }

  @Test
  public void streamHttpErrorException() {
    TypeBehavior.checkEqualsAndHashCode(Arrays.asList(
        () -> new StreamHttpErrorException(400),
        () -> new StreamHttpErrorException(401)
        ));
  }
  
  @Test
  public void streamClosedByCallerException() {
    TypeBehavior.checkEqualsAndHashCode(Arrays.asList(
        () -> new StreamClosedByCallerException()
        ));
  }
  
  @Test
  public void streamClosedByServerException() {
    TypeBehavior.checkEqualsAndHashCode(Arrays.asList(
        () -> new StreamClosedByServerException()
        ));
  }
}
