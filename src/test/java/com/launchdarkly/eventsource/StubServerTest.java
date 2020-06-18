package com.launchdarkly.eventsource;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.eventsource.StubServer.Handlers.forRequestsInSequence;
import static com.launchdarkly.eventsource.StubServer.Handlers.ioError;
import static com.launchdarkly.eventsource.StubServer.Handlers.returnStatus;
import static org.junit.Assert.assertEquals;

import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * These are not tests of EventSource; they just verify that the embedded HTTP server helper we're using
 * behaves as it should, since if it doesn't, the results of the EventSource tests are undefined.
 */
@SuppressWarnings("javadoc")
public class StubServerTest {
  private final OkHttpClient client;
  
  public StubServerTest() {
    client = new OkHttpClient.Builder()
        .connectionPool(new ConnectionPool(1, 1, TimeUnit.SECONDS))
        .retryOnConnectionFailure(false)
        .build();
  }
  
  private Call makeGet(StubServer server) throws Exception {
    return client.newCall(new Request.Builder().method("GET", null).url(server.getUri().toURL()).build());
  }
  
  @Test
  public void returnStatusHandler() throws Exception {
    try (StubServer server = StubServer.start(returnStatus(418))) {
      Response resp = makeGet(server).execute();
      assertEquals(418, resp.code());
    }
  }
  
  @Test(expected=IOException.class)
  public void ioErrorHandler() throws Exception {
    try (StubServer server = StubServer.start(ioError())) {
      makeGet(server).execute();
    }
  }
  
  @Test
  public void forRequestsInSequenceHandler() throws Exception {
    StubServer.Handler handler = forRequestsInSequence(returnStatus(418), returnStatus(503));
    try (StubServer server = StubServer.start(handler)) {
      Response resp1 = makeGet(server).execute();
      assertEquals(418, resp1.code());

      Response resp2 = makeGet(server).execute();
      assertEquals(503, resp2.code());
    }
  }
}
