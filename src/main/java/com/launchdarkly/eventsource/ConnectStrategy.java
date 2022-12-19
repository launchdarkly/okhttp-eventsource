package com.launchdarkly.eventsource;

import com.launchdarkly.logging.LDLogger;

import java.io.Closeable;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import okhttp3.HttpUrl;

/**
 * An abstraction of how {@link EventSource} should obtain an input stream.
 * <p>
 * The default implementation is {@link HttpConnectStrategy}, which makes HTTP
 * requests. To customize the HTTP behavior, you can use methods of
 * {@link HttpConnectStrategy}:
 * <pre><code>
 *     EventSource.Builder builder = new EventSource.Builder(
 *       ConnectStrategy.http(myStreamUri)
 *         .headers(myCustomHeaders)
 *         .connectTimeout(10, TimeUnit.SECONDS)
 *     );
 * </code></pre>
 * <p>
 * Or, if you want to consume an input stream from some other source, you can
 * create your own subclass of {@link ConnectStrategy}.
 * <p>
 * Instances of this class should be immutable and not contain any state that
 * is specific to one active stream. The {@link ConnectStrategy.Client} that
 * they produce is stateful and belongs to a single EventSource.  
 *
 * @since 4.0.0
 */
public abstract class ConnectStrategy {
  /**
   * Creates a client instance.
   * <p>
   * This is called once when an EventSource is created. The EventSource
   * retains the returned Client and uses it to perform all subsequent
   * connection attempts.
   *
   * @param logger the logger belonging to EventSource
   * @return a {@link Client} instance
   */
  public abstract Client createClient(LDLogger logger);
  
  /**
   * An object provided by {@link ConnectStrategy} that is retained by a
   * single {@link EventSource} instance to perform all connection attempts
   * by that instance.
   * <p>
   * For the default HTTP implementation, this represents a configured HTTP
   * client.
   * <p>
   * The {@link #close()} method should be implemented to do any necessary
   * cleanup when an EventSource instance is being permanently disposed of.
   */
  public static abstract class Client implements Closeable {
    /**
     * The return type of {@link ConnectStrategy.Client#connect(String)}.
     */
    public static class Result {
      private final InputStream inputStream;
      private final URI origin;
      private final Closeable closer;
      
      /**
       * Creates an instance.
       * 
       * @param inputStream see {@link #getInputStream()}
       * @param origin see {@link #getOrigin()}
       * @param closer see {@link #getCloser()}
       */
      public Result(InputStream inputStream, URI origin, Closeable closer) {
        this.inputStream = inputStream;
        this.origin = origin;
        this.closer = closer;
      }
      
      /**
       * The input stream that {@link EventSource} should read from.
       *
       * @return the input stream (must not be null)
       */
      public InputStream getInputStream() {
        return inputStream;
      }
  
      /**
       * The origin URI that should be included in every {@link MessageEvent}.
       * <p>
       * The SSE specification dictates that every message should have an origin
       * property representing the stream it came from. In the default HTTP
       * implementation, this is simply the stream URI; other implementations of
       * {@link ConnectStrategy} can set it to whatever they want.
       * <p>
       * If this value is null, it defaults to the original URI that was returned
       * by {@link Client#getOrigin()} prior to making the connection attempt.
       * This allows clients to modify the origin if necessary for each attempt,
       * for instance, if using different query parameters.
       *
       * @return the stream URI
       */
      public URI getOrigin() {
        return origin;
      }
      
      /**
       * An object that {@link EventSource} can use to close the connection.
       * If this is not null, its {@link Closeable#close()} method will be
       * called whenever the current connection is stopped either due to an
       * error or because the caller explicitly closed the stream.
       *
       * @return a Closeable object or null
       */
      public Closeable getCloser() {
        return closer;
      }
    }
    
    /**
     * Attempts to connect to a stream.
     * 
     * @param lastEventId the current value of {@link EventSource#getLastEventId()}
     *   (should be sent to the server to support resuming an interrupted stream)
     * @return the result if successful
     * @throws StreamException if not successful
     */
    public abstract Result connect(String lastEventId) throws StreamException;
    
    /**
     * Implements {@link EventSource#awaitClosed(long, java.util.concurrent.TimeUnit)}.
     * 
     * @param timeoutMillis maximum amount of time to wait
     * @return true if all requests are now closed
     * @throws InterruptedException if the thread is interrupted
     */
    public abstract boolean awaitClosed(long timeoutMillis) throws InterruptedException;
    
    /**
     * Returns the expected URI of the stream.
     * <p>
     * This value is returned by {@link EventSource#getOrigin()}. It can be overridden
     * on a per-connection basis with {@link Result#getOrigin()}.
     *
     * @return the stream URI
     */
    public abstract URI getOrigin();
  }
  
  /**
   * Returns the default HTTP implementation, specifying a stream URI.
   * <p>
   * To specify custom HTTP behavior, call {@link HttpConnectStrategy} methods on the
   * returned object to obtain a modified instance:
   * <pre><code>
   *     EventSource.Builder builder = new EventSource.Builder(
   *       ConnectStrategy.http(myStreamUri)
   *         .headers(myCustomHeaders)
   *         .connectTimeout(10, TimeUnit.SECONDS)
   *     );
   * </code></pre>
   *
   * @param uri the stream URI
   * @return a configurable {@link HttpConnectStrategy}
   * @throws IllegalArgumentException if the argument is null, or if the scheme
   *   is not HTTP or HTTPS
   * @see #http(HttpUrl)
   */
  public static HttpConnectStrategy http(URI uri) {
    return new HttpConnectStrategy(uri);
  }

  /**
   * Returns the default HTTP implementation, specifying a stream URL.
   * <p>
   * This is the same as {@link #http(URI)}, but uses the {@link URL} type.
   *
   * @param url the stream URL
   * @return a configurable {@link HttpConnectStrategy}
   * @throws IllegalArgumentException if the argument is null, or if the scheme
   *   is not HTTP or HTTPS
   * @see #http(HttpUrl)
   */
  public static HttpConnectStrategy http(URL url) {
    try {
      return new HttpConnectStrategy(url == null ? null : url.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }
  }
  
  /**
   * Returns the default HTTP implementation, specifying a stream URI.
   * <p>
   * This is the same as {@link #http(URI)}, but uses the okhttp type
   * {@link HttpUrl}.
   *
   * @param url the stream URL
   * @return a configurable {@link HttpConnectStrategy}
   * @throws IllegalArgumentException if the argument is null, or if the scheme
   *   is not HTTP or HTTPS
   * @see #http(URI)
   */
  public static HttpConnectStrategy http(HttpUrl url) {
    return new HttpConnectStrategy(url == null ? null : url.uri());
  }
}
