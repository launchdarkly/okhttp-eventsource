package com.launchdarkly.eventsource;

@SuppressWarnings("javadoc")
public class TestUtils {
  private static int generatedStringCounter = 0;

  public static String makeStringOfLength(int n) {
    int offset = generatedStringCounter++;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append((char)('!' + (i + offset) % ('~' - '!' + 1)));
    }
    return sb.toString();
  }

  public static Thread interruptOnAnotherThreadAfterDelay(EventSource es, long delayMillis) {
    Thread t = new Thread(new Runnable() {
      public void run() {
        try {
          if (delayMillis > 0) {
            Thread.sleep(delayMillis);
          }
        } catch (InterruptedException e) {
        }
        es.interrupt();
      }
    });
    t.start();
    return t;
  }
  
  public static Thread interruptOnAnotherThread(EventSource es) {
    return interruptOnAnotherThreadAfterDelay(es, 0);
  }

  public static void interruptThisThreadFromAnotherThreadAfterDelay(long delayMillis) {
    final Thread t = Thread.currentThread();
    new Thread(new Runnable() {
      public void run() {
        try {
          if (delayMillis > 0) {
            Thread.sleep(delayMillis);
          }
        } catch (InterruptedException e) {}
        t.interrupt();
      }
    }).start();
  }
}
