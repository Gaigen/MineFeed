#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" src/main/java/com/feed/client/MessageTemplate.java src/main/java/com/feed/client/FeedChatActions.java src/main/java/com/feed/client/NotificationOrder.java tests/MessageTemplateTest.java tests/FeedChatActionsTest.java tests/NotificationOrderTest.java src/main/java/com/feed/client/account/TagRules.java src/main/java/com/feed/client/account/Versions.java tests/AccountRulesTest.java src/main/java/com/feed/client/screen/TagText.java tests/TagTextTest.java
java -cp "$classes" MessageTemplateTest
java -cp "$classes" FeedChatActionsTest

java -cp "$classes" NotificationOrderTest
java -cp "$classes" AccountRulesTest
java -cp "$classes" TagTextTest
