#!/usr/bin/env bash
# Installe une chaîne de build Android minimale SANS le SDK manager de Google
# (utile quand dl.google.com est inaccessible). Tout est téléchargé depuis :
#  - apt (Debian/Ubuntu)            : aapt2, zipalign, apksigner, patchelf
#  - GitHub                          : android.jar (API 34), compilateur Kotlin
#  - storage.googleapis.com          : r8lib.jar (D8/R8, dexer officiel)
#  - Maven Central                   : JavaCPP + OpenCV (Java + natives arm64), JUnit, org.json
#
# Usage : TOOLCHAIN_DIR=~/.sphere360-toolchain ./tools/setup-toolchain.sh
set -euo pipefail

TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$HOME/.sphere360-toolchain}"
KOTLIN_VERSION="${KOTLIN_VERSION:-2.0.21}"
R8_VERSION="${R8_VERSION:-8.5.35}"
ANDROID_API="${ANDROID_API:-34}"
JAVACPP_VERSION="${JAVACPP_VERSION:-1.5.14}"
OPENCV_VERSION="${OPENCV_VERSION:-4.14.0-1.5.14}"
MAVEN="https://repo1.maven.org/maven2"

mkdir -p "$TOOLCHAIN_DIR"
cd "$TOOLCHAIN_DIR"

fetch() { # url dest
  if [ ! -s "$2" ]; then echo "  -> $(basename "$2")"; curl -fsSL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2"; fi
}

echo "[1/5] Outils système (aapt2, zipalign, apksigner, patchelf)"
for t in aapt2 zipalign apksigner patchelf; do
  if ! command -v "$t" >/dev/null 2>&1; then
    if command -v apt-get >/dev/null 2>&1; then
      SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"
      $SUDO apt-get install -y aapt zipalign apksigner patchelf
    else
      echo "Installe $t manuellement (brew install $t / apt install ...)" >&2; exit 1
    fi
    break
  fi
done

echo "[2/5] android.jar (API $ANDROID_API)"
fetch "https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-$ANDROID_API/android.jar" "android-$ANDROID_API.jar"

echo "[3/5] D8/R8 $R8_VERSION"
fetch "https://storage.googleapis.com/r8-releases/raw/$R8_VERSION/r8lib.jar" "r8lib.jar"

echo "[4/5] Kotlin $KOTLIN_VERSION"
if [ ! -x kotlinc/bin/kotlinc ]; then
  fetch "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip" "kotlin-compiler.zip"
  unzip -q -o kotlin-compiler.zip
fi

echo "[5/5] Dépendances Java (JavaCPP/OpenCV, JUnit, org.json)"
mkdir -p libs
fetch "$MAVEN/org/bytedeco/javacpp/$JAVACPP_VERSION/javacpp-$JAVACPP_VERSION.jar" "libs/javacpp.jar"
fetch "$MAVEN/org/bytedeco/javacpp/$JAVACPP_VERSION/javacpp-$JAVACPP_VERSION-android-arm64.jar" "libs/javacpp-android-arm64.jar"
fetch "$MAVEN/org/bytedeco/opencv/$OPENCV_VERSION/opencv-$OPENCV_VERSION.jar" "libs/opencv.jar"
fetch "$MAVEN/org/bytedeco/opencv/$OPENCV_VERSION/opencv-$OPENCV_VERSION-android-arm64.jar" "libs/opencv-android-arm64.jar"
fetch "$MAVEN/junit/junit/4.13.2/junit-4.13.2.jar" "libs/junit.jar"
fetch "$MAVEN/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar" "libs/hamcrest-core.jar"
fetch "$MAVEN/org/json/json/20240303/json-20240303.jar" "libs/json.jar"

echo "OK. Toolchain dans $TOOLCHAIN_DIR"
