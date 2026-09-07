# Sphère 360

Application Android native (Kotlin) de capture de photos sphériques 360° en intérieur et de
visite virtuelle avec navigation entre pièces.

- **V1** : capture guidée façon Photo Sphere, assemblage OpenCV en tâche de fond, galerie, viewer 360.
- **V2** : portails entre sphères (flèches 3D + libellés), mode édition, visite de démo.
- **V2.3** : objectif grand angle avec correction de distorsion, dossiers (une visite par lieu),
  retouche d'une sphère déjà assemblée (assiette, résolution, raccords, photos écartées).

Les APK debug livrées sont dans [`releases/`](releases/) (une par version, voir les tags `v1.0` et `v2.0`).

## Installation de l'APK

1. Télécharger `releases/sphere360-vX-debug.apk` sur le téléphone (Android 8.0+, processeur 64 bits arm64, soit
   tout téléphone depuis ~2016).
2. Autoriser l'installation depuis une source inconnue quand Android le demande, puis installer.
3. Les versions successives se mettent à jour l'une sur l'autre (même clé de signature debug,
   `tools/debug.keystore`). Un APK compilé par Android Studio avec ce dépôt utilise la même clé.

## Utilisation

**Capturer une sphère** : bouton appareil photo dans la galerie. L'écran explique le principe : rester sur
place, garder le téléphone proche du corps et pivoter sur soi-même. Si le téléphone expose plusieurs
caméras arrière, un bouton « Objectif » propose de choisir : le module principal par défaut, le grand
angle pour diviser presque par deux le nombre de positions à photographier. L'écran indique combien de
photos chaque objectif demande. L'exposition, la balance des blancs
et la mise au point sont verrouillées au départ. Le réticule central doit être amené sur chaque point ; la
photo se déclenche seule quand l'alignement est bon (< 3°) et que le téléphone est immobile. Une flèche
indique le point suivant s'il est hors champ, la mini-carte du bas montre la couverture. La grille
(3 rangées : horizon, +46°, −46° environ, ~30 photos avec le module principal, 18 avec un ultra grand
angle) est calculée depuis le champ de vue réel de la caméra pour garantir ~40 % de recouvrement. « Terminer » permet d'arrêter avant la fin (sphère
incomplète, zones grises).

**Assembler** : lancé automatiquement à la fin de la capture, dans un service de premier plan avec
notification de progression. Comptez une poignée de secondes. La galerie affiche la carte « Assemblage
en cours », puis la sphère avec sa vignette. En cas d'échec, un message explicite (photos qui ne se
recoupent pas, déplacement pendant la capture, mémoire…) et un bouton « Réessayer » ou « Supprimer ».

**Visualiser** : appui sur une vignette. Glisser pour regarder autour, pincer pour zoomer. Appui long
sur une vignette : renommer / déplacer vers un dossier / retoucher / supprimer.

**Ranger par dossier** : menu ⋮ → « Nouveau dossier ». Un dossier correspond à un lieu, une visite.
Ce qui est capturé depuis l'intérieur d'un dossier y atterrit, et un appui long sur une sphère permet
de la déplacer. Les portails ne relient que des sphères d'un même dossier : le viewer ne charge que la
visite courante, et le sélecteur de destination d'un portail ne propose que ses pièces. Déplacer une
sphère supprime donc ses portails, ce que l'app annonce avant de valider en chiffrant ce qui sera perdu.

**Retoucher une sphère** : appui long sur une vignette → « Retoucher l'assemblage », ou le menu ⋮ du
viewer. Les photos de la capture étant conservées, la sphère peut être réassemblée autrement autant de
fois que voulu, sans rien recapturer :

- **Alignement** : capteurs (rapide, aboutit toujours) ou recalage sur les points d'intérêt d'OpenCV,
  qui corrige les erreurs de focale et les petits déplacements mais prend une à deux minutes et
  échoue sur murs unis ou en faible lumière — la sphère actuelle est alors conservée.
- **Résolution** : 4096, 6144 ou 8192 px de large.
- **Raccords** : doux (dilue les écarts de lumière), équilibré (par défaut) ou francs (contours plus
  nets, marche d'exposition possible).
- **Photos utilisées** : toucher une vignette pour écarter une prise floue ou faite en marchant. Ses
  voisines la remplacent, avec un recouvrement moindre mais un résultat propre.

Le résultat remplace la sphère en gardant son nom, son dossier, ses portails et sa vue d'entrée.

**Redresser une sphère** : menu ⋮ du viewer → « Redresser la sphère ». Deux curseurs (inclinaison,
roulis, ±15°) corrigent un horizon penché en direct, sans réassembler l'image : c'est une rotation du
maillage appliquée par le GPU au rendu (`sphereCorrection` de Photo Sphere Viewer). Les portails
suivent le décor — Photo Sphere Viewer place ses marqueurs dans le repère du monde, alors que la
correction fait tourner l'image dessous, donc leurs positions sont recalculées en les rattachant à
leur point d'image.

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
  capture/   CaptureActivity (Camera2 + capteurs, sélecteur d'objectif), CameraController
             (énumération des caméras arrière, intrinsèques, distorsion), OrientationTracker,
             CaptureGrid (grille azimut × élévation depuis le FOV), SphereMath, GuidanceOverlay
  stitch/    StitchService (service de premier plan, file d'attente), SphereStitcher (orchestration),
             FeatureAlignment (briques cv::detail), PanoGeometry (géométrie capteurs),
             LensDistortion (Brown-Conrady, inversion par bissection), ShotView (modèle de caméra),
             EquirectComposer (projection, mélange à deux échelles), EquirectFill (pôles, trous),
             StitchOptions (réglages de retouche), OpenCvRuntime, StitchJobs (état observé)
  gallery/   GalleryActivity (dossiers, sphères, sessions), RetouchActivity (réassemblage paramétré)
  viewer/    ViewerActivity (WebView, édition, portails, assiette), LocalContentServer (origine https locale)
  demo/      DemoTourInstaller (copie assets/demo → galerie)
  data/      Sphere, Portal, Folder, CameraMeta, CaptureSessionMeta (JSON), TourStore (persistance filesDir)
  util/      Haptics, Prefs, Thumbs, Bg, extensions d'insets
app/src/main/assets/viewer/   index.html, viewer.js (glue), vendor/psv-bundle.* (three + Photo Sphere Viewer)
app/src/main/assets/demo/     3 panoramas synthétiques + demo.json (générés par tools/make-demo-panos.py)
web/                          sources du bundle du viewer (npm + esbuild) et test Playwright
tools/                        scripts de build sans SDK manager, keystore debug
tools/desktop/                banc de test de l'assemblage : rendu de captures synthétiques
                              (distorsion comprise), exécution du vrai code, comparaison au panorama
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

### Grand angle et distorsion d'objectif

Un module ultra grand angle est intéressant pour une capture d'intérieur : à 100° de champ en paysage,
la grille tombe de 30 à 18 positions, la capture est plus courte, donc moins exposée à ce que
l'utilisateur se déplace en route, et les rangées inclinées portent au-delà du pôle — plus de calotte
à extrapoler. Mais un tel objectif n'est pas rectilinéaire, et la projection ne peut plus le traiter
comme un sténopé.

**Qui corrige la distorsion.** L'ordre de préférence laisse la main au pilote de la caméra quand il
sait le faire : `DISTORTION_CORRECTION_MODE` est demandé en `FAST` pour la prévisualisation et en
`HIGH_QUALITY` pour les photos, la correction est alors définie exactement contre les intrinsèques
que le pilote publie et elle est appliquée avant compression. On ne reprend le calcul à notre charge
que lorsqu'il déclare une distorsion (`LENS_DISTORTION`) sans proposer de la corriger, ce qui est le
cas courant des modules ultra grand angle. La session enregistre alors les cinq coefficients de
Brown-Conrady, ce qui garantit qu'un réassemblage des mois plus tard décrit les photos avec le même
modèle.

**Où elle s'applique.** Le modèle relie deux pentes de rayon, pas deux positions en pixels : il ne
dépend donc ni de la résolution de travail ni d'une focale corrigée par le recalage. La projection
directe (direction → pixel) sert à remplir les tables de remappage, à mesurer l'empreinte de chaque
photo sur le canevas et à calculer le poids de centrage ; l'inverse sert à parcourir le bord du cadre
et à ramener les points d'intérêt dans un repère sténopé avant les homographies, sans quoi
l'ajustement de faisceau ne converge pas sur un grand angle.

**Inversion.** L'itération par point fixe d'OpenCV, qui divise par le facteur radial, diverge dès que
ce facteur s'éloigne de 1, c'est-à-dire précisément au bord d'un objectif en barillet. La partie
radiale est donc inversée par bissection — la fonction est croissante sur le domaine, ce que le code
vérifie avant d'accepter un modèle — et les deux termes tangentiels sont traités comme une petite
perturbation en quelques passes. Un modèle non monotone avant le bord du cadre, ou incompatible avec
le champ de vue annoncé, est écarté : une projection rectilinéaire approchée vaut mieux qu'un modèle
faux.

**Ce que cela change, mesuré.** Banc de test `tools/desktop`, capture ultra grand angle synthétique
(80° × 96° en portrait, 18 photos, barillet k1 = −0,062 comprimant le coin du cadre de 15 %), face au
panorama de référence :

| Capture | Modèle appliqué | Modèle ignoré |
| --- | --- | --- |
| Rotation pure | 4,4 niveaux d'écart moyen, netteté 0,84 | 6,0 niveaux, netteté 0,80 |
| Tremblements 0,8°, roulis 1,5°, exposition ±3 %, bruit | 5,2 niveaux, netteté 0,81 | 7,0 niveaux, netteté 0,79 |

Soit un quart à un tiers d'erreur en moins, et une sphère grand angle aussi propre qu'une sphère prise
au module principal. L'écart se concentre sur les bords de chaque photo : c'est exactement là que les
photos se raccordent.

Deux garde-fous complètent l'affaire. Le champ de vue vient des intrinsèques mesurées
(`LENS_INTRINSIC_CALIBRATION`, ramenées du tableau de pixels avant correction à la taille de sortie)
quand elles existent, de la géométrie du capteur sinon. Et l'énumération des objectifs écarte les
caméras arrière sans la capacité `BACKWARD_COMPATIBLE` : un capteur de profondeur ou monochrome se
présente comme une caméra arrière et polluerait la liste avec un champ de vue fantaisiste.

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

- **Logique pure** : 44 tests JUnit sur machine virtuelle, sans Android ni OpenCV
  (`tools/run-tests.sh`) : grille de capture et connexité du graphe d'appariement (module principal
  et ultra grand angle), géométrie des capteurs et de la projection équirectangulaire, modèle de
  distorsion (inversion, ordre des coefficients d'Android, rotation du repère avec l'image, rejet
  d'un modèle replié), moyenne de rotations par quaternions, estimation de couverture, remplissage
  des pôles, sérialisation du modèle.
- **Assemblage** : banc de test `tools/desktop` qui rend des captures synthétiques depuis un
  panorama de référence (tremblements, roulis, déplacement parasite, exposition, bruit, flou,
  distorsion d'objectif) et exécute le vrai code d'assemblage avec les bibliothèques OpenCV Linux.
  `compare_pano.py` chiffre l'écart au panorama de référence et la netteté relative des contours.
  Le chemin par défaut aboutit sur les conditions testées, y compris murs quasi unis, capture
  partielle et ultra grand angle, et reste géométriquement exact : sur une capture idéale au module
  principal, 3,9 niveaux de gris d'écart moyen et 0,92 de la netteté de la référence, en 2,3 s pour
  30 photos ; 4,4 niveaux au grand angle avec son modèle de distorsion. Un passage avec
  `System.gc()` en continu vérifie qu'aucun objet natif n'est libéré trop tôt.
- **Viewer** : tests d'intégration Playwright dans Chromium headless (WebGL logiciel) :
  `web/test/viewer.test.mjs` (chargement, libellés, flèches 3D, navigation par portail avec vue
  d'arrivée dans la direction du portail, retour arrière, mode édition, mise à jour de nœud,
  correction d'assiette avec vérification que le portail reste sur le même pixel de l'image) et
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
- **Zénith et nadir.** Avec le module principal, la grille ne monte pas au-delà de 85° environ : il
  reste une calotte au sommet et une au sol, soit à peu près 3 % de la sphère de chaque côté,
  extrapolées par fondu des couleurs de bord, sans détail. Le nadir montre souvent un léger étirement
  vertical. Un objectif d'au moins 85° de champ vertical ferme complètement la sphère, l'inclinaison
  des rangées étant calculée pour porter jusqu'au pôle.
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
  n'est pas le chemin par défaut, et qu'il est proposé dans la retouche plutôt qu'appliqué d'office.
- **Grand angle.** Le modèle de distorsion vient du téléphone. Sur un appareil qui ne publie ni
  `LENS_DISTORTION` ni de mode de correction, les photos du module ultra grand angle sont traitées
  comme rectilinéaires et les bords de chaque photo sont mal placés : les raccords sont alors moins
  nets qu'au module principal, ce que l'écran de choix d'objectif signale. Les objectifs accessibles
  uniquement comme sous-caméras d'une caméra logique (`getPhysicalCameraIds`) ne sont pas proposés :
  beaucoup de pilotes refusent de les ouvrir directement. Le centre optique est supposé au milieu de
  l'image, ce qui est vrai à un ou deux pixels près sur un téléphone. Enfin, au-delà d'environ 130° de
  diagonale un objectif n'est plus décrit correctement par un modèle rectilinéaire à distorsion
  polynomiale : le code écarte alors le modèle, ce qui est plus sûr qu'un résultat replié, mais un tel
  objectif demanderait une projection fisheye dédiée.
- **Dossiers et portails.** Une visite est fermée sur son dossier : un portail ne relie que deux
  sphères du même dossier, et déplacer une sphère supprime ses portails dans les deux sens. Les
  sphères laissées à la racine peuvent se relier entre elles, la racine se comportant comme un
  dossier implicite. Il n'y a pas de sous-dossiers.
- **Retouche.** Le réassemblage repart des photos conservées, réduites à 1280 px de côté : passer la
  sortie à 6144 ou 8192 px agrandit le canevas mais ne fait pas apparaître de détail que la capture
  n'a pas enregistré, et coûte de la mémoire à l'affichage. Le banc de test le chiffre : une sortie
  8192 assemblée depuis ces photos atteint 0,69 de la netteté du panorama de référence en 8192,
  contre 0,92 pour une sortie 4096 comparée au même panorama réduit. Autrement dit, le canevas
  s'agrandit sans que les contours gagnent. Écarter une photo laisse ses voisines couvrir la zone,
  avec un recouvrement moindre : trois exclusions sur trente font tomber la couverture de 97 à 94 %
  de la sphère, au-delà mieux vaut recapturer.
- **Assiette.** Le redressement est une rotation du rendu, bornée à ±15°. Elle ne recrée rien : la
  bande extrapolée du pôle bas remonte dans le champ à mesure qu'on incline, et un horizon penché de
  plus de 15° vient d'une capture à refaire.
- **Stockage.** Les photos réduites sont conservées après l'assemblage pour permettre une retouche
  ultérieure, soit environ 5 à 10 Mo par sphère en plus de l'équirectangulaire. Elles sont supprimées
  avec la sphère, et avec le dossier qui la contient.
- **Résolution.** Équirectangulaire 4096 × 2048 par défaut, compromis entre détail et mémoire GPU des
  WebView mobiles ; jusqu'à 8192 × 4096 via la retouche.
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
