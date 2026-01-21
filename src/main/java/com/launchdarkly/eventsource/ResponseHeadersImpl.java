package com.launchdarkly.eventsource;

import okhttp3.Headers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Package-private implementation of ResponseHeaders.
 * <p>
 * This class is immutable and thread-safe.
 */
final class ResponseHeadersImpl implements ResponseHeaders {
  private static final ResponseHeaders EMPTY = new ResponseHeadersImpl(Collections.emptyList());

  private final List<Header> headers;

  private ResponseHeadersImpl(List<Header> headers) {
    this.headers = headers;
  }

  /**
   * Creates a ResponseHeaders instance from OkHttp Headers.
   * <p>
   * This preserves all headers in the order they appear, including duplicate header names.
   *
   * @param headers the OkHttp headers
   * @return a ResponseHeaders instance
   */
  static ResponseHeaders fromOkHttpHeaders(Headers headers) {
    if (headers == null || headers.size() == 0) {
      return EMPTY;
    }

    List<Header> headerList = new ArrayList<>(headers.size());
    for (int i = 0; i < headers.size(); i++) {
      headerList.add(new Header(headers.name(i), headers.value(i)));
    }

    return new ResponseHeadersImpl(Collections.unmodifiableList(headerList));
  }

  /**
   * Returns an empty ResponseHeaders instance.
   *
   * @return an empty ResponseHeaders
   */
  static ResponseHeaders empty() {
    return EMPTY;
  }

  @Override
  public int size() {
    return headers.size();
  }

  @Override
  public Header get(int index) {
    return headers.get(index);
  }

  @Override
  public String value(String name) {
    if (name == null) {
      return null;
    }
    for (Header header : headers) {
      if (header.getName().equalsIgnoreCase(name)) {
        return header.getValue();
      }
    }
    return null;
  }

  @Override
  public boolean isEmpty() {
    return headers.isEmpty();
  }

  @Override
  public String toString() {
    return "ResponseHeaders(" + headers.size() + " headers)";
  }
}
