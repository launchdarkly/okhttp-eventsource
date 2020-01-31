#!/bin/bash

set -ue

# Publish to Github Pages
echo "Publishing to Github Pages"
./gradlew gitPublishPush
