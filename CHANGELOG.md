# Change log

All notable changes to the LaunchDarkly EventSource implementation for Java will be documented in this file. This project adheres to [Semantic Versioning](http://semver.org).

## [2.7.1] - 2022-08-23
### Changed:
- Changed jitter logic that used `java.util.Random` to use `java.security.SecureRandom`. Even though in this case it is not being used for any cryptographic purpose, but only to produce a pseudo-random delay, static analysis tools may still report every use of `java.util.Random` as a security risk by default. The purpose of this change is simply to avoid such warnings; it has no practical effect on the behavior of the library.

## [1.11.3] - 2022-08-22
### Changed:
- Changed jitter logic that used `java.util.Random` to use `java.security.SecureRandom`. Even though in this case it is not being used for any cryptographic purpose, but only to produce a pseudo-random delay, static analysis tools may still report every use of `java.util.Random` as a security risk by default. The purpose of this change is simply to avoid such warnings; it has no practical effect on the behavior of the library.

## [2.7.0] - 2022-08-02
The main purpose of this release is to introduce a new logging facade, [`com.launchdarkly.logging`](https://github.com/launchdarkly/java-logging), to streamline how logging works in LaunchDarkly Java and Android code. Previously, `okhttp-eventsource` used SLF4J for logging by default, but could be made to send output to a `Logger` interface of its own; the LaunchDarkly Java SDK used only SLF4J, so developers needed to provide an SLF4J configuration externally; and the LaunchDarkly Android SDK used Timber, but still brought in SLF4J as a transitive dependency of `okhttp-eventsource`. In this release, the default behavior is still to use SLF4J, but the logging facade can also be configured programmatically to do simple console logging without SLF4J, or to forward output to another framework such as `java.util.logging`, or other destinations. In a future major version release, the default behavior will be changed so that `okhttp-eventsource` does not require SLF4J as a dependency.

### Added:
- An overload of `EventSource.Builder.logger()` that takes a `com.launchdarkly.logging.LDLogger` instead of a `com.launchdarkly.eventsource.Logger`.

### Deprecated:
- The overload of `EventSource.Builder.logger()` that takes a `com.launchdarkly.eventsource.Logger`.
- `EventSource.Builder.loggerBaseName()`: this method was only relevant to the default behavior of using SLF4J, providing a way to customize what the logger name would be for SLF4J. But when using the new framework, the logger name is built into the `LDLogger` instance. For example (having imported the `com.launchdarkly.logging` package): `builder.logger(LDLogger.withAdapter(LDSLF4J.adapter(), "my.desired.logger.name"))`

## [2.6.2] - 2022-07-27
### Changed:
- Updated OkHttp dependency to v4.9.3 to get [recent fixes](https://square.github.io/okhttp/changelogs/changelog_4x/), including a security fix.

## [2.6.1] - 2022-06-29
### Fixed:
- The 2.5.0 and 2.6.0 releases mistakenly showed `kotlin-stdlib` as a compile-time dependency in `pom.xml`. While this library does use the Kotlin runtime (because the underlying OkHttp client uses Kotlin), that is a transitive dependency and is not needed at compile time.

## [2.6.0] - 2022-06-28
### Added:
- `EventSource.Builder.streamEventData` and `EventSource.Builder.expectFields`, for enabling a new event parsing mode in which event data can be consumed directly from the stream without holding it all in memory. This may be useful in applications where individual events are extremely large.
- `MessageEvent.getDataReader` and `MessageEvent.isStreamingData`, for use with the new mode described above.
- `MessageEvent.getEventName`, providing access to the event name from the event object; previously the event name was only available as a parameter of `EventHandler.onMessage`.

### Changed:
- Miscellaneous improvements in memory usage and performance when parsing the stream, even in the default mode.

## [2.5.0] - 2022-01-13
### Added:
- `EventSource.Builder.maxTasksInFlight` allows setting a limit on how many asynchronous event handler calls can be queued for dispatch at any given time. (Thanks, [thomaslee](https://github.com/launchdarkly/okhttp-eventsource/pull/58)!)
- `EventSource.awaitClosed` provides confirmation that all asynchronous event handler calls have been completed after stopping the `EventSource`.  (Thanks, [thomaslee](https://github.com/launchdarkly/okhttp-eventsource/pull/58)!)

### Changed:
- The build has been updated to use Gradle 7.
- The CI build now includes testing in Java 17.

## [2.4.0] - 2022-01-06
This release fixes a number of SSE spec compliance issues which do not affect usage in the LaunchDarkly Java and Android SDKs, but could be relevant in other use cases.

### Added:
- `EventSource.Builder.readBufferSize`

### Changed:
- The implementation of stream parsing has been changed. Previously, we were using `BufferedSource` from the `okio` library, but that API did not support `\r` line terminators (see below). Now we use our own implementation, which is simpler than `BufferedSource` and is optimized for reading text lines in UTF-8.
- The CI build now incorporates the cross-platform contract tests defined in https://github.com/launchdarkly/sse-contract-tests to ensure consistent test coverage across different LaunchDarkly SSE implementations.

### Fixed:
- The stream parser did not support a `\r` character by itself as a line terminator. The SSE specification requires that `\r`, `\n`, and `\r\n` are all valid.
- If an event's `id:` field contains a null character, the whole field should be ignored.
- The parser was incorrectly trimming spaces from lines that did not contain a colon, so for instance `data[space]` was being treated as an empty `data` field, when it is really an invalid field name that should be ignored.

## [2.3.2] - 2021-06-24
### Fixed:
- Fixed a bug that caused the connection error handler to be called twice instead of once, with only the second return value being used. The second call would always pass an `EOFException` instead of the original error. The result was that any connection error handler logic that needed to distinguish between different kinds of errors would not work as intended.

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

## [1.11.2] - 2020-09-28
### Fixed:
- Restored compatibility with Java 7. CI builds now verify that the library can be compiled and tested in Java 7 rather than just building with a target JVM setting of 1.7.
- Bumped OkHttp version to 3.12.12 to avoid a crash on Java 8u252.
- Explicitly closing the stream could also cause an unnecessary backoff delay (with a log line about waiting X amount of time) before the stream task actually shut down.

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
