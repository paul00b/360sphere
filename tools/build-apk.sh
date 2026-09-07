#!/usr/bin/env bash
# Build de l'APK debug SANS Android Gradle Plugin ni SDK manager :
#   aapt2 (ressources) -> javac (R.java) -> kotlinc -> d8 -> zip -> zipalign -> apksigner
# Prérequis : ./tools/setup-toolchain.sh puis ./tools/prepare-natives.sh
# Usage : ./tools/build-apk.sh [--release-tag v1]   -> build/offline/sphere360-debug.apk
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$HOME/.sphere360-toolchain}"
ANDROID_API="${ANDROID_API:-34}"
MIN_SDK=26
TARGET_SDK=34
PACKAGE="care.primary.sphere360"

ANDROID_JAR="$TOOLCHAIN_DIR/android-$ANDROID_API.jar"
KOTLINC="$TOOLCHAIN_DIR/kotlinc/bin/kotlinc"
KOTLIN_STDLIB="$TOOLCHAIN_DIR/kotlinc/lib/kotlin-stdlib.jar"
R8="$TOOLCHAIN_DIR/r8lib.jar"
LIBS="$TOOLCHAIN_DIR/libs"
APP="$ROOT/app/src/main"
OUT="$ROOT/build/offline"
NATIVES="$APP/jniLibs/arm64-v8a"
KEYSTORE="$ROOT/tools/debug.keystore"

for f in "$ANDROID_JAR" "$KOTLINC" "$R8" "$LIBS/javacpp.jar" "$LIBS/opencv.jar"; do
  [ -e "$f" ] || { echo "Manquant : $f (lance tools/setup-toolchain.sh)"; exit 1; }
done
[ -n "$(ls -A "$NATIVES" 2>/dev/null)" ] || { echo "Natives manquantes : lance tools/prepare-natives.sh"; exit 1; }
for t in aapt2 zipalign apksigner zip; do command -v $t >/dev/null || { echo "$t manquant"; exit 1; }; done

rm -rf "$OUT" && mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/staging"

echo "[1/6] aapt2 : compilation des ressources"
aapt2 compile --dir "$APP/res" -o "$OUT/res/res.zip"

# AGP 8 interdit l'attribut package dans le manifest source (namespace via Gradle) ;
# aapt2 en a besoin : on l'injecte dans une copie.
sed "s|<manifest |<manifest package=\"$PACKAGE\" |" "$APP/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

echo "[2/6] aapt2 : link (manifest, ressources, assets)"
aapt2 link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$OUT/AndroidManifest.xml" \
  -R "$OUT/res/res.zip" \
  -A "$APP/assets" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --auto-add-overlay

echo "[3/6] javac (R.java) + kotlinc"
javac --release 17 -d "$OUT/classes" $(find "$OUT/gen" -name '*.java')
CP="$ANDROID_JAR:$OUT/classes:$LIBS/javacpp.jar:$LIBS/opencv.jar"
"$KOTLINC" -no-jdk -no-stdlib -no-reflect \
  -jvm-target 17 -Xno-param-assertions -Xno-call-assertions -Xno-receiver-assertions \
  -classpath "$CP:$KOTLIN_STDLIB" \
  -d "$OUT/classes" \
  $(find "$APP/java" -name '*.kt') 2>&1 | grep -v "^warning: " || true
[ -n "$(find "$OUT/classes" -name 'App.class' 2>/dev/null)" ] || { echo "Échec kotlinc"; exit 1; }

echo "[4/6] d8 : dex"
# Copies des jars sans META-INF/versions (module-info) qui ne concernent pas Android.
for j in javacpp opencv; do
  cp "$LIBS/$j.jar" "$OUT/$j-stripped.jar"; zip -q -d "$OUT/$j-stripped.jar" 'META-INF/*' >/dev/null 2>&1 || true
done
cp "$KOTLIN_STDLIB" "$OUT/kotlin-stdlib-stripped.jar"; zip -q -d "$OUT/kotlin-stdlib-stripped.jar" 'META-INF/*' >/dev/null 2>&1 || true
( cd "$OUT/classes" && zip -q -r "$OUT/app-classes.jar" . -x 'META-INF/*' )
java -Xmx3g -cp "$R8" com.android.tools.r8.D8 \
  --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$OUT/dex" \
  "$OUT/app-classes.jar" "$OUT/kotlin-stdlib-stripped.jar" "$OUT/javacpp-stripped.jar" "$OUT/opencv-stripped.jar"

echo "[5/6] Assemblage de l'APK"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
( cd "$OUT/dex" && zip -q "$OUT/unaligned.apk" classes*.dex )
mkdir -p "$OUT/staging/lib/arm64-v8a" && cp "$NATIVES"/*.so "$OUT/staging/lib/arm64-v8a/"
( cd "$OUT/staging" && zip -q -r "$OUT/unaligned.apk" lib )
zipalign -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "[6/6] Signature (clé debug tools/debug.keystore)"
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --min-sdk-version "$MIN_SDK" \
  --out "$OUT/sphere360-debug.apk" "$OUT/aligned.apk"
apksigner verify --print-certs "$OUT/sphere360-debug.apk" | head -3
ls -la "$OUT/sphere360-debug.apk"
echo "APK : $OUT/sphere360-debug.apk"
