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

**Assembler** : l'assemblage démarre seul à la fin de la capture et tourne en tâche de fond, avec une
notification de progression. Comptez une poignée de secondes. Appui long sur une sphère puis
« Affiner l'assemblage » pour tenter le recalage par points d'intérêt d'OpenCV, plus lent et plus
incertain ; en cas d'échec la sphère existante est conservée.

**Visualiser** : appui sur une vignette. Glisser pour regarder autour, pincer pour zoomer. Appui long
sur une vignette : renommer / supprimer.

**Relier les pièces (V2)** : dans le viewer, le bouton crayon passe en mode édition (cadre jaune).
Toucher un endroit de la sphère ouvre le dialogue « Nouveau portail » : pièce de destination, libellé
(prérempli avec le nom de la pièce), et par défaut la création du portail retour dans la pièce cible
(placé à l'opposé, à déplacer si besoin). Toucher un libellé existant permet de le modifier, le déplacer
(puis toucher le nouvel emplacement) ou le supprimer. Le bouton cible enregistre la vue courante comme
**vue d'entrée** de la sphère (utilisée quand on l'ouvre depuis la galerie). Le bouton retour quitte
d'abord le mode édition.

**Visiter** : hors édition, chaque portail apparaît comme une flèche 3D au sol (VirtualTourPlugin)
pointant vers la destination, plus un libellé lisible posé à l'endroit exact du portail. Toucher l'un ou
l'autre lance la transition ; la vue d'arrivée regarde dans la direction du portail emprunté. Le bouton
retour revient à la pièce précédente (historique), puis ferme le viewer.

**Visite de démo** : menu ⋮ → « Charger la visite de démo » (ou le bouton de l'écran vide). Trois
pièces synthétiques (Salon ↔ Cuisine, Salon ↔ Chambre) rendues par lancer de rayons, avec les portes
exactement dans la direction des portails, pour tester la navigation de bout en bout. Elles sont
éditables et supprimables comme les autres sphères.

## Architecture

```
app/src/main/java/care/primary/sphere360/
  capture/   CaptureActivity (Camera2 + capteurs), CameraController, OrientationTracker,
             CaptureGrid (grille azimut × élévation depuis le FOV), SphereMath, GuidanceOverlay
  stitch/    StitchService (service de premier plan, file d'attente), SphereStitcher (cv::Stitcher),
             EquirectGeometry (placement exact sur le canevas 2:1), EquirectFill (pôles, trous),
             OpenCvRuntime (chargement des natives), StitchJobs (état observé par la galerie)
  gallery/   GalleryActivity
  viewer/    ViewerActivity (WebView, mode édition, dialogues de portail), LocalContentServer (origine https locale)
  demo/      DemoTourInstaller (copie assets/demo → galerie)
  data/      Sphere, Portal, CaptureSessionMeta (JSON), TourStore (persistance filesDir)
app/src/main/assets/viewer/   index.html, viewer.js (glue), vendor/psv-bundle.* (three + Photo Sphere Viewer)
app/src/main/assets/demo/     3 panoramas synthétiques + demo.json (générés par tools/make-demo-panos.py)
web/                          sources du bundle du viewer (npm + esbuild) et test Playwright
tools/                        scripts de build sans SDK manager, keystore debug
```

### Briques réutilisées (pas réinventées)

| Besoin | Brique | Pourquoi |
|---|---|---|
| Stitching | **OpenCV 4.14 `cv::Stitcher`** mode PANORAMA (warper sphérique, `BundleAdjusterRay`, correction d'ondulation, `MultiBandBlender`) via les bindings Java **JavaCPP Presets** (`org.bytedeco:opencv`) | L'AAR officiel `org.opencv:opencv` n'expose pas le module `stitching` en Java (il faudrait du C++/NDK). JavaCPP expose tout, y compris `cameras()` et `resultMask()`, indispensables pour placer le résultat exactement sur la sphère. |
| Viewer 360 | **Photo Sphere Viewer 5.15** (core + `VirtualTourPlugin` + `MarkersPlugin`) sur three.js, empaqueté avec esbuild dans une WebView | Drag, pinch-to-zoom, transitions, flèches 3D et liens entre scènes prêts à l'emploi. |
| UI | Widgets du framework Android (thème Material natif) | Google Maven (AndroidX) n'était pas accessible depuis l'environnement de build, voir plus bas. |

### Portails (V2)

Chaque `Portal` (id, sphère cible, yaw, pitch, libellé) devient côté web un lien du
`VirtualTourPlugin` (flèche 3D, transition, rotation vers le lien puis conservation de la direction à
l'arrivée : c'est le comportement natif du plugin) **et** un marqueur HTML du `MarkersPlugin` à la
position exacte du portail pour que le libellé soit lisible avant de cliquer (les flèches 3D sont
toujours dessinées au sol, sous l'horizon). Les modifications passent par `updateNode` sans recharger
le panorama. Les angles sont en radians, convention Photo Sphere Viewer : yaw 0 au centre de l'image
équirectangulaire, croissant vers la droite ; pitch positif vers le haut.

### Assemblage : deux chemins, une seule projection

L'assemblage part des photos réduites à 1280 px de côté et produit un équirectangulaire 4096 × 2048.

**Chemin par défaut, guidé par les capteurs.** Chaque photo est reprojetée dans le canevas à partir
de la matrice de rotation enregistrée au déclenchement (`GAME_ROTATION_VECTOR`, donc gyroscope et
accéléromètre, sans magnétomètre) et de la focale déduite du champ de vue de la caméra. Ce chemin ne
dépend ni de la texture des murs ni de la lumière, et il aboutit toujours.

**Chemin d'affinage, sur demande.** Le module stitching d'OpenCV recale les photos sur leurs points
d'intérêt : détection SIFT, appariement des deux meilleurs voisins, plus grande composante connexe,
estimation par homographies, ajustement de faisceau par rayons, redressement de l'horizon. Il corrige
les erreurs de focale et les petits déplacements, mais il est lent et échoue régulièrement en
intérieur (voir « Pourquoi le recalage n'est pas le chemin par défaut »). On lui demande seulement les
rotations et les focales : la projection reste celle du chemin capteurs.

Le repère du recalage est ensuite ramené sur celui des capteurs. OpenCV construit son repère autour
de la photo qu'il choisit comme référence et ne redresse l'horizon qu'en moyennant les orientations
des caméras ; le repère des capteurs, lui, tient sa verticale de la gravité et son azimut zéro de la
première photo. La rotation entre les deux repères est estimée sur les photos présentes des deux
côtés, par moyenne de quaternions. Les photos que le recalage a écartées reprennent leur orientation
capteur, ce qui évite de laisser le plafond vide.

Le résultat n'est retenu que s'il couvre au moins 97 % de ce que couvrent les capteurs, mesuré en
angle solide sur une grille grossière. Sinon la sphère est recomposée depuis les capteurs seuls.

### Mélange des recouvrements

Moyenner les photos qui se recouvrent produit un dédoublement translucide très visible, et c'était
le principal défaut visuel des versions précédentes. La grille prévoit 40 % de recouvrement
horizontal et les rangées se chevauchent sur une vingtaine de degrés : un pixel reçoit donc souvent
trois ou quatre photos à poids égal. Le moindre écart entre elles, parallaxe due à un déplacement de
l'utilisateur ou dérive de quelques degrés du gyroscope, se lit alors comme une double exposition sur
de larges zones.

La composition sépare maintenant chaque photo en deux échelles, dans l'esprit d'un mélange
multi-bandes :

- les **fonds**, structures plus larges qu'un quarante-huitième du tour d'horizon (environ 85 pixels
  sur un canevas de 4096), sont moyennés avec un poids doux sur tout le recouvrement. Les écarts
  d'exposition et le vignetage se diluent progressivement, sans marche à la jointure, et un
  dédoublement à cette échelle ne se voit pas ;
- les **détails** viennent de la photo qui regarde le pixel le plus près de son centre, avec un fondu
  court vers la deuxième mieux centrée autour de leur frontière. Les contours restent donc nets et
  uniques, et la parallaxe résiduelle se lit comme un léger décalage local plutôt que comme une image
  fantôme.

La séparation se fait par convolution normalisée, en ne floutant que les pixels valides pour ne pas
assombrir les bords. La bande des fonds est calculée une fois par photo à un huitième de la
résolution, puis cumulée dans un canevas réduit : à son échelle la réduction est invisible et le coût
divisé par soixante-quatre. Seuls les détails demandent la pleine résolution, traitée par bandes de
colonnes pour tenir dans la mémoire d'un téléphone.

Sur le banc de test, ce changement fait passer la netteté des contours de 0,69 à 0,79 fois celle du
panorama de référence, sans allonger le calcul : trois secondes pour trente photos.

### Pourquoi le recalage n'est pas le chemin par défaut

Mesures sur le banc de test `tools/desktop`, qui rend des captures synthétiques depuis un panorama de
référence et exécute le vrai code d'assemblage (4 cœurs x86_64, 30 photos par sphère) :

| Capture | Capteurs | Recalage OpenCV |
| --- | --- | --- |
| Rotation pure, murs très texturés | 1,2 s, erreur 3,4 niveaux | 5,2 s, erreur 2,9 niveaux |
| Tremblements 1,5°, roulis 3°, déplacement 6 cm, exposition ±4 % | 1,2 s, aboutit | 132 s, ajustement de faisceau non convergent |
| Murs quasi unis | 1,2 s, aboutit | échoue sur assertion FLANN (corrigé par le filtrage) |

L'erreur est la différence absolue moyenne en niveaux de gris face au panorama de référence. Le
recalage apporte donc un gain marginal quand il réussit, pour un coût de deux ordres de grandeur et
un taux d'échec élevé dès que la capture n'est pas parfaite. Sur un téléphone, comptez plusieurs
minutes. D'où le choix : capteurs par défaut, recalage proposé par appui long sur une sphère.

### Deux pannes trouvées par le banc de test

Les deux erreurs remontées après la V2 sont reproduites et corrigées :

- **« L'alignement a échoué »** (`ERR_CAMERA_PARAMS_ADJUST_FAIL`). Le masque d'appariement comparait
  séparément l'écart d'azimut et l'écart d'élévation, ce qui excluait les voisins diagonaux : le
  graphe d'appariement était un arbre, sans aucun cycle, et l'ajustement de faisceau n'y converge
  pas. Le critère porte maintenant sur l'angle entre les axes optiques comparé au champ de vue
  diagonal, ce qui donne au moins trois voisins par position (vérifié par un test).
- **« Erreur pendant l'assemblage : OpenCV … »** Une photo sans texture (mur uni, plafond) produit
  moins de deux descripteurs SIFT. La recherche des deux plus proches voisins de FLANN échoue alors
  sur une assertion et fait tomber tout l'assemblage. Les photos comptant moins de douze points
  d'intérêt sont désormais écartées du recalage, et replacées par leur orientation capteur.

Un troisième défaut a été trouvé au passage : `Stitcher.setWarper` prend un pointeur partagé C++,
et lui confier un objet alloué côté Java crée une double propriété (le ramasse-miettes de la machine
virtuelle et le pointeur C++ libèrent le même objet). Le code n'appelle plus ce point d'entrée.

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

- **Logique pure** : 29 tests JUnit sur machine virtuelle, sans Android ni OpenCV
  (`tools/run-tests.sh`) : grille de capture et connexité du graphe d'appariement, géométrie des
  capteurs et de la projection équirectangulaire, moyenne de rotations par quaternions, estimation
  de couverture, remplissage des pôles, sérialisation du modèle.
- **Assemblage** : banc de test `tools/desktop` qui rend des captures synthétiques depuis un
  panorama de référence (tremblements, roulis, déplacement parasite, exposition, bruit, flou) et
  exécute le vrai code d'assemblage avec les bibliothèques OpenCV Linux. Le chemin par défaut
  aboutit sur les cinq conditions testées, y compris murs quasi unis et capture partielle, et reste
  géométriquement exact : 5,1 niveaux de gris d'écart moyen face au panorama de référence sur une
  capture idéale. Un passage avec `System.gc()` en continu vérifie qu'aucun objet natif n'est
  libéré trop tôt.
- **Viewer** : tests d'intégration Playwright dans Chromium headless (WebGL logiciel) :
  `web/test/viewer.test.mjs` (chargement, libellés, flèches 3D, navigation par portail avec vue
  d'arrivée dans la direction du portail, retour arrière, mode édition, mise à jour de nœud) et
  `web/test/demo.test.mjs` (parcours complet Salon ↔ Cuisine, Salon ↔ Chambre de la visite de démo).
- **APK** : structure, permissions, bibliothèques natives et signature vérifiées avec `aapt2` et
  `apksigner`.
- **Non testé** : l'application n'a pas tourné sur un téléphone réel. L'environnement de
  compilation n'a ni appareil ni émulateur, et le SDK Android de Google y est inaccessible. Le flux
  Camera2, le ressenti du guidage et le comportement des bibliothèques OpenCV sur ARM restent à
  valider sur ton appareil.

## Limitations connues

- **Qualité des raccords.** Le chemin par défaut suppose une rotation pure autour de l'objectif. Tout
  déplacement latéral (se pencher, tourner autour de son épaule plutôt qu'autour du téléphone) crée
  une parallaxe que la projection ne peut pas corriger. Le mélange à deux échelles la transforme en
  léger décalage local à la frontière entre deux photos, au lieu de l'image fantôme que produisait une
  moyenne, mais elle reste visible sur les objets proches. Les murs lointains sont nets. L'affinage
  par recalage corrige une partie de ces petits déplacements quand il aboutit.
- **Placement des jointures.** La frontière entre deux photos passe à mi-chemin de leurs centres,
  sans tenir compte du contenu. Une jointure qui tombe sur un objet proche est plus visible que si
  elle suivait un mur uni ; corriger cela demanderait une recherche de couture par coupe de graphe.
- **Précision du champ de vue.** La focale vient des métadonnées de la caméra (taille physique du
  capteur et longueur focale). Si le pilote les renseigne mal, toutes les jointures sont décalées de
  la même façon. L'affinage par recalage est le moyen de corriger ce cas, puisqu'il estime la focale.
- **Zénith et nadir.** La grille photographie trois rangées et ne monte pas au-delà de 85° environ :
  il reste une calotte au sommet et une au sol, soit à peu près 3 % de la sphère de chaque côté.
  Elles sont extrapolées par fondu des couleurs de bord, sans détail. Le nadir montre souvent un
  léger étirement vertical.
- **Dérive du gyroscope.** L'orientation vient d'un capteur fusionné sans magnétomètre : sur une
  capture longue (plus d'une minute), une dérive de quelques degrés en azimut est possible et se
  traduit par un raccord visible au point de bouclage.
- **Vitesse de rotation.** Tourner vite floute les photos et fait rater des positions. Le guidage
  déclenche quand la vitesse angulaire descend sous 0,5 rad/s ; un appui sur l'image force la prise
  si le déclenchement automatique ne se fait pas.
- **Retour de capture.** Une vibration brève confirme chaque prise, sans bruit d'obturateur : une
  capture guidée enchaîne une trentaine de photos et autant de déclics serait fatigant.
- **Lumière.** L'exposition et la balance des blancs sont verrouillées au démarrage de la capture,
  ce qui évite les sauts de luminosité entre photos. En contrepartie, une pièce très contrastée
  (fenêtre en plein jour d'un côté, coin sombre de l'autre) sera correctement exposée d'un seul côté.
- **Recalage par points d'intérêt.** Lent (une à deux minutes pour 30 photos) et sans garantie
  d'aboutir : murs unis, faible lumière ou rotation rapide le font échouer. C'est pour cela qu'il
  n'est pas le chemin par défaut.
- **Stockage.** Les photos réduites sont conservées après l'assemblage pour permettre un affinage
  ultérieur, soit environ 5 à 10 Mo par sphère en plus de l'équirectangulaire. Elles sont supprimées
  avec la sphère.
- **Résolution.** Équirectangulaire 4096 × 2048, compromis entre détail et mémoire GPU des WebView
  mobiles.
- **Portails.** La flèche 3D est toujours affichée au sol dans la direction du portail (comportement
  du VirtualTourPlugin), c'est le libellé qui marque l'endroit exact touché. Le portail retour créé
  automatiquement est placé à l'opposé de la direction d'arrivée : à ajuster avec « Déplacer » si la
  porte n'est pas en face. Il n'y a pas de correction d'orientation entre sphères : chacune a son
  azimut zéro sur la direction de sa première photo.
- **Matériel.** APK arm64 uniquement (aucun téléphone Android récent n'est en 32 bits). Android 8.0
  minimum. Un capteur d'orientation fusionné est requis.
- **Démo.** Panoramas synthétiques : aucune banque de photos n'était accessible depuis
  l'environnement de compilation.

## Licences des briques

OpenCV (Apache 2.0), JavaCPP Presets (Apache 2.0 / GPLv2 with Classpath Exception), Photo Sphere Viewer
(MIT), three.js (MIT).
