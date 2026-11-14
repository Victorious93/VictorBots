#!/usr/bin/env bash
# Test-only dependencies (never packaged in the APK). Versions and SHA-256 are pinned.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/libs
get() { # file url sha256
  if [ -f "build/libs/$1" ] && echo "$3  build/libs/$1" | sha256sum -c - >/dev/null 2>&1; then return; fi
  curl -sS -fL --retry 3 -o "build/libs/$1" "$2"
  echo "$3  build/libs/$1" | sha256sum -c - >/dev/null || { echo "checksum mismatch for $1" >&2; rm -f "build/libs/$1"; exit 1; }
}
M=https://repo1.maven.org/maven2
get json-20240303.jar "$M/org/json/json/20240303/json-20240303.jar" 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed
get sqlite-jdbc-3.46.1.3.jar "$M/org/xerial/sqlite-jdbc/3.46.1.3/sqlite-jdbc-3.46.1.3.jar" 4a4832720a65eaf7f4d6fd7ede52087b994dc5633c076f9e994dc0c8b4b0b4fa
get slf4j-api-1.7.36.jar "$M/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar" d3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0
