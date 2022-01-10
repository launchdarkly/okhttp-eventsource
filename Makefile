
build:
	./gradlew jar

clean:
	./gradlew clean

test:
	./gradlew test

TEMP_TEST_OUTPUT=/tmp/sse-contract-test-service.log

build-contract-tests:
	@cd contract-tests && ../gradlew installDist

start-contract-test-service:
	@contract-tests/service/build/install/service/bin/service

start-contract-test-service-bg:
	@echo "Test service output will be captured in $(TEMP_TEST_OUTPUT)"
	@make start-contract-test-service >$(TEMP_TEST_OUTPUT) 2>&1 &

run-contract-tests:
	@curl -s https://raw.githubusercontent.com/launchdarkly/sse-contract-tests/v2.0.0/downloader/run.sh \
      | VERSION=v2 PARAMS="-url http://localhost:8000 -debug -stop-service-at-end" sh

contract-tests: build-contract-tests start-contract-test-service-bg run-contract-tests

.PHONY: build clean test build-contract-tests start-contract-test-service run-contract-tests contract-tests
