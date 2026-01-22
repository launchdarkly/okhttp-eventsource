#!/bin/bash

set -ue

if $LD_RELEASE_IS_DRYRUN ; then
  echo "Doing a dry run of publishing."
else
    echo "Publishing to Sonatype"
    if [ "${LD_RELEASE_IS_PRERELEASE}" == "true" ]; then
        echo "PRERELEASE"
        ./gradlew publishToSonatype -Psigning.keyId="${SIGNING_KEY_ID}" -Psigning.password="${SIGNING_KEY_PASSPHRASE}" -Psigning.secretKeyRingFile="${SIGNING_SECRET_KEY_RING_FILE}" -PsonatypeUsername="${SONATYPE_USER_NAME}" -PsonatypePassword="${SONATYPE_PASSWORD}" || {
            echo "Gradle publish/release failed" >&2
            exit 1
        }
    else
        echo "RELEASE"
        ./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository -Psigning.keyId="${SIGNING_KEY_ID}" -Psigning.password="${SIGNING_KEY_PASSPHRASE}" -Psigning.secretKeyRingFile="${SIGNING_SECRET_KEY_RING_FILE}" -PsonatypeUsername="${SONATYPE_USER_NAME}" -PsonatypePassword="${SONATYPE_PASSWORD}" || {
            echo "Gradle publish/release failed" >&2
            exit 1
        }
    fi
fi
