#!/usr/bin/env bash
# Runs the tests for the Minecraft-free combat rules (common/.../core). Needs only a JDK 21+.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d "$OUT" \
    $(find common/src/main/java/io/github/mojolowjo/crossblades/core -name '*.java') \
    $(find common/src/test/java -name '*.java')
java -cp "$OUT" io.github.mojolowjo.crossblades.core.CoreTests
