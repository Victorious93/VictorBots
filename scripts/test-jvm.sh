#!/usr/bin/env bash
# Pure-Java checks: compiles every source that does not use android.* plus tests/jvm/*.java, then
# runs each test class. Uses real SQLite (sqlite-jdbc) and org.json from build/libs (see fetch-test-libs.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/fetch-test-libs.sh
CP="build/libs/json-20240303.jar:build/libs/sqlite-jdbc-3.46.1.3.jar:build/libs/slf4j-api-1.7.36.jar"
rm -rf build/jvm && mkdir -p build/jvm
PURE=$(grep -L -E '(^import android\.|\bandroid\.[a-z]+\.)' app/src/main/java/com/victorious/bots/*.java)
javac -nowarn -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp "$CP" -d build/jvm $PURE tests/jvm/*.java 2>&1 | grep -v '^Picked up' || true
rc=0
for t in ${@:-$(ls tests/jvm/*Test.java | xargs -n1 basename | sed 's/\.java$//')}; do
  echo "=== $t"
  java -cp "build/jvm:$CP" "$t" 2>&1 | grep -v '^Picked up' || rc=1
done
exit $rc
