#!/bin/bash

set -ue

# Publish to Sonatype
echo "Publishing to Sonatype"
./gradlew publishToSonatype closeAndReleaseRepository || { echo "Gradle publish/release failed" >&2; exit 1; }
