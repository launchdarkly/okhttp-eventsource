package com.launchdarkly.eventsource;

/**
 * This interface provides access to HTTP response headers in a way that is independent
 * of any specific HTTP client implementation. Headers are represented as an ordered list
 * of name-value pairs, preserving the original structure including duplicate header names.
 * <p>
 * Implementations of this interface should be immutable and thread-safe.
 *
 * @since 4.2.0
 */
public interface ResponseHeaders {
  /**
   * Represents a single HTTP header as a name-value pair.
   * <p>
   * Header names are case-insensitive according to the HTTP specification, but the
   * original case is preserved in this representation.
   *
   * @since 4.2.0
   */
  public static final class Header {
    private final String name;
    private final String value;

    /**
     * Creates a header with the given name and value.
     *
     * @param name the header name
     * @param value the header value
     */
    public Header(String name, String value) {
      this.name = name;
      this.value = value;
    }

    /**
     * Returns the header name.
     *
     * @return the header name
     */
    public String getName() {
      return name;
    }

    /**
     * Returns the header value.
     *
     * @return the header value
     */
    public String getValue() {
      return value;
    }

    @Override
    public String toString() {
      return name + ": " + value;
    }
  }

  /**
   * Returns the number of headers in this collection.
   * <p>
   * Note that if a header name appears multiple times (e.g., multiple {@code Set-Cookie}
   * headers), each appearance is counted separately.
   *
   * @return the number of headers
   */
  int size();

  /**
   * Returns the header at the specified index.
   * <p>
   * Headers are indexed in the order they appeared in the HTTP response.
   *
   * @param index the index of the header to retrieve (0-based)
   * @return the header at the specified index
   * @throws IndexOutOfBoundsException if the index is out of range
   */
  Header get(int index);

  /**
   * Returns the value of the first header with the specified name (case-insensitive),
   * or null if no such header exists.
   * <p>
   * This is a convenience method for headers where you expect a single value. If the
   * header appears multiple times in the response, only the first occurrence is returned.
   * <p>
   * Note that the returned value may contain commas if:
   * <ul>
   * <li>The original HTTP response had a comma in the header value, or</li>
   * <li>The HTTP client combined multiple header lines into one (though this is
   *     uncommon with the underlying OkHttp implementation)</li>
   * </ul>
   * <p>
   * For headers that can legitimately appear multiple times (like {@code Set-Cookie}),
   * use {@link #size()} and {@link #get(int)} to iterate through all occurrences instead.
   * <p>
   * Example usage:
   * <pre>
   * String contentType = headers.value("Content-Type");
   * String retryAfter = headers.value("Retry-After");
   * </pre>
   *
   * @param name the header name (case-insensitive)
   * @return the value of the first matching header, or null if not found
   */
  String value(String name);

  /**
   * Returns true if this collection contains no headers.
   *
   * @return true if there are no headers
   */
  boolean isEmpty();
}
