#!/usr/bin/env bash
# Télécharge les natives OpenCV/JavaCPP Linux x86_64 nécessaires au banc de test desktop.
set -euo pipefail
TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$HOME/.sphere360-toolchain}"
LINUX_DIR="$TOOLCHAIN_DIR/linux"
M=https://repo1.maven.org/maven2/org/bytedeco
mkdir -p "$LINUX_DIR"
for u in \
 "$M/opencv/4.14.0-1.5.14/opencv-4.14.0-1.5.14-linux-x86_64.jar" \
 "$M/javacpp/1.5.14/javacpp-1.5.14-linux-x86_64.jar" \
 "$M/openblas/0.3.34-1.5.14/openblas-0.3.34-1.5.14-linux-x86_64.jar" \
 "$M/openblas/0.3.34-1.5.14/openblas-0.3.34-1.5.14.jar" ; do
  f="$LINUX_DIR/$(basename $u)"
  [ -s "$f" ] || curl -fsSL -o "$f" "$u"
done
echo "OK : $LINUX_DIR"
