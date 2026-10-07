#!/usr/bin/env bash
# Compiles and runs the :domain unit tests WITHOUT Maven access, using the Kotlin compiler, kotlinx-serialization and
# JUnit jars that ship inside a Gradle distribution. Handy in sandboxes; CI uses plain `gradle :domain:test` instead.
# Usage: GRADLE_LIB=/opt/gradle-8.14.3/lib scripts/domain-test-offline.sh
set -euo pipefail
L="${GRADLE_LIB:-/opt/gradle-8.14.3/lib}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT_DIR:-$(mktemp -d)}"
K=2.0.21
KC="$L/kotlin-compiler-embeddable-$K.jar:$L/kotlin-stdlib-$K.jar:$L/kotlin-script-runtime-$K.jar:$L/kotlin-reflect-$K.jar:$L/kotlin-daemon-embeddable-$K.jar:$L/trove4j-1.0.20200330.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar:$L/annotations-24.0.1.jar"
CP="$L/kotlin-stdlib-$K.jar:$L/kotlinx-serialization-core-jvm-1.6.2.jar:$L/kotlinx-serialization-json-jvm-1.6.2.jar"
JU="$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar"
mkdir -p "$OUT/main" "$OUT/test"
kotlinc() { java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect "$@" 2>&1 | grep -v '^warning' || true; }
kotlinc -cp "$CP" -d "$OUT/main" "$ROOT/domain/src/main/kotlin"
cp -r "$ROOT"/domain/src/main/resources/* "$OUT/main/"
kotlinc -cp "$CP:$OUT/main:$JU" -d "$OUT/test" "$ROOT/domain/src/test/kotlin"
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's|^\./||;s|\.class$||;s|/|.|g')
java -cp "$CP:$OUT/main:$OUT/test:$JU" org.junit.runner.JUnitCore $CLASSES
