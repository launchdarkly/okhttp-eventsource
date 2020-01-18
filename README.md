# okhttp-eventsource
Java EventSource implementation based on OkHttp

[![Circle CI](https://circleci.com/gh/launchdarkly/okhttp-eventsource.svg?style=shield)](https://circleci.com/gh/launchdarkly/okhttp-eventsource)
[![Javadocs](http://javadoc.io/badge/com.launchdarkly/okhttp-eventsource.svg)](http://javadoc.io/doc/com.launchdarkly/okhttp-eventsource)

Project Information
-----------

This library allows Java developers to consume Server Sent Events from a remote API. The server sent events spec is defined here: [https://html.spec.whatwg.org/multipage/server-sent-events.html](https://html.spec.whatwg.org/multipage/server-sent-events.html#server-sent-events)

Starting in version 2.0, this library uses OkHttp 4.x and requires Java 8+. If you need support for OkHttp 3.x or Java 7, use the latest 1.x version.
