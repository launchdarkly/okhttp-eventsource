package com.launchdarkly.eventsource;

import com.google.common.io.CharStreams;

import org.junit.Test;

import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

@SuppressWarnings("javadoc")
public class MessageEventTest {
  private static final URI ORIGIN_URI = URI.create("http://hostname");
  
  @Test
  public void constructorsAndSimpleProperties() {
    MessageEvent e1 = new MessageEvent("data", "id", ORIGIN_URI);
    assertThat(e1.getEventName(), equalTo("message"));
    assertThat(e1.getData(), equalTo("data"));
    assertThat(e1.getLastEventId(), equalTo("id"));
    assertThat(e1.getOrigin(), equalTo(ORIGIN_URI));
    
    MessageEvent e2 = new MessageEvent("data", null, ORIGIN_URI);
    assertThat(e2.getEventName(), equalTo("message"));
    assertThat(e2.getData(), equalTo("data"));
    assertThat(e2.getLastEventId(), nullValue());
    assertThat(e2.getOrigin(), equalTo(ORIGIN_URI));
    
    MessageEvent e3 = new MessageEvent("data");
    assertThat(e3.getEventName(), equalTo("message"));
    assertThat(e3.getData(), equalTo("data"));
    assertThat(e3.getLastEventId(), nullValue());
    assertThat(e3.getOrigin(), nullValue());
    
    MessageEvent e4 = new MessageEvent("put", "data", "id", ORIGIN_URI);
    assertThat(e4.getEventName(), equalTo("put"));
    assertThat(e4.getData(), equalTo("data"));
    assertThat(e4.getLastEventId(), equalTo("id"));
    assertThat(e4.getOrigin(), equalTo(ORIGIN_URI));
    
    MessageEvent e5 = new MessageEvent("put", "data");
    assertThat(e5.getEventName(), equalTo("put"));
    assertThat(e5.getData(), equalTo("data"));
    assertThat(e5.getLastEventId(), nullValue());
    assertThat(e5.getOrigin(), nullValue());
  }
  
  @Test
  public void streamingDataBehavior() throws Exception{
    String data1 = "lazily-computed-data-1";
    MessageEvent e1 = new MessageEvent(
        null,
        new StringReader(data1),
        null,
        null);
    assertThat(e1.getData(), equalTo(data1));
    assertThat(e1.getData(), equalTo(data1)); // should get same answer each time
    Reader r1 = e1.getDataReader(); // if you call getDataReader() after getData(), it should reuse the buffered string
    assertThat(CharStreams.toString(r1), equalTo(data1));
    assertThat(e1.getDataReader(), sameInstance(r1));
    
    MessageEvent e2 = new MessageEvent(
        null,
        new StringReader(data1),
        null,
        null);
    Reader r2 = e2.getDataReader();
    assertThat(CharStreams.toString(r2), equalTo(data1));
    assertThat(e2.getDataReader(), sameInstance(r2));
    assertThat(e2.getData(), equalTo(""));
    
    MessageEvent e3 = new MessageEvent(
        null,
        data1,
        null,
        null);
    Reader r3 = e3.getDataReader();
    assertThat(CharStreams.toString(r3), equalTo(data1));
    assertThat(e3.getDataReader(), sameInstance(r3));
    assertThat(e3.getData(), equalTo(data1));
  }
  
  @Test
  public void equalValuesAreEqual()
  {
    List<MessageEvent> values = new ArrayList<>();
    
    for (String name: new String[] { null, "event1", "event2" }) {
      for (String data: new String[] { null, "data1", "data2" }) {
        for (String id: new String[] { null, "id1", "id2" }) {
          for (URI origin: new URI[] { null, ORIGIN_URI, URI.create("http://other") }) {
            MessageEvent e1 = new MessageEvent(name, data, id, origin);
            MessageEvent e2 = new MessageEvent(name, data, id, origin);
            assertThat(e1, equalTo(e1));
            assertThat(e1, equalTo(e2));
            assertThat(e2, equalTo(e1));
            assertThat(e1.hashCode(), equalTo(e2.hashCode()));
            values.add(e1);
          }
        }
      }
    }
    
    for (int i = 0; i < values.size(); i++) {
      for (int j = 0; j < values.size(); j++) {
        if (i != j) {
          MessageEvent e1 = values.get(i);
          MessageEvent e2 = values.get(j);
          assertThat(e1, not(equalTo(e2)));
          assertThat(e2, not(equalTo(e1)));
        }
      }
    }

    MessageEvent e = new MessageEvent("data", "id", ORIGIN_URI);
    assertThat(e, not(equalTo(null)));
    assertThat(e, not(equalTo("x")));
  }
  
  @Test
  public void simpleStringRepresentation() {
    assertThat(new MessageEvent("put", "data", "id", ORIGIN_URI).toString(),
        equalTo("MessageEvent(eventName=put,data=data,id=id,origin=http://hostname)"));
    assertThat(new MessageEvent("data", "id", ORIGIN_URI).toString(),
        equalTo("MessageEvent(eventName=message,data=data,id=id,origin=http://hostname)"));
    assertThat(new MessageEvent(null, "id", ORIGIN_URI).toString(),
        equalTo("MessageEvent(eventName=message,data=,id=id,origin=http://hostname)"));
    assertThat(new MessageEvent("data", null, ORIGIN_URI).toString(),
        equalTo("MessageEvent(eventName=message,data=data,origin=http://hostname)"));
    assertThat(new MessageEvent("data", "id", null).toString(),
        equalTo("MessageEvent(eventName=message,data=data,id=id,origin=null)"));
  }
}
