package care.primary.sphere360.stitch

import android.util.Log
import care.primary.sphere360.data.CaptureSessionMeta
import care.primary.sphere360.data.ShotMeta
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgcodecs
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Size
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class StitchMethod { FEATURES, SENSORS }

/**
 * Choix du chemin d'assemblage.
 *
 * `SENSORS` recompose depuis les orientations mesurées : environ une seconde de calcul par sphère,
 * et cela aboutit toujours. `REFINE` tente d'abord le recalage par points d'intérêt d'OpenCV, qui
 * corrige les erreurs de focale et les petites translations mais coûte une à deux minutes et
 * échoue régulièrement en intérieur ; on retombe alors sur les capteurs.
 */
enum class StitchMode { SENSORS, REFINE }

class StitchResult(
    val width: Int,
    val height: Int,
    val defaultYaw: Double,
    val usedShots: Int,
    val totalShots: Int,
    val method: StitchMethod,
    val coverage: Double
)

class StitchException(val kind: Kind, message: String, val used: Int = 0, val total: Int = 0) : Exception(message) {
    enum class Kind { NEED_MORE, HOMOGRAPHY, ADJUST, PARTIAL, MEMORY, GENERIC }
}

/**
 * Assemble une sphère à partir d'une session de capture.
 *
 * Deux chemins, une seule projection :
 *  1. recalage par points d'intérêt avec le module Stitcher d'OpenCV — meilleure qualité, corrige
 *     les erreurs de focale et les petites translations, mais échoue sur murs unis, faible lumière
 *     ou rotation trop rapide ;
 *  2. recomposition depuis les orientations mesurées par les capteurs — aboutit toujours, avec des
 *     raccords visibles là où l'utilisateur s'est déplacé.
 *
 * Le premier chemin n'est retenu que s'il conserve la grande majorité des photos ; sinon on
 * recompose depuis les capteurs, ce qui vaut mieux qu'une sphère amputée de la moitié de la pièce.
 */
class SphereStitcher(private val progress: (StitchJobs.Stage, Int) -> Unit) {

    companion object {
        private const val TAG = "SphereStitcher"

        /** Largeur de l'équirectangulaire : compromis entre détail et mémoire GPU des WebView mobiles. */
        const val TARGET_WIDTH = 4096

        /**
         * Côté long des photos utilisées pour le calcul.
         *
         * Le recalage n'a pas besoin de la pleine résolution, et la projection n'en a besoin que
         * proportionnellement à la largeur du canevas : une photo portrait de 1280 px de haut
         * couvrant 50° de champ vertical vaut environ 9200 px de haut à l'échelle de la sphère,
         * donc largement de quoi remplir un équirectangulaire de 8192 de large.
         */
        private const val MAX_INPUT_SIDE = 1280

        /**
         * Fraction minimale de photos que le recalage doit raccorder. Les photos écartées sont
         * replacées grâce à leur orientation capteur, donc le seuil peut rester bas.
         */
        private const val MIN_KEPT_RATIO = 0.55

        /**
         * Le recalage doit couvrir au moins cette fraction de ce que couvrent les capteurs pour
         * être préféré. Comparer à la couverture des capteurs plutôt qu'à une valeur absolue rend
         * le critère valable aussi pour une capture volontairement partielle.
         */
        private const val MIN_RELATIVE_COVERAGE = 0.97
    }

    fun stitch(
        sessionDir: File,
        meta: CaptureSessionMeta,
        outEquirect: File,
        outThumb: File,
        options: StitchOptions
    ): StitchResult {
        OpenCvRuntime.ensureLoaded()
        progress(StitchJobs.Stage.LOADING, 1)

        val mode = options.mode
        val shots = meta.shots
            .filter { File(sessionDir, it.file).exists() }
            .filter { it.file !in options.excluded }
        if (shots.size < 2) throw StitchException(StitchException.Kind.NEED_MORE, "moins de 2 photos")
        if (options.excluded.isNotEmpty()) Log.i(TAG, "${options.excluded.size} photo(s) écartée(s) à la demande")

        val images = ArrayList<Mat>()
        val kept = ArrayList<ShotMeta>()
        try {
            for ((i, shot) in shots.withIndex()) {
                val m = load(sessionDir, shot, meta) ?: continue
                if (images.isNotEmpty() && (m.cols() != images[0].cols() || m.rows() != images[0].rows())) {
                    // Tailles hétérogènes : la focale et le centre optique ne seraient plus valables.
                    m.close(); continue
                }
                images.add(m)
                kept.add(shot)
                progress(StitchJobs.Stage.LOADING, 1 + 9 * (i + 1) / shots.size)
            }
            if (images.size < 2) throw StitchException(StitchException.Kind.NEED_MORE, "photos illisibles")

            val imgW = images[0].cols()
            val imgH = images[0].rows()
            val source = object : ImageSource {
                override val size: Int get() = images.size
                override fun get(index: Int): Mat? = images.getOrNull(index)
                override fun release(index: Int) {}
            }

            val sensorFocal = PanoGeometry.focalPxX(meta.camera, imgW)
            val sensorFocalY = PanoGeometry.focalPxY(meta.camera, imgH)
            // Modèle d'objectif de la session : rectilinéaire pour un module principal ou quand le
            // téléphone corrige lui-même, Brown-Conrady pour un grand angle qu'il documente sans
            // corriger. Il ne dépend pas de la résolution : les coefficients portent sur des pentes
            // de rayon, pas sur des pixels.
            val lens = PanoGeometry.distortionOf(meta.camera)
            if (!lens.identity) {
                Log.i(TAG, "distorsion de l'objectif appliquée : k1=%.4f k2=%.4f k3=%.4f p1=%.5f p2=%.5f"
                    .format(lens.k1, lens.k2, lens.k3, lens.p1, lens.p2))
            }
            val sensorViews = buildSensorViews(kept, sensorFocal, sensorFocalY, imgW, imgH, lens)

            var method = StitchMethod.SENSORS
            var views = sensorViews
            if (mode == StitchMode.REFINE) {
                val aligned = tryFeatureAlignment(images, kept, meta, sensorFocal, lens)
                if (aligned != null) {
                    val rebased = rebaseOnSensorFrame(aligned, sensorViews)
                    // Un recalage qui laisserait une partie de la pièce vide est moins bon qu'un
                    // placement capteur : on compare les deux couvertures avant de composer.
                    val featureCoverage = PanoGeometry.estimateCoverage(rebased, imgW, imgH)
                    val sensorCoverage = PanoGeometry.estimateCoverage(sensorViews, imgW, imgH)
                    if (featureCoverage >= sensorCoverage * MIN_RELATIVE_COVERAGE) {
                        views = rebased
                        method = StitchMethod.FEATURES
                    } else {
                        Log.w(TAG, "recalage écarté : couverture %.2f contre %.2f pour les capteurs"
                            .format(featureCoverage, sensorCoverage))
                    }
                }
            }

            progress(StitchJobs.Stage.COMPOSE, 30)
            val canvas = EquirectComposer(progress, options.blend)
                .compose(views, source, options.targetWidth, 30, 85)
            val coverage = canvas.coverage()

            progress(StitchJobs.Stage.FINALIZE, 88)
            EquirectFill.fill(canvas.bgr, canvas.mask, canvas.width, canvas.height)
            write(canvas, outEquirect, outThumb)
            progress(StitchJobs.Stage.FINALIZE, 100)

            Log.i(TAG, "sphère assemblée : ${canvas.width}x${canvas.height} méthode=$method " +
                "photos=${canvas.usedShots}/${shots.size} mélange=${options.blend} couverture=%.2f".format(coverage))
            // Les photos sont conservées réduites : elles permettent de réassembler la sphère plus
            // tard (retouche, recalage) sans occuper la place des originales pleine résolution.
            compactSessionImages(sessionDir, images, kept)
            return StitchResult(canvas.width, canvas.height, 0.0, canvas.usedShots, shots.size, method, coverage)
        } catch (e: OutOfMemoryError) {
            throw StitchException(StitchException.Kind.MEMORY, "mémoire insuffisante")
        } catch (e: StitchException) {
            throw e
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            if (msg.contains("Insufficient memory", true) || msg.contains("bad_alloc", true)) {
                throw StitchException(StitchException.Kind.MEMORY, msg)
            }
            throw StitchException(StitchException.Kind.GENERIC, msg)
        } finally {
            for (m in images) try { m.close() } catch (_: Throwable) {}
        }
    }

    /** Recalage OpenCV, retenu seulement s'il conserve assez de photos et une focale plausible. */
    private fun tryFeatureAlignment(
        images: List<Mat>, shots: List<ShotMeta>, meta: CaptureSessionMeta, sensorFocal: Double,
        lens: LensDistortion
    ): List<ShotView>? {
        return try {
            val alignment = FeatureAlignment(progress).align(images, shots, meta, lens)
            val ratio = alignment.views.size.toDouble() / images.size
            val focal = alignment.views.map { it.focal }.sorted()[alignment.views.size / 2]
            val focalDrift = abs(focal - sensorFocal) / max(focal, sensorFocal)
            when {
                ratio < MIN_KEPT_RATIO -> {
                    Log.w(TAG, "recalage écarté : ${alignment.views.size}/${images.size} photos raccordées")
                    null
                }
                focalDrift > 0.35 -> {
                    Log.w(TAG, "recalage écarté : focale estimée %.0f px contre %.0f px attendue".format(focal, sensorFocal))
                    null
                }
                else -> {
                    Log.i(TAG, "recalage retenu : ${alignment.views.size}/${images.size} photos, focale %.0f px".format(focal))
                    alignment.views
                }
            }
        } catch (e: StitchException) {
            Log.w(TAG, "recalage impossible (${e.kind}) : ${e.message} — recomposition depuis les capteurs")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "recalage planté : ${t.message} — recomposition depuis les capteurs")
            null
        }
    }

    /**
     * Ramène le recalage dans le repère des capteurs, puis complète avec les photos qu'il a
     * écartées (murs unis, plafond).
     *
     * OpenCV construit son repère autour de la photo qu'il choisit comme référence et ne redresse
     * l'horizon qu'en moyennant les orientations des caméras : la sphère peut en sortir inclinée
     * et tournée. Le repère des capteurs, lui, tient sa verticale de la gravité et son azimut zéro
     * de la première photo. On estime donc la rotation entre les deux repères sur les photos
     * présentes des deux côtés, on l'applique au recalage, et les photos manquantes reprennent
     * simplement leur orientation capteur.
     */
    private fun rebaseOnSensorFrame(featureViews: List<ShotView>, sensorViews: List<ShotView>): List<ShotView> {
        val sensorByFile = sensorViews.associateBy { it.shot.file }
        // A_feat = A_sens · Q  =>  Q = A_sensᵀ · A_feat  (repère recalage → repère capteurs)
        val candidates = featureViews.mapNotNull { fv ->
            val sv = sensorByFile[fv.shot.file] ?: return@mapNotNull null
            PanoGeometry.multiply(PanoGeometry.transpose(sv.matrix), fv.matrix)
        }
        if (candidates.isEmpty()) return featureViews
        val q = PanoGeometry.averageRotation(candidates)
        val qT = PanoGeometry.transpose(q)
        val rebased = featureViews.map { fv -> fv.withMatrix(PanoGeometry.multiply(fv.matrix, qT)) }
        val drift = rebased.mapNotNull { rv ->
            sensorByFile[rv.shot.file]?.let { sv -> PanoGeometry.angleBetweenRotationsDeg(rv.matrix, sv.matrix) }
        }
        if (drift.isNotEmpty()) {
            Log.i(TAG, "écart recalage/capteurs : médian %.1f° max %.1f°"
                .format(drift.sorted()[drift.size / 2], drift.max()))
        }
        val placed = rebased.map { it.shot.file }.toSet()
        val missing = sensorViews.filter { it.shot.file !in placed }
        if (missing.isNotEmpty()) Log.i(TAG, "${missing.size} photo(s) placée(s) depuis les capteurs seuls")
        return rebased + missing
    }

    private fun buildSensorViews(shots: List<ShotMeta>, focal: Double, focalY: Double, imgW: Int, imgH: Int,
                                 lens: LensDistortion): List<ShotView> {
        val yaw0 = shots.first().yawDeg
        return shots.mapIndexed { index, s ->
            ShotView(s, index, PanoGeometry.refToCamera(PanoGeometry.deviceRotationOf(s), yaw0), focal,
                imgW / 2.0, imgH / 2.0, focalY, lens)
        }
    }

    /** Réécrit les photos de la session à la résolution réellement utilisée par l'assemblage. */
    private fun compactSessionImages(sessionDir: File, images: List<Mat>, shots: List<ShotMeta>) {
        for (i in images.indices) {
            val file = File(sessionDir, shots[i].file)
            try {
                if (!file.exists() || file.length() < 400_000L) continue
                opencv_imgcodecs.imwrite(file.absolutePath, images[i], intArrayOf(opencv_imgcodecs.IMWRITE_JPEG_QUALITY, 88))
            } catch (t: Throwable) {
                Log.w(TAG, "compactage de ${shots[i].file} impossible : ${t.message}")
            }
        }
    }

    private fun write(canvas: ComposedCanvas, outEquirect: File, outThumb: File) {
        outEquirect.parentFile?.mkdirs()
        val mat = Mat(canvas.height, canvas.width, opencv_core.CV_8UC3)
        try {
            mat.data().put(canvas.bgr, 0, canvas.bgr.size)
            if (!opencv_imgcodecs.imwrite(outEquirect.absolutePath, mat, intArrayOf(opencv_imgcodecs.IMWRITE_JPEG_QUALITY, 88))) {
                throw StitchException(StitchException.Kind.GENERIC, "écriture JPEG impossible")
            }
            val thumb = Mat()
            try {
                opencv_imgproc.resize(mat, thumb, Size(640, 320), 0.0, 0.0, opencv_imgproc.INTER_AREA)
                opencv_imgcodecs.imwrite(outThumb.absolutePath, thumb, intArrayOf(opencv_imgcodecs.IMWRITE_JPEG_QUALITY, 85))
            } finally {
                thumb.close()
            }
        } finally {
            mat.close()
        }
    }

    private fun load(dir: File, shot: ShotMeta, meta: CaptureSessionMeta): Mat? {
        val m = opencv_imgcodecs.imread(File(dir, shot.file).absolutePath, opencv_imgcodecs.IMREAD_COLOR)
        if (m == null || m.empty()) { m?.close(); return null }
        var img = m
        if (img.cols() > img.rows() && meta.camera.width < meta.camera.height) {
            // L'orientation EXIF n'a pas été appliquée par le décodeur : on redresse nous-mêmes.
            val rotated = Mat()
            val code = if (meta.camera.jpegRotation == 270) opencv_core.ROTATE_90_COUNTERCLOCKWISE
            else opencv_core.ROTATE_90_CLOCKWISE
            opencv_core.rotate(img, rotated, code)
            img.close(); img = rotated
        }
        val longSide = max(img.cols(), img.rows())
        if (longSide > MAX_INPUT_SIDE) {
            val s = MAX_INPUT_SIDE.toDouble() / longSide
            val resized = Mat()
            opencv_imgproc.resize(img, resized, Size((img.cols() * s).roundToInt(), (img.rows() * s).roundToInt()),
                0.0, 0.0, opencv_imgproc.INTER_AREA)
            img.close(); img = resized
        }
        return img
    }
}
