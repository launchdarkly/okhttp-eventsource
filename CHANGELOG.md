# Change log

All notable changes to the LaunchDarkly EventSource implementation for Java will be documented in this file. This project adheres to [Semantic Versioning](http://semver.org).

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
