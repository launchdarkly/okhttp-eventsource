package com.launchdarkly.eventsource;

import org.junit.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@SuppressWarnings("javadoc")
public class MessageEventTest {
  private static final URI ORIGIN_URI = URI.create("http://hostname");
  
  @Test
  public void constructorsAndSimpleProperties() {
    MessageEvent e1 = new MessageEvent("data", "id", ORIGIN_URI);
    assertThat(e1.getData(), equalTo("data"));
    assertThat(e1.getLastEventId(), equalTo("id"));
    assertThat(e1.getOrigin(), equalTo(ORIGIN_URI));
    
    MessageEvent e2 = new MessageEvent("data", null, ORIGIN_URI);
    assertThat(e2.getData(), equalTo("data"));
    assertThat(e2.getLastEventId(), nullValue());
    assertThat(e2.getOrigin(), equalTo(ORIGIN_URI));
    
    MessageEvent e3 = new MessageEvent("data");
    assertThat(e3.getData(), equalTo("data"));
    assertThat(e3.getLastEventId(), nullValue());
    assertThat(e3.getOrigin(), nullValue());
  }
  
  @Test
  public void equalValuesAreEqual()
  {
    List<MessageEvent> values = new ArrayList<>();
    
    for (String data: new String[] { null, "data1", "data2" }) {
      for (String id: new String[] { null, "id1", "id2" }) {
        for (URI origin: new URI[] { null, ORIGIN_URI, URI.create("http://other") }) {
          MessageEvent e1 = new MessageEvent(data, id, origin);
          MessageEvent e2 = new MessageEvent(data, id, origin);
          assertThat(e1, equalTo(e1));
          assertThat(e1, equalTo(e2));
          assertThat(e2, equalTo(e1));
          assertThat(e1.hashCode(), equalTo(e2.hashCode()));
          values.add(e1);
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
    assertThat(new MessageEvent("data", "id", ORIGIN_URI).toString(),
        equalTo("MessageEvent(data=data,id=id,origin=http://hostname)"));
    assertThat(new MessageEvent(null, "id", ORIGIN_URI).toString(),
        equalTo("MessageEvent(data=null,id=id,origin=http://hostname)"));
    assertThat(new MessageEvent("data", null, ORIGIN_URI).toString(),
        equalTo("MessageEvent(data=data,id=null,origin=http://hostname)"));
    assertThat(new MessageEvent("data", "id", null).toString(),
        equalTo("MessageEvent(data=data,id=id,origin=null)"));
  }
}
