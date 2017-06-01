#!/usr/bin/env bash
# This script updates the version for the okhttp-eventsource library and releases the artifact + javadoc
# It will only work if you have the proper credentials set up in ~/.gradle/gradle.properties

# It takes exactly one argument: the new version.
# It should be run from the root of this git repo like this:
#   ./scripts/release.sh 4.0.9

# When done you should commit and push the changes made.

set -uxe
echo "Starting okhttp-eventsource release."

VERSION=$1

#Update version in gradle.properties file:
sed  -i '' "s/^version.*$/version=${VERSION}/" gradle.properties

./gradlew clean test install sourcesJar javadocJar uploadArchives closeAndReleaseRepository
echo "Finished okhttp-eventsource release."
