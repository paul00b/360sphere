#!/usr/bin/env bash
# Regroupe three.js + Photo Sphere Viewer (core, virtual-tour, markers) en un seul bundle IIFE
# pour la WebView (pas de modules ES ni de CDN à l'exécution). Résultat versionné dans
# app/src/main/assets/viewer/vendor/.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/web"
[ -d node_modules ] || npm ci --no-audit --no-fund
npx esbuild entry.js --bundle --minify --format=iife --target=es2020 \
  --outfile="$ROOT/app/src/main/assets/viewer/vendor/psv-bundle.js"
cat node_modules/@photo-sphere-viewer/core/index.css \
    node_modules/@photo-sphere-viewer/virtual-tour-plugin/index.css \
    node_modules/@photo-sphere-viewer/markers-plugin/index.css \
    > "$ROOT/app/src/main/assets/viewer/vendor/psv-bundle.css"
node -e 'const p=require("./node_modules/@photo-sphere-viewer/core/package.json");const t=require("./node_modules/three/package.json");console.log("PSV "+p.version+" / three "+t.version)' > "$ROOT/app/src/main/assets/viewer/vendor/VERSIONS.txt"
cat "$ROOT/app/src/main/assets/viewer/vendor/VERSIONS.txt"
