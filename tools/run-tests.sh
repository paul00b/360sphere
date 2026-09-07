#!/usr/bin/env bash
# Tests unitaires JVM (logique pure : grille de capture, géométrie équirectangulaire, modèle JSON)
# sans Gradle : kotlinc + JUnit 4.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$HOME/.sphere360-toolchain}"
KOTLINC="$TOOLCHAIN_DIR/kotlinc/bin/kotlinc"
LIBS="$TOOLCHAIN_DIR/libs"
OUT="$ROOT/build/tests"
rm -rf "$OUT" && mkdir -p "$OUT"
# Fichiers de logique pure (aucune dépendance Android ni OpenCV)
J="$ROOT/app/src/main/java/care/primary/sphere360"
SRC="$J/capture/SphereMath.kt $J/capture/CaptureGrid.kt $J/stitch/EquirectGeometry.kt $J/stitch/EquirectFill.kt $J/data/Models.kt"
CP="$LIBS/json.jar:$LIBS/junit.jar:$LIBS/hamcrest-core.jar"
"$KOTLINC" -classpath "$CP" -d "$OUT/classes" $SRC $(find "$ROOT/app/src/test/java" -name '*.kt') 2>&1 | grep -v "^warning: " || true
TESTS=$(cd "$OUT/classes" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | tr '\n' ' ')
java -cp "$OUT/classes:$CP:$TOOLCHAIN_DIR/kotlinc/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore $TESTS
