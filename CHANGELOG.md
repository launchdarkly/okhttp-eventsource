# Change log

All notable changes to the LaunchDarkly EventSource implementation for Java will be documented in this file. This project adheres to [Semantic Versioning](http://semver.org).

## [1.10.2] - 2020-03-20
### Changed:
- Updated OkHttp version to 3.12.10 (the latest version that still supports Java 7).

## [1.10.1] - 2019-10-17
### Fixed:
- Fixed trailing period in logger name. ([#34](https://github.com/launchdarkly/okhttp-eventsource/issues/34))
- If you provide your own value for the `Accept` header using `EventSource.Builder.headers()`, it should not _also_ send the default `Accept: text/event-stream`, but replace it. ([#38](https://github.com/launchdarkly/okhttp-eventsource/issues/38))


## [1.10.0] - 2019-08-01
### Added:
- `EventSource.Builder.clientBuilderActions()` allows you to modify the OkHttp client options in any way, such as customizing TLS behavior or any other methods supported by `OkHttpClient.Builder`.
- The CI tests now include end-to-end tests against an embedded HTTP server.

## [1.9.1] - 2019-03-20
### Fixed:
- Removed JSR305 annotations such as `@Nullable`. JSR305 is unsupported and obsolete, and can cause [problems](https://blog.codefx.org/java/jsr-305-java-9/) in Java 9.

## [1.9.0] - 2018-12-12
### Added:
- The `requestTransformer` option allows you to customize the outgoing request in any way you like. ([#28](https://github.com/launchdarkly/okhttp-eventsource/issues/28))
- It is now possible to specify the endpoint as either a `URI` or an OkHttp `HttpUrl`. ([#29](https://github.com/launchdarkly/okhttp-eventsource/issues/29))
- The `backoffResetThresholdMs` option controls the new backoff behavior described below.
- Added Javadoc comments for all public members.

### Changed:
- The exponential backoff behavior when a stream connection fails has changed as follows. Previously, the backoff delay would increase for each attempt if the connection could not be made at all, or if a read timeout happened; but if a connection was made and then an error (other than a timeout) occurred, the delay would be reset to the minimum value. Now, the delay is only reset if a stream connection is made and remains open for at least `backoffResetThresholdMs` milliseconds. The default for this value is one minute (60000).

## [1.8.0] - 2018-04-04
### Added:
- Added `maxReconnectTimeMs(long)` method to `EventSource.Builder` to override the default maximum reconnect time of 30 seconds

## [1.7.1] - 2018-01-30
### Changed:
- Fixed EventSource logger name to match convention
- Ensure background threads are daemon threads
- Don't log IOExceptions when the stream is already being shut down

## [1.7.0] - 2018-01-07
### Added:
- Added the ability to connect to an SSE resource using any HTTP method (defaults to GET), and specify a request body.

## [1.6.0] - 2017-12-20
### Added:
- Add new handler interface for stopping connection retries after an error

### Fixed:
- Ensure that connections are closed completely ([#24](https://github.com/launchdarkly/okhttp-eventsource/pull/24))
- Ensure that we reconnect with backoff in case of a read timeout
