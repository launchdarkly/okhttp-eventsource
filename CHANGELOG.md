# Change log

All notable changes to the LaunchDarkly EventSource implementation for Java will be documented in this file. This project adheres to [Semantic Versioning](http://semver.org).

## [2.3.1] - 2020-06-18
### Fixed:
- Worker threads might not be shut down after closing the EventSource, if the stream had previously been stopped due to a ConnectionErrorHandler returning `SHUTDOWN`. Now, the threads are stopped as soon as the stream is shut down for any reason. ([#51](https://github.com/launchdarkly/okhttp-eventsource/issues/51))
- Fixed a race condition that could cause `onClosed()` not to be called in some circumstances where it should be called.

## [2.3.0] - 2020-06-02
### Added:
- `EventSource.Builder.logger()` and the `Logger` interface allow a custom logging implementation to be used instead of SLF4J.
- `EventSource.Builder.loggerBaseName()` allows customization of the logger name when using the default SLF4J implementation.
- Greatly improved unit test coverage; the CI build will now enforce coverage goals (see `CONTRIBUTING.md`).

### Fixed:
- Explicitly closing the stream could cause a misleading log line saying that the connection error handler had shut it down.
- Explicitly closing the stream could also cause an unnecessary backoff delay (with a log line about waiting X amount of time) before the stream task actually shut down.
- Fixed a bug that could cause the randomized jitter not to be applied to reconnection delays if the reconnect interval (in milliseconds) was a power of two.

## [2.2.0] - 2020-05-08
### Added:
- `EventSource.Builder.threadPriority()` specifies that `EventSource` should create its worker threads with a specific priority.

## [2.1.0] - 2020-04-29
### Added:
- `EventSource` method `restart()` (also added in 1.11.0).

### Changed:
- Updated OkHttp version to 4.5.0.

## [2.0.1] - 2020-01-31
### Fixed:
- A build problem caused the 2.0.0 release to have an incorrect `pom.xml` that did not list any dependencies.

## [2.0.0] - 2020-01-22
### Added:
- `EventSource.Builder.lastEventId` (replaces `EventSource.setLastEventId`).

### Changed:
- This library now uses OkHttp 4.x and requires Java 8 or above.
- Configuration methods that previously specified a duration in milliseconds now use the `Duration` class.

### Removed:
- In `EventSource`: `setHttpUrl`, `setLastEventId`, `setMaxReconnectTime`, `setReconnectionTime`, `setUri` (these can only be set in the builder).

## [1.11.1] - 2020-05-26
### Fixed:
- Fixed a bug that could cause the randomized jitter not to be applied to reconnection delays if the reconnect interval (in milliseconds) was a power of two.

## [1.11.0] - 2020-03-30
### Added:
- New `EventSource` method `restart()` allows the caller to force a stream connection retry even if no I/O error has happened, using the same backoff behavior that would be used for errors.

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
