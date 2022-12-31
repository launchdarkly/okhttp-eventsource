#!/bin/bash

set -ue

# Publish to Sonatype
echo "Publishing to Sonatype"
if [[ -n "${LD_RELEASE_IS_PRERELEASE}" ]]; then
  ./gradlew publishToSonatype || { echo "Gradle publish/release failed" >&2; exit 1; }	
else
  ./gradlew publishToSonatype closeAndReleaseRepository || { echo "Gradle publish/release failed" >&2; exit 1; }
fi

