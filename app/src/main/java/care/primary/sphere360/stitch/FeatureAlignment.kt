package care.primary.sphere360.stitch

import android.util.Log
import care.primary.sphere360.capture.CaptureGrid
import care.primary.sphere360.data.CaptureSessionMeta
import care.primary.sphere360.data.ShotMeta
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.global.opencv_stitching
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.MatVector
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import org.bytedeco.opencv.opencv_core.UMat
import org.bytedeco.opencv.opencv_features2d.SIFT
import org.bytedeco.opencv.opencv_stitching.BestOf2NearestMatcher
import org.bytedeco.opencv.opencv_stitching.BundleAdjusterRay
import org.bytedeco.opencv.opencv_stitching.CameraParamsVector
import org.bytedeco.opencv.opencv_stitching.HomographyBasedEstimator
import org.bytedeco.opencv.opencv_stitching.ImageFeaturesVector
import org.bytedeco.opencv.opencv_stitching.MatchesInfoVector
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Recalage par points d'intérêt, en assemblant les briques du module stitching d'OpenCV dans
 * l'ordre de son propre exemple `stitching_detailed` : détection SIFT, appariement des deux
 * meilleurs voisins, plus grande composante connexe, estimation par homographies, ajustement de
 * faisceau par rayons, redressement de l'horizon.
 *
 * Piloter ces briques plutôt que la façade `cv::Stitcher` permet deux choses indispensables ici :
 *  - écarter les photos qui ne contiennent presque aucun point d'intérêt (mur uni, plafond). Avec
 *    une seule descripteur, la recherche des deux plus proches voisins de FLANN échoue sur une
 *    assertion et fait tomber tout l'assemblage ;
 *  - ne calculer les points d'intérêt qu'une seule fois, ce qui compte sur un téléphone.
 *
 * On ne demande à OpenCV que les rotations et les focales : la projection dans l'équirectangulaire
 * est faite par EquirectComposer, commune au recalage et aux capteurs.
 */
class FeatureAlignment(private val progress: (StitchJobs.Stage, Int) -> Unit) {

    companion object {
        private const val TAG = "FeatureAlignment"

        /** Résolution de travail pour la détection, en mégapixels (valeur par défaut d'OpenCV). */
        private const val REGISTRATION_RESOL_MP = 0.6

        /** Nombre de points d'intérêt par photo : au-delà, le temps de calcul explose sans gain. */
        private const val MAX_FEATURES = 2500

        /**
         * En dessous de ce nombre de points, une photo est inexploitable pour l'appariement. Le
         * seuil doit rester au moins à 2 : c'est la précondition de la recherche des deux plus
         * proches voisins de FLANN.
         */
        private const val MIN_KEYPOINTS = 12

        private const val MATCH_CONFIDENCE = 1.0f
    }

    class Alignment(val views: List<ShotView>, val totalShots: Int, val droppedForTexture: Int)

    fun align(images: List<Mat>, shots: List<ShotMeta>, meta: CaptureSessionMeta): Alignment {
        if (images.size < 2) throw StitchException(StitchException.Kind.NEED_MORE, "moins de 2 photos")
        val imgW = images[0].cols()
        val imgH = images[0].rows()
        val workScale = min(1.0, sqrt(REGISTRATION_RESOL_MP * 1e6 / (imgW.toDouble() * imgH)))

        val owned = ArrayList<Pointer>()
        fun <T : Pointer> keep(p: T): T { owned.add(p); return p }
        try {
            // ---- 1. Points d'intérêt à la résolution de travail ----
            val work = keep(MatVector(images.size.toLong()))
            for (i in images.indices) {
                val r = Mat()
                opencv_imgproc.resize(images[i], r, Size(), workScale, workScale, opencv_imgproc.INTER_LINEAR)
                work.put(i.toLong(), r)
            }
            val finder = keep(SIFT.create(MAX_FEATURES, 3, 0.03, 10.0, 1.6, false))
            val allFeatures = keep(ImageFeaturesVector())
            opencv_stitching.computeImageFeatures(finder, work, allFeatures)
            progress(StitchJobs.Stage.ALIGN, 18)

            val counts = (0 until allFeatures.size().toInt()).map { allFeatures.get(it.toLong()).keypoints().size().toInt() }
            val usable = counts.indices.filter { counts[it] >= MIN_KEYPOINTS }
            Log.i(TAG, "points d'intérêt : médiane ${counts.sorted()[counts.size / 2]} min ${counts.min()} — " +
                "${usable.size}/${counts.size} photos exploitables")
            if (usable.size < 3) throw StitchException(StitchException.Kind.NEED_MORE, "trop peu de photos texturées")

            // ---- 2. Sous-ensemble exploitable ----
            val features = keep(ImageFeaturesVector(usable.size.toLong()))
            for ((newIndex, oldIndex) in usable.withIndex()) {
                val f = allFeatures.get(oldIndex.toLong())
                f.img_idx(newIndex)
                features.put(newIndex.toLong(), f)
            }
            val subShots = usable.map { shots[it] }

            // ---- 3. Masque d'appariement issu des capteurs ----
            val n = usable.size
            val mask = keep(Mat(n, n, opencv_core.CV_8U, Scalar.all(0.0)))
            var pairs = 0
            for (i in 0 until n) for (j in 0 until n) {
                if (i == j) continue
                if (CaptureGrid.overlaps(subShots[i].yawDeg, subShots[i].pitchDeg, subShots[j].yawDeg, subShots[j].pitchDeg,
                        meta.camera.hfovDeg, meta.camera.vfovDeg)) {
                    mask.ptr(i).put(j.toLong(), 1.toByte())
                    if (j > i) pairs++
                }
            }
            val useMask = pairs >= n
            val umask = keep(UMat())
            if (useMask) mask.copyTo(umask)

            // ---- 4. Appariement ----
            val matcher = keep(BestOf2NearestMatcher(false, 0.3f, 6, 6, Double.MAX_VALUE))
            val pairwise = keep(MatchesInfoVector())
            if (useMask) matcher.apply2(features, pairwise, umask) else matcher.apply2(features, pairwise)
            matcher.collectGarbage()
            progress(StitchJobs.Stage.ALIGN, 24)

            var edges = 0
            for (i in 0 until pairwise.size().toInt()) {
                val m = pairwise.get(i.toLong())
                if (m.src_img_idx() >= 0 && m.dst_img_idx() > m.src_img_idx() && m.confidence() > MATCH_CONFIDENCE) edges++
            }
            Log.i(TAG, "appariement : $edges paires fiables sur $pairs testées ($n photos)")

            // ---- 5. Plus grande composante connexe ----
            val keptIndices = opencv_stitching.leaveBiggestComponent(features, pairwise, MATCH_CONFIDENCE)
            val keptCount = features.size().toInt()
            if (keptCount < 3) throw StitchException(StitchException.Kind.NEED_MORE, "composante connexe de $keptCount photo(s)")
            val originalIndex = IntArray(keptCount) { usable[keptIndices.get(it.toLong())] }

            // ---- 6. Rotations : homographies puis ajustement de faisceau ----
            val cameras = keep(CameraParamsVector())
            if (!keep(HomographyBasedEstimator()).apply(features, pairwise, cameras)) {
                throw StitchException(StitchException.Kind.HOMOGRAPHY, "estimation par homographies impossible")
            }
            for (k in 0 until cameras.size()) {
                val cam = cameras.get(k)
                val r = Mat()
                cam.R().convertTo(r, opencv_core.CV_32F)
                cam.R(r)
            }
            progress(StitchJobs.Stage.ALIGN, 27)
            val adjuster = keep(BundleAdjusterRay())
            adjuster.setConfThresh(MATCH_CONFIDENCE.toDouble())
            if (!adjuster.apply(features, pairwise, cameras)) {
                throw StitchException(StitchException.Kind.ADJUST, "ajustement de faisceau non convergent")
            }

            // ---- 7. Redressement de l'horizon ----
            val rmats = keep(MatVector(cameras.size()))
            for (k in 0 until cameras.size()) {
                val r = Mat()
                cameras.get(k).R().convertTo(r, opencv_core.CV_32F)
                rmats.put(k, r)
            }
            try {
                opencv_stitching.waveCorrect(rmats, opencv_stitching.WAVE_CORRECT_HORIZ)
            } catch (t: Throwable) {
                Log.w(TAG, "redressement de l'horizon ignoré : ${t.message}")
            }

            // ---- 8. Conversion vers notre repère ----
            // OpenCV donne R : caméra → repère panorama (X droite, Y bas, Z avant de la référence).
            // Notre repère est (X est, Y nord, Z haut), donc panorama = M · référence avec
            // M = [[1,0,0],[0,0,-1],[0,1,0]] et la matrice cherchée vaut A = Rᵀ · M.
            val views = ArrayList<ShotView>(keptCount)
            for (k in 0 until keptCount) {
                val cam = cameras.get(k.toLong())
                val r = Mat()
                rmats.get(k.toLong()).convertTo(r, opencv_core.CV_64F)
                val rm = DoubleArray(9)
                val rp = r.ptr()
                for (idx in 0 until 9) rm[idx] = readDouble(rp, idx)
                r.close()
                val a = DoubleArray(9)
                for (row in 0 until 3) {
                    a[row * 3 + 0] = rm[0 * 3 + row]
                    a[row * 3 + 1] = rm[2 * 3 + row]
                    a[row * 3 + 2] = -rm[1 * 3 + row]
                }
                val focal = cam.focal() / workScale
                val index = originalIndex[k]
                // Le centre optique n'est pas raffiné par l'ajustement de faisceau d'OpenCV, qui
                // construit ses matrices avec le centre de l'image : on fait de même.
                views.add(ShotView(shots[index], index, a, focal, imgW / 2.0, imgH / 2.0, focal * cam.aspect()))
            }
            val focals = views.map { it.focal }.sorted()
            Log.i(TAG, "recalage : ${views.size}/${images.size} photos, focale médiane %.0f px (min %.0f max %.0f)"
                .format(focals[focals.size / 2], focals.first(), focals.last()))
            return Alignment(views, images.size, images.size - usable.size)
        } finally {
            for (p in owned.asReversed()) try { p.close() } catch (_: Throwable) {}
        }
    }

    private fun readDouble(p: BytePointer, index: Int): Double {
        var bits = 0L
        val o = index.toLong() * 8
        for (b in 7 downTo 0) bits = (bits shl 8) or (p.get(o + b).toLong() and 0xFF)
        return java.lang.Double.longBitsToDouble(bits)
    }
}
