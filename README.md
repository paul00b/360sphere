# Sphère 360

Application Android native (Kotlin) de capture de photos sphériques 360° en intérieur et de
visite virtuelle avec navigation entre pièces.

- **V1** : capture guidée façon Photo Sphere, assemblage OpenCV en tâche de fond, galerie, viewer 360.
- **V2** : portails entre sphères (flèches 3D + libellés), mode édition, visite de démo.

Les APK debug livrées sont dans [`releases/`](releases/) (une par version, voir les tags `v1.0` et `v2.0`).

## Installation de l'APK

1. Télécharger `releases/sphere360-vX-debug.apk` sur le téléphone (Android 8.0+, processeur 64 bits arm64, soit
   tout téléphone depuis ~2016).
2. Autoriser l'installation depuis une source inconnue quand Android le demande, puis installer.
3. Les versions successives se mettent à jour l'une sur l'autre (même clé de signature debug,
   `tools/debug.keystore`). Un APK compilé par Android Studio avec ce dépôt utilise la même clé.

## Utilisation

**Capturer une sphère** : bouton appareil photo dans la galerie. L'écran explique le principe : rester sur
place, garder le téléphone proche du corps et pivoter sur soi-même. L'exposition, la balance des blancs
et la mise au point sont verrouillées au départ. Le réticule central doit être amené sur chaque point ; la
photo se déclenche seule quand l'alignement est bon (< 3°) et que le téléphone est immobile. Une flèche
indique le point suivant s'il est hors champ, la mini-carte du bas montre la couverture. La grille
(3 rangées : horizon, +46°, −46° environ, ~30 photos) est calculée depuis le champ de vue réel de la
caméra pour garantir ~40 % de recouvrement. « Terminer » permet d'arrêter avant la fin (sphère
incomplète, zones grises).

**Assemblage** : lancé automatiquement à la fin de la capture dans un service de premier plan
(notification de progression). La galerie affiche la carte « Assemblage en cours », puis la sphère avec sa
vignette. En cas d'échec, un message explicite (photos qui ne se recoupent pas, déplacement pendant la
capture, mémoire…) et un bouton « Réessayer » (réglages plus tolérants au second essai) ou « Supprimer ».

**Visualiser** : appui sur une vignette. Glisser pour regarder autour, pincer pour zoomer. Appui long
sur une vignette : renommer / supprimer.

## Architecture

```
app/src/main/java/care/primary/sphere360/
  capture/   CaptureActivity (Camera2 + capteurs), CameraController, OrientationTracker,
             CaptureGrid (grille azimut × élévation depuis le FOV), SphereMath, GuidanceOverlay
  stitch/    StitchService (service de premier plan, file d'attente), SphereStitcher (cv::Stitcher),
             EquirectGeometry (placement exact sur le canevas 2:1), EquirectFill (pôles, trous),
             OpenCvRuntime (chargement des natives), StitchJobs (état observé par la galerie)
  gallery/   GalleryActivity
  viewer/    ViewerActivity (WebView), LocalContentServer (origine https locale pour les assets/images)
  data/      Sphere, Portal, CaptureSessionMeta (JSON), TourStore (persistance filesDir)
app/src/main/assets/viewer/   index.html, viewer.js (glue), vendor/psv-bundle.* (three + Photo Sphere Viewer)
web/                          sources du bundle du viewer (npm + esbuild) et test Playwright
tools/                        scripts de build sans SDK manager, keystore debug
```

### Briques réutilisées (pas réinventées)

| Besoin | Brique | Pourquoi |
|---|---|---|
| Stitching | **OpenCV 4.14 `cv::Stitcher`** mode PANORAMA (warper sphérique, `BundleAdjusterRay`, correction d'ondulation, `MultiBandBlender`) via les bindings Java **JavaCPP Presets** (`org.bytedeco:opencv`) | L'AAR officiel `org.opencv:opencv` n'expose pas le module `stitching` en Java (il faudrait du C++/NDK). JavaCPP expose tout, y compris `cameras()` et `resultMask()`, indispensables pour placer le résultat exactement sur la sphère. |
| Viewer 360 | **Photo Sphere Viewer 5.15** (core + `VirtualTourPlugin` + `MarkersPlugin`) sur three.js, empaqueté avec esbuild dans une WebView | Drag, pinch-to-zoom, transitions, flèches 3D et liens entre scènes prêts à l'emploi. |
| UI | Widgets du framework Android (thème Material natif) | Google Maven (AndroidX) n'était pas accessible depuis l'environnement de build, voir plus bas. |

### Du panorama OpenCV à l'équirectangulaire

`cv::Stitcher` produit le rectangle englobant des images projetées sur la sphère, pas une image 2:1
complète. `SphereStitcher` reproduit le calcul interne de `composePanorama` (échelle de composition,
focale médiane, `warpRoi` de chaque caméra) pour connaître le coin haut-gauche du résultat dans le plan
sphérique, le pose sur un canevas `2π·s × π·s` (cible 4096 × 2048, taille de texture sûre en WebGL
mobile) avec gestion du passage ±180°, puis complète : trous entre photos (interpolation), colonnes non
capturées (gris neutre, volontairement visible), calottes polaires (extrapolation floue de la couleur de
bord convergeant vers une couleur uniforme au pôle, donc sans couture au zénith). Une vérification
compare la taille du panorama à celle prédite ; en cas d'écart, repli sur un placement centré.

Les orientations capteur enregistrées à chaque photo servent à construire le **masque
d'appariement** d'OpenCV : seules les paires de photos qui se recouvrent géométriquement sont comparées.
Ça divise le temps de calcul et évite les fausses correspondances entre murs semblables.

## Compiler

### Android Studio (chemin normal)

Ouvrir le dossier, laisser Gradle synchroniser (AGP 8.7, Kotlin 2.0, `compileSdk 34`, `minSdk 26`), puis :

```bash
./tools/setup-toolchain.sh      # une fois : télécharge les jars JavaCPP/OpenCV (et outils hors SDK)
./tools/prepare-natives.sh      # produit app/src/main/jniLibs/arm64-v8a (patchelf requis)
./gradlew assembleDebug
```

Les natives ne sont pas récupérées par Gradle : les jars `android-arm64` de JavaCPP contiennent
~90 Mo de modules (DNN, xfeatures2d…) et une dépendance OpenBLAS de 35 Mo dont le stitching n'a pas
besoin. `prepare-natives.sh` n'extrait que 13 bibliothèques (~24 Mo), supprime les entrées `DT_NEEDED`
superflues avec `patchelf` et vérifie qu'aucun symbole importé ne manque. Côté Java, le chargeur
automatique de JavaCPP est désactivé (`org.bytedeco.javacpp.loadlibraries=false`) et
`OpenCvRuntime` charge explicitement ces bibliothèques.

### Sans SDK manager (chemin utilisé pour produire les APK livrées)

L'environnement de build n'avait pas accès à `dl.google.com` (SDK Android, Google Maven, NDK,
AndroidX). Les APK ont donc été produites avec `tools/build-apk.sh` :
`aapt2` (paquet Debian) → `javac` (R) → `kotlinc` → `d8` (r8lib officiel, storage.googleapis.com) →
`zipalign` → `apksigner`, avec `android.jar` API 34 récupéré depuis GitHub et les dépendances depuis
Maven Central. Voir `tools/setup-toolchain.sh`.

```bash
TOOLCHAIN_DIR=~/.sphere360-toolchain ./tools/setup-toolchain.sh
./tools/prepare-natives.sh
./tools/build-apk.sh            # -> build/offline/sphere360-debug.apk
./tools/run-tests.sh            # tests JVM (grille, géométrie, remplissage, JSON)
cd web && npm ci && node test/viewer.test.mjs /chemin/panoramas /chemin/captures   # test Playwright du viewer
./tools/build-viewer.sh         # regénère le bundle Photo Sphere Viewer après mise à jour npm
```

## État des tests

- **Tests JVM** (20) : grille de capture, projection/orientation, géométrie équirectangulaire,
  remplissage des pôles et trous, sérialisation JSON.
- **Viewer** : test d'intégration Playwright dans Chromium headless (WebGL logiciel) : chargement,
  libellés, flèches 3D, navigation par portail avec vue d'arrivée dans la direction du portail,
  retour arrière, mode édition, mise à jour de nœud.
- **APK** : vérifiée structurellement (`aapt2 dump badging`, `apksigner verify`, classes dans le dex,
  natives, assets).
- **Non testé ici** : exécution sur un téléphone réel (pas d'émulateur ni d'appareil dans
  l'environnement de build). La capture Camera2, les capteurs et l'assemblage OpenCV sur ARM sont
  écrits d'après les APIs documentées et relus, mais c'est ton test sur téléphone qui tranche.
  Les logs sont sous les tags `OpenCvRuntime`, `StitchService`, `Viewer`.

## Limitations connues

- **Qualité du stitching** : dépend de la texture des murs (un mur uni ne donne aucun point
  d'intérêt), de la lumière (bruit en basse lumière), de la vitesse de rotation (flou de bougé) et du
  respect de la rotation pure. Un déplacement du téléphone de quelques dizaines de centimètres crée de la
  parallaxe sur les objets proches : raccords visibles ou échec « alignement ». Recommencer en gardant
  le téléphone près du corps.
- **Exposition verrouillée** au départ : commencer face à une zone représentative. Une fenêtre en
  contre-jour dans le dos donnera une zone très sombre ou cramée.
- **Pôles non capturés** : la grille couvre environ ±79° d'élévation. Zénith et nadir sont extrapolés
  (couleur douce), pas photographiés. Pour une visite d'intérieur c'est en général invisible, mais le
  plafond et le sol juste au-dessus/dessous de soi ne sont pas réels.
- **Photos écartées** : si une photo ne se raccorde à aucune autre, OpenCV la retire ; la galerie
  signale « N photo(s) non raccordée(s) » et la zone correspondante est extrapolée. Si moins de la
  moitié des photos sont raccordées, l'assemblage est déclaré en échec.
- **Fin anticipée** : les colonnes d'azimut jamais photographiées apparaissent en gris neutre.
- **Temps et mémoire** : ~30 photos ramenées à 1280 px, SIFT (1500 points/image), composition à
  4096 px : comptez 30 s à 2 min selon le téléphone, quelques centaines de Mo de mémoire native.
  Sur un téléphone à 3 Go, fermer les autres applications.
- **Capteurs** : le guidage utilise `GAME_ROTATION_VECTOR` (gyroscope + accéléromètre). Sans
  gyroscope, `ROTATION_VECTOR` (magnétomètre) est utilisé et peut sauter près de métal ou
  d'électronique.
- **Architecture** : APK arm64 uniquement (JavaCPP ne publie plus de natives Android 32 bits).
- **Résolution** : équirectangulaire 4096 × 2048 (compromis qualité / mémoire GPU des WebView mobiles).

## Licences des briques

OpenCV (Apache 2.0), JavaCPP Presets (Apache 2.0 / GPLv2 with Classpath Exception), Photo Sphere Viewer
(MIT), three.js (MIT).
