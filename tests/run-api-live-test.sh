#!/usr/bin/env sh
# Live test against the DEV worker (creates a throwaway account; delete it from minefeed-dev-db afterwards).
set -eu
cd "$(dirname "$0")/.."
gson=${GSON_JAR:-$(find "$HOME/.gradle" -name 'gson-2.1*.jar' | head -1)}
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -cp "$gson" -d "$classes" src/main/java/com/feed/client/account/AccountApi.java \
  src/main/java/com/feed/client/account/ApiError.java src/main/java/com/feed/client/account/AccountStore.java \
  tests/AccountApiLiveTest.java
java -cp "$classes:$gson" AccountApiLiveTest "$@"
