package care.primary.sphere360.stitch

import care.primary.sphere360.capture.CaptureGrid
import care.primary.sphere360.data.CaptureSessionMeta
import org.bytedeco.javacpp.Pointer
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgcodecs
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.global.opencv_stitching
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.MatVector
import org.bytedeco.opencv.opencv_core.Point2f
import org.bytedeco.opencv.opencv_core.Rect
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import org.bytedeco.opencv.opencv_core.UMat
import org.bytedeco.opencv.opencv_features2d.SIFT
import org.bytedeco.opencv.opencv_stitching.SphericalWarper
import org.bytedeco.opencv.opencv_stitching.Stitcher
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class StitchResult(val width: Int, val height: Int, val defaultYaw: Double, val usedShots: Int, val totalShots: Int)

class StitchException(val kind: Kind, message: String, val used: Int = 0, val total: Int = 0) : Exception(message) {
    enum class Kind { NEED_MORE, HOMOGRAPHY, ADJUST, PARTIAL, MEMORY, GENERIC }
}

/**
 * Assemblage d'une sphère avec cv::Stitcher (mode PANORAMA : warper sphérique, ajustement de
 * faisceau par rayons, correction d'ondulation), puis placement exact du résultat sur un canevas
 * équirectangulaire 2:1 et remplissage des zones non couvertes.
 */
class SphereStitcher(private val progress: (StitchJobs.Stage, Int) -> Unit) {

    companion object {
        /** Largeur cible de l'équirectangulaire (texture WebGL sûre sur mobile). */
        const val TARGET_WIDTH = 4096
        private const val MAX_INPUT_SIDE = 1280
        private const val REGISTRATION_RESOL_MP = 0.6
        private const val SEAM_RESOL_MP = 0.1
    }

    fun stitch(sessionDir: File, meta: CaptureSessionMeta, outEquirect: File, outThumb: File, relaxed: Boolean): StitchResult {
        OpenCvRuntime.ensureLoaded()
        progress(StitchJobs.Stage.LOADING, 2)

        val shots = meta.shots.filter { File(sessionDir, it.file).exists() }
        if (shots.size < 2) throw StitchException(StitchException.Kind.NEED_MORE, "moins de 2 photos")

        val images = ArrayList<Mat>()
        val usedShotIdx = ArrayList<Int>()
        try {
            // ---- 1. Chargement (orientation EXIF appliquée par imread) ----
            for ((i, shot) in shots.withIndex()) {
                val m = opencv_imgcodecs.imread(File(sessionDir, shot.file).absolutePath, opencv_imgcodecs.IMREAD_COLOR)
                if (m == null || m.empty()) { m?.close(); continue }
                var img = m
                if (img.cols() > img.rows() && meta.camera.width < meta.camera.height) {
                    // l'orientation JPEG n'a pas été appliquée : on la rétablit nous-mêmes
                    val rotated = Mat()
                    val code = if (meta.camera.jpegRotation == 270) opencv_core.ROTATE_90_COUNTERCLOCKWISE else opencv_core.ROTATE_90_CLOCKWISE
                    opencv_core.rotate(img, rotated, code)
                    img.close(); img = rotated
                }
                val longSide = max(img.cols(), img.rows())
                if (longSide > MAX_INPUT_SIDE) {
                    val s = MAX_INPUT_SIDE.toDouble() / longSide
                    val resized = Mat()
                    opencv_imgproc.resize(img, resized, Size((img.cols() * s).roundToInt(), (img.rows() * s).roundToInt()), 0.0, 0.0, opencv_imgproc.INTER_AREA)
                    img.close(); img = resized
                }
                images.add(img)
                usedShotIdx.add(i)
                progress(StitchJobs.Stage.LOADING, 2 + 10 * (i + 1) / shots.size)
            }
            val n = images.size
            if (n < 2) throw StitchException(StitchException.Kind.NEED_MORE, "photos illisibles")
            val fullW = images[0].cols()
            val fullH = images[0].rows()

            // ---- 2. Masque d'appariement à partir des orientations capteur ----
            val mask = Mat(n, n, opencv_core.CV_8U, Scalar.all(0.0))
            for (i in 0 until n) {
                val si = shots[usedShotIdx[i]]
                for (j in 0 until n) {
                    if (i == j) continue
                    val sj = shots[usedShotIdx[j]]
                    val ok = CaptureGrid.overlaps(si.yawDeg, si.pitchDeg, sj.yawDeg, sj.pitchDeg, meta.camera.hfovDeg, meta.camera.vfovDeg)
                    if (ok) mask.ptr(i).put(j.toLong(), 1.toByte())
                }
            }
            val umask = UMat()
            mask.copyTo(umask)

            // ---- 3. Configuration du Stitcher ----
            val stitcher = Stitcher.create(Stitcher.PANORAMA)
            stitcher.setRegistrationResol(REGISTRATION_RESOL_MP)
            stitcher.setSeamEstimationResol(SEAM_RESOL_MP)
            stitcher.setPanoConfidenceThresh(if (relaxed) 0.6 else 0.85)
            stitcher.setWaveCorrection(true)
            stitcher.setWaveCorrectKind(opencv_stitching.WAVE_CORRECT_HORIZ)
            stitcher.setWarper(SphericalWarper())
            stitcher.setFeaturesFinder(SIFT.create(if (relaxed) 2500 else 1500, 3, 0.04, 10.0, 1.6, false))
            stitcher.setMatchingMask(umask)

            val vec = MatVector(*images.toTypedArray())
            progress(StitchJobs.Stage.ALIGN, 15)

            // ---- 4. Estimation (détection, appariement, ajustement de faisceau) ----
            val status = stitcher.estimateTransform(vec)
            when (status) {
                Stitcher.OK -> {}
                Stitcher.ERR_NEED_MORE_IMGS -> throw StitchException(StitchException.Kind.NEED_MORE, "ERR_NEED_MORE_IMGS")
                Stitcher.ERR_HOMOGRAPHY_EST_FAIL -> throw StitchException(StitchException.Kind.HOMOGRAPHY, "ERR_HOMOGRAPHY_EST_FAIL")
                Stitcher.ERR_CAMERA_PARAMS_ADJUST_FAIL -> throw StitchException(StitchException.Kind.ADJUST, "ERR_CAMERA_PARAMS_ADJUST_FAIL")
                else -> throw StitchException(StitchException.Kind.GENERIC, "status $status")
            }
            progress(StitchJobs.Stage.ALIGN, 60)

            val cams = stitcher.cameras()
            val used = cams.size().toInt()
            if (used < 2) throw StitchException(StitchException.Kind.NEED_MORE, "moins de 2 caméras estimées")
            if (used < max(2, (n * 0.5).roundToInt())) {
                throw StitchException(StitchException.Kind.PARTIAL, "seulement $used/$n photos raccordées", used, n)
            }
            val component = stitcher.component()
            val compIdx = IntArray(used) { component.get(it.toLong()) }

            // ---- 5. Échelle de composition pour viser TARGET_WIDTH ----
            val focals = DoubleArray(used) { cams.get(it.toLong()).focal() }
            val workScale = stitcher.workScale()
            val fMed = EquirectGeometry.medianFocal(focals)
            val fullScale = fMed / workScale                       // px par radian à pleine résolution
            val targetScale = TARGET_WIDTH / (2 * Math.PI)
            val composeScale = min(1.0, targetScale / fullScale)
            val composeResol = composeScale * composeScale * fullW * fullH / 1e6
            stitcher.setCompositingResol(composeResol)

            // Réplique du calcul interne de composePanorama pour connaître le repère du résultat.
            val cs = EquirectGeometry.composeScale(composeResol, fullW, fullH)
            val cwa = (cs / workScale).toFloat()
            val warpedScale = fMed.toFloat() * cwa
            val composed = EquirectGeometry.composedSize(fullW, fullH, cs)
            val warper = SphericalWarper().create(warpedScale)
            val rois = ArrayList<IntRoi>()
            val kMats = ArrayList<Mat>()
            val rMats = ArrayList<Mat>()
            for (i in 0 until used) {
                val cam = cams.get(i.toLong())
                val k = Mat(3, 3, opencv_core.CV_32F, Scalar.all(0.0))
                val kp = k.ptr()
                val f = (cam.focal() * cwa).toFloat()
                putFloat(kp, 0, f); putFloat(kp, 2, (cam.ppx() * cwa).toFloat())
                putFloat(kp, 4, (f * cam.aspect()).toFloat()); putFloat(kp, 5, (cam.ppy() * cwa).toFloat())
                putFloat(kp, 8, 1f)
                val r = Mat()
                cam.R().convertTo(r, opencv_core.CV_32F)
                val roi: Rect = warper.warpRoi(Size(composed[0], composed[1]), k, r)
                rois.add(IntRoi(roi.x(), roi.y(), roi.width(), roi.height()))
                roi.close()
                kMats.add(k); rMats.add(r)
            }
            val union = EquirectGeometry.union(rois)

            // ---- 6. Composition ----
            progress(StitchJobs.Stage.COMPOSE, 65)
            val pano = Mat()
            val st2 = stitcher.composePanorama(pano)
            if (st2 != Stitcher.OK) throw StitchException(StitchException.Kind.GENERIC, "composePanorama $st2")
            val maskU = stitcher.resultMask()
            val panoMask = Mat()
            maskU.copyTo(panoMask)
            progress(StitchJobs.Stage.FINALIZE, 85)

            val geometryTrusted = pano.cols() == union.width && pano.rows() == union.height
            val canvasSize = EquirectGeometry.canvasSize(warpedScale.toDouble())
            val cw = canvasSize[0]
            val ch = canvasSize[1]
            val tlX: Int
            val tlY: Int
            if (geometryTrusted) {
                tlX = union.x; tlY = union.y
            } else {
                // repli : panorama centré (couverture symétrique haut/bas supposée)
                tlX = -pano.cols() / 2; tlY = (ch - pano.rows()) / 2
            }

            val canvas = Mat(ch, cw, opencv_core.CV_8UC3, Scalar.all(0.0))
            val cmask = Mat(ch, cw, opencv_core.CV_8U, Scalar.all(0.0))
            val rowStart = max(0, -tlY)
            val rowEnd = min(pano.rows(), ch - tlY)
            if (rowEnd > rowStart) {
                for (seg in EquirectGeometry.columnSegments(tlX, pano.cols(), cw)) {
                    val srcRect = Rect(seg[0], rowStart, seg[2], rowEnd - rowStart)
                    val dstRect = Rect(seg[1], tlY + rowStart, seg[2], rowEnd - rowStart)
                    val src = Mat(pano, srcRect); val srcM = Mat(panoMask, srcRect)
                    val dst = Mat(canvas, dstRect); val dstM = Mat(cmask, dstRect)
                    src.copyTo(dst, srcM)
                    srcM.copyTo(dstM, srcM)
                    src.close(); srcM.close(); dst.close(); dstM.close(); srcRect.close(); dstRect.close()
                }
            }

            // ---- 7. Remplissage des zones non couvertes ----
            val buf = ByteArray(cw * ch * 3)
            val mbuf = ByteArray(cw * ch)
            canvas.data().get(buf)
            cmask.data().get(mbuf)
            EquirectFill.fill(buf, mbuf, cw, ch)
            canvas.data().put(buf, 0, buf.size)

            // ---- 8. Vue d'entrée : direction de la première photo ----
            var defaultYaw = 0.0
            val firstPos = compIdx.indexOf(0)
            if (geometryTrusted && firstPos >= 0) {
                val pt = warper.warpPoint(Point2f(composed[0] / 2f, composed[1] / 2f), kMats[firstPos], rMats[firstPos])
                val col = EquirectGeometry.canvasCol(pt.x().roundToInt(), cw)
                defaultYaw = EquirectGeometry.yawFromCol(col + 0.5, cw)
                pt.close()
            }

            // ---- 9. Écriture ----
            outEquirect.parentFile?.mkdirs()
            if (!opencv_imgcodecs.imwrite(outEquirect.absolutePath, canvas, intArrayOf(opencv_imgcodecs.IMWRITE_JPEG_QUALITY, 88))) {
                throw StitchException(StitchException.Kind.GENERIC, "écriture JPEG impossible")
            }
            val thumb = Mat()
            opencv_imgproc.resize(canvas, thumb, Size(640, 320), 0.0, 0.0, opencv_imgproc.INTER_AREA)
            opencv_imgcodecs.imwrite(outThumb.absolutePath, thumb, intArrayOf(opencv_imgcodecs.IMWRITE_JPEG_QUALITY, 85))
            progress(StitchJobs.Stage.FINALIZE, 100)

            closeAll(listOf(thumb, canvas, cmask, pano, panoMask, mask, umask, maskU, vec, stitcher, warper, cams) + kMats + rMats)
            return StitchResult(cw, ch, defaultYaw, used, shots.size)
        } catch (e: OutOfMemoryError) {
            throw StitchException(StitchException.Kind.MEMORY, "OOM")
        } catch (e: StitchException) {
            throw e
        } catch (e: Throwable) {
            val msg = e.message ?: e.javaClass.simpleName
            if (msg.contains("Insufficient memory", true) || msg.contains("alloc", true)) throw StitchException(StitchException.Kind.MEMORY, msg)
            throw StitchException(StitchException.Kind.GENERIC, msg)
        } finally {
            images.forEach { try { it.close() } catch (_: Throwable) {} }
        }
    }

    private fun putFloat(p: org.bytedeco.javacpp.BytePointer, index: Int, v: Float) {
        val bits = java.lang.Float.floatToIntBits(v)
        val o = index.toLong() * 4
        p.put(o, (bits and 0xFF).toByte())
        p.put(o + 1, ((bits ushr 8) and 0xFF).toByte())
        p.put(o + 2, ((bits ushr 16) and 0xFF).toByte())
        p.put(o + 3, ((bits ushr 24) and 0xFF).toByte())
    }

    private fun closeAll(ps: List<Pointer>) {
        for (p in ps) try { p.close() } catch (_: Throwable) {}
    }
}
