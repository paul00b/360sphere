#!/usr/bin/env bash
# Compile et lance le banc de test desktop du stitching (natives OpenCV Linux via JavaCPP).
# Usage : tools/desktop/run-harness.sh <dossier_session> <dossier_sortie> [--gc] [--relaxed]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$HOME/.sphere360-toolchain}"
LINUX_DIR="${LINUX_DIR:-$TOOLCHAIN_DIR/linux}"
KOTLINC="$TOOLCHAIN_DIR/kotlinc/bin/kotlinc"
OUT="$ROOT/build/harness"

[ -d "$LINUX_DIR" ] || { echo "Natives Linux manquantes : lance tools/desktop/setup-harness.sh"; exit 1; }
CP="$TOOLCHAIN_DIR/android-34.jar:$TOOLCHAIN_DIR/libs/javacpp.jar:$TOOLCHAIN_DIR/libs/opencv.jar:$TOOLCHAIN_DIR/libs/json.jar"
RUNCP="$TOOLCHAIN_DIR/libs/javacpp.jar:$TOOLCHAIN_DIR/libs/opencv.jar:$TOOLCHAIN_DIR/libs/json.jar:$TOOLCHAIN_DIR/android-34.jar:$TOOLCHAIN_DIR/kotlinc/lib/kotlin-stdlib.jar"

# Natives extraites une fois : on les charge explicitement (comme l'APK) au lieu de laisser
# JavaCPP tirer tout OpenCV, y compris highgui qui réclame GTK.
NAT="$OUT/natives"
if [ ! -d "$NAT" ]; then
  mkdir -p "$NAT"
  for j in "$LINUX_DIR"/*.jar; do unzip -q -o -j "$j" '*.so*' -d "$NAT" 2>/dev/null || true; done
  rm -f "$NAT"/cv2*.so "$NAT"/libjniopencv_highgui.so "$NAT"/libopencv_highgui.so*
fi

J="$ROOT/app/src/main/java/care/primary/sphere360"
SRC="$J/stitch/SphereStitcher.kt $J/stitch/OpenCvRuntime.kt $J/stitch/PanoGeometry.kt $J/stitch/ShotView.kt $J/stitch/LensDistortion.kt $J/stitch/StitchOptions.kt $J/stitch/EquirectComposer.kt $J/stitch/FeatureAlignment.kt $J/stitch/EquirectFill.kt $J/stitch/StitchJobs.kt $J/capture/CaptureGrid.kt $J/capture/SphereMath.kt $J/data/Models.kt $J/util/Bg.kt $ROOT/tools/desktop/StitchHarness.kt $ROOT/tools/desktop/Natives.kt $ROOT/tools/desktop/StitchDiag.kt"

if [ ! -d "$OUT/classes" ] || [ -n "$(find $SRC $ROOT/tools/desktop/stub -newer "$OUT/classes" 2>/dev/null)" ]; then
  echo "== compilation =="
  rm -rf "$OUT/classes" "$OUT/stub"
  mkdir -p "$OUT/stub"
  # android.jar ne contient que des signatures : on fournit un vrai android.util.Log
  javac -d "$OUT/stub" $(find "$ROOT/tools/desktop/stub" -name '*.java') 2>&1 | grep -v "^Note:" || true
  "$KOTLINC" -classpath "$CP" -d "$OUT/classes" -jvm-target 17 $SRC 2>&1 | grep -vE "^warning: |^info: " || true
fi
MAIN="${MAIN:-care.primary.sphere360.desktop.StitchHarness}"
exec java -Xmx6g -Dorg.bytedeco.javacpp.maxbytes=0 -Dorg.bytedeco.javacpp.maxphysicalbytes=0 \
  -cp "$OUT/classes:$OUT/stub:$RUNCP" "$MAIN" "$1" "$2" "$NAT" "${@:3}"
