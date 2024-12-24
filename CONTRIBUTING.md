# Contributing to this project

## Submitting bug reports and feature requests

The LaunchDarkly SDK team monitors the [issue tracker](https://github.com/launchdarkly/okhttp-eventsource/issues) in the GitHub repository. Bug reports and feature requests specific to this project should be filed in this issue tracker. The SDK team will respond to all newly filed issues within two business days.

## Submitting pull requests

We encourage pull requests and other contributions from the community. Before submitting pull requests, ensure that all temporary or unintended code is removed. Don't worry about adding reviewers to the pull request; the LaunchDarkly SDK team will add themselves. The SDK team will acknowledge all pull requests within two business days.

## Build instructions
 
### Prerequisites
 
The project builds with [Gradle](https://gradle.org/) and should be built against Java 8.
 
### Building

To build the project without running any tests:
```
make
```

If you wish to clean your working directory between builds, you can clean it by running:
```
make clean
```

If you wish to use your generated SDK artifact by another Maven/Gradle project such as [java-server-sdk](https://github.com/launchdarkly/java-server-sdk), you will likely want to publish the artifact to your local Maven repository so that your other project can access it.
```
./gradlew publishToMavenLocal
```

### Testing
 
To build the project and run all unit tests:
```
make test
```

To run the standardized contract tests that are run against all LaunchDarkly SSE client implementations:
```
make contract-tests
```

## Note on Java version and Android support

This version of the library is used by the LaunchDarkly server-side Java SDK, which has a minimum Java version of 8. The LaunchDarkly Android SDK does not fully support Java 8, and currently uses the older 1.x branch of `okhttp-eventsource`.

## Code coverage

It is important to keep unit test coverage as close to 100% as possible in this project.

You can view the latest code coverage report through GitHub actions. You can also run the report locally with `./gradlew jacocoTestCoverage` and view `./build/reports/jacoco/test`.

Sometimes a gap in coverage is unavoidable, usually because the compiler requires us to provide a code path for some condition that in practice can't happen and can't be tested, or because of a known issue with the code coverage tool. Please handle all such cases as follows:

* Mark the code with an explanatory comment beginning with "COVERAGE:".
* Run the code coverage task with `./gradlew jacocoTestCoverageVerification`. It should fail and indicate how many lines of missed coverage exist in the method you modified.
* Add an item in the `knownMissedLinesForMethods` map in `build.gradle` that specifies that number of missed lines for that method signature.
