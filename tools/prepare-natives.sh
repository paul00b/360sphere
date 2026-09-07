#!/usr/bin/env bash
# Extrait les bibliothèques natives arm64 d'OpenCV/JavaCPP nécessaires au stitching,
# supprime avec patchelf les dépendances DT_NEEDED inutilisées (DNN, xfeatures2d, OpenBLAS...)
# et vérifie que la fermeture des dépendances est complète.
# Résultat : app/src/main/jniLibs/arm64-v8a/*.so (~25 Mo).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$HOME/.sphere360-toolchain}"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
WORK="$ROOT/build/natives"

for t in patchelf unzip; do command -v $t >/dev/null || { echo "$t manquant"; exit 1; }; done
[ -s "$TOOLCHAIN_DIR/libs/opencv-android-arm64.jar" ] || { echo "Lance d'abord tools/setup-toolchain.sh"; exit 1; }

# Bibliothèques OpenCV réellement utilisées (core → stitching) + leurs wrappers JNI.
CV_LIBS=(opencv_core opencv_imgproc opencv_imgcodecs opencv_flann opencv_features2d opencv_calib3d opencv_stitching)
JNI_LIBS=(jnijavacpp jniopencv_core jniopencv_imgproc jniopencv_imgcodecs jniopencv_features2d jniopencv_stitching)
SYSTEM_LIBS="libc.so libm.so libdl.so liblog.so libandroid.so"

rm -rf "$WORK" && mkdir -p "$WORK" "$OUT"
( cd "$WORK" && unzip -q -o "$TOOLCHAIN_DIR/libs/opencv-android-arm64.jar" 'lib/arm64-v8a/*' && unzip -q -o "$TOOLCHAIN_DIR/libs/javacpp-android-arm64.jar" 'lib/arm64-v8a/*' )
SRC="$WORK/lib/arm64-v8a"

KEEP=""
for l in "${CV_LIBS[@]}" "${JNI_LIBS[@]}"; do KEEP="$KEEP lib$l.so"; done

needed() { patchelf --print-needed "$1"; }

rm -f "$OUT"/*.so
for l in $KEEP; do
  cp "$SRC/$l" "$OUT/$l"
  for dep in $(needed "$OUT/$l"); do
    case " $KEEP $SYSTEM_LIBS " in
      *" $dep "*) ;;
      *) patchelf --remove-needed "$dep" "$OUT/$l" ;;
    esac
  done
done

# Vérification : aucun symbole importé ne doit provenir d'une lib supprimée.
python3 - "$SRC" "$OUT" "$SYSTEM_LIBS" <<'PY'
import os, subprocess, sys
src, out, system = sys.argv[1], sys.argv[2], sys.argv[3].split()
def syms(path):
    txt = subprocess.run(["llvm-readelf" if subprocess.run(["which","llvm-readelf"],capture_output=True).returncode==0 else "readelf","--dyn-syms","-W",path],capture_output=True,text=True).stdout
    und, defd = set(), set()
    for line in txt.splitlines():
        p = line.split()
        if len(p) < 8 or not p[0].endswith(":"): continue
        name = p[7].split("@")[0]
        (und if p[6] == "UND" else defd).add(name)
    return und, defd
exports = {}
kept = sorted(f for f in os.listdir(out) if f.endswith(".so"))
for f in kept: exports[f] = syms(os.path.join(out, f))[1]
removed_exports = {}
for f in os.listdir(src):
    if f.endswith(".so") and f not in exports: removed_exports[f] = syms(os.path.join(src, f))[1]
ok = True
for f in kept:
    und = syms(os.path.join(out, f))[0]
    provided = set().union(*exports.values())
    for rf, ex in removed_exports.items():
        bad = und & ex - provided
        if bad:
            ok = False; print(f"ERREUR: {f} importe {len(bad)} symboles de {rf} (supprimée), ex: {sorted(bad)[:3]}")
    needed = subprocess.run(["patchelf","--print-needed",os.path.join(out,f)],capture_output=True,text=True).stdout.split()
    for n in needed:
        if n not in exports and n not in system:
            ok = False; print(f"ERREUR: {f} dépend encore de {n}")
print("Vérification des natives :", "OK" if ok else "ÉCHEC")
sys.exit(0 if ok else 1)
PY
du -sh "$OUT"; ls "$OUT"
