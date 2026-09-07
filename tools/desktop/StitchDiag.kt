package care.primary.sphere360.desktop

import care.primary.sphere360.capture.CaptureGrid
import care.primary.sphere360.data.CaptureSessionMeta
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgcodecs
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.global.opencv_stitching
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.MatVector
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import org.bytedeco.opencv.opencv_core.UMat
import org.bytedeco.opencv.opencv_features2d.ORB
import org.bytedeco.opencv.opencv_features2d.SIFT
import org.bytedeco.opencv.opencv_stitching.BestOf2NearestMatcher
import org.bytedeco.opencv.opencv_stitching.BundleAdjusterRay
import org.bytedeco.opencv.opencv_stitching.BundleAdjusterReproj
import org.bytedeco.opencv.opencv_stitching.CameraParamsVector
import org.bytedeco.opencv.opencv_stitching.HomographyBasedEstimator
import org.bytedeco.opencv.opencv_stitching.ImageFeaturesVector
import org.bytedeco.opencv.opencv_stitching.MatchesInfoVector
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Diagnostic bas niveau du pipeline de recalage : compte les points d'intérêt, les appariements
 * et leur confiance, puis exécute l'estimation et l'ajustement de faisceau séparément, pour
 * savoir précisément quelle étape échoue et pourquoi.
 *
 * Usage : StitchDiag <session> <out ignoré> <natives> [--no-mask] [--orb] [--conf X] [--features N] [--limit N] [--reproj]
 */
object StitchDiag {

    @JvmStatic
    fun main(args: Array<String>) {
        // run-harness.sh passe <session> <out> <natives> : on ignore <out>
        val sessionDir = File(args[0])
        Natives.load(File(args[2]))
        val noMask = args.contains("--no-mask")
        val useOrb = args.contains("--orb")
        val reproj = args.contains("--reproj")
        val conf = args.indexOf("--conf").let { if (it >= 0) args[it + 1].toFloat() else 1.0f }
        val nfeat = args.indexOf("--features").let { if (it >= 0) args[it + 1].toInt() else 1500 }
        val limit = args.indexOf("--limit").let { if (it >= 0) args[it + 1].toInt() else 0 }

        val meta = CaptureSessionMeta.fromJson(JSONObject(File(sessionDir, "meta.json").readText()))
        var shots = meta.shots.filter { File(sessionDir, it.file).exists() }
        if (limit > 0) shots = shots.take(limit)
        println("=== diag : ${shots.size} photos, mask=${!noMask} finder=${if (useOrb) "ORB" else "SIFT($nfeat)"} conf=$conf ba=${if (reproj) "Reproj" else "Ray"}")

        // Chargement + mise à l'échelle de travail, comme cv::Stitcher (registrationResol = 0.6 MP)
        val full = ArrayList<Mat>()
        for (s in shots) {
            val m = opencv_imgcodecs.imread(File(sessionDir, s.file).absolutePath, opencv_imgcodecs.IMREAD_COLOR)
            val longSide = max(m.cols(), m.rows())
            if (longSide > 1280) {
                val k = 1280.0 / longSide
                val r = Mat()
                opencv_imgproc.resize(m, r, Size((m.cols() * k).roundToInt(), (m.rows() * k).roundToInt()), 0.0, 0.0, opencv_imgproc.INTER_AREA)
                m.close(); full.add(r)
            } else full.add(m)
        }
        val area = full[0].cols().toDouble() * full[0].rows()
        val workScale = min(1.0, sqrt(0.6 * 1e6 / area))
        val work = MatVector(full.size.toLong())
        for (i in full.indices) {
            val r = Mat()
            opencv_imgproc.resize(full[i], r, Size(), workScale, workScale, opencv_imgproc.INTER_LINEAR)
            work.put(i.toLong(), r)
        }
        println("image ${full[0].cols()}x${full[0].rows()} -> travail ${work.get(0).cols()}x${work.get(0).rows()} (workScale=%.4f)".format(workScale))

        // 1. Points d'intérêt
        val finder = if (useOrb) ORB.create() else SIFT.create(nfeat, 3, 0.04, 10.0, 1.6, false)
        val features = ImageFeaturesVector()
        val t0 = System.currentTimeMillis()
        opencv_stitching.computeImageFeatures(finder, work, features)
        val kp = (0 until features.size().toInt()).map { features.get(it.toLong()).keypoints().size().toInt() }
        println("points d'intérêt : min=${kp.min()} médiane=${kp.sorted()[kp.size / 2]} max=${kp.max()} (${System.currentTimeMillis() - t0} ms)")
        println("  par photo : " + kp.mapIndexed { i, c -> "$i:$c" }.joinToString(" "))

        // 2. Masque d'appariement issu des capteurs
        val n = shots.size
        val umask = UMat()
        var maskPairs = 0
        if (!noMask) {
            val mask = Mat(n, n, opencv_core.CV_8U, Scalar.all(0.0))
            for (i in 0 until n) for (j in 0 until n) {
                if (i == j) continue
                if (CaptureGrid.overlaps(shots[i].yawDeg, shots[i].pitchDeg, shots[j].yawDeg, shots[j].pitchDeg, meta.camera.hfovDeg, meta.camera.vfovDeg)) {
                    mask.ptr(i).put(j.toLong(), 1.toByte())
                    if (j > i) maskPairs++
                }
            }
            mask.copyTo(umask)
            println("masque : $maskPairs paires autorisées sur ${n * (n - 1) / 2}")
        }

        // 3. Appariement
        val matcher = BestOf2NearestMatcher(false, 0.3f, 6, 6, Double.MAX_VALUE)
        val pairwise = MatchesInfoVector()
        val t1 = System.currentTimeMillis()
        if (noMask) matcher.apply2(features, pairwise) else matcher.apply2(features, pairwise, umask)
        matcher.collectGarbage()
        var good = 0
        var withH = 0
        val confs = ArrayList<Double>()
        for (i in 0 until pairwise.size().toInt()) {
            val m = pairwise.get(i.toLong())
            if (m.src_img_idx() < 0) continue
            if (!m.H().empty()) withH++
            if (m.confidence() > conf) { good++; confs.add(m.confidence()) }
        }
        println("appariements : $withH paires avec homographie, $good au-dessus de la confiance $conf (${System.currentTimeMillis() - t1} ms)")
        if (confs.isNotEmpty()) println("  confiances retenues : min=%.2f médiane=%.2f max=%.2f".format(confs.min(), confs.sorted()[confs.size / 2], confs.max()))

        val names = org.bytedeco.opencv.opencv_core.StringVector()
        for (s in shots) names.push_back(s.file)
        val graph = opencv_stitching.matchesGraphAsString(names, pairwise, conf).string
        println("  graphe : " + graph.lines().count { it.contains("--") } + " arêtes")

        // 4. Composante connexe
        val keep = opencv_stitching.leaveBiggestComponent(features, pairwise, conf)
        val kept = ArrayList<Int>()
        var i = 0L
        while (i < features.size()) { kept.add(keep.get(i)); i++ }
        println("composante retenue : ${kept.size} photos sur $n -> $kept")
        if (kept.size < 2) { println("RÉSULTAT : ERR_NEED_MORE_IMGS"); return }

        // 5. Estimation des rotations
        val cameras = CameraParamsVector()
        val estimator = HomographyBasedEstimator()
        if (!estimator.apply(features, pairwise, cameras)) { println("RÉSULTAT : ERR_HOMOGRAPHY_EST_FAIL"); return }
        val focals = (0 until cameras.size().toInt()).map { cameras.get(it.toLong()).focal() }
        println("estimation OK : ${cameras.size()} caméras, focales min=%.0f médiane=%.0f max=%.0f".format(
            focals.min(), focals.sorted()[focals.size / 2], focals.max()))
        // R doit être en CV_32F pour l'ajustement de faisceau
        for (k in 0 until cameras.size()) {
            val cam = cameras.get(k)
            val r = Mat()
            cam.R().convertTo(r, opencv_core.CV_32F)
            cam.R(r)
        }

        // 6. Ajustement de faisceau
        val ba = if (reproj) BundleAdjusterReproj() else BundleAdjusterRay()
        ba.setConfThresh(conf.toDouble())
        val t2 = System.currentTimeMillis()
        val ok = ba.apply(features, pairwise, cameras)
        println("ajustement de faisceau : " + (if (ok) "OK" else "ÉCHEC") + " (${System.currentTimeMillis() - t2} ms)")
        if (!ok) { println("RÉSULTAT : ERR_CAMERA_PARAMS_ADJUST_FAIL"); return }
        val f2 = (0 until cameras.size().toInt()).map { cameras.get(it.toLong()).focal() }
        println("focales après ajustement : min=%.0f médiane=%.0f max=%.0f".format(f2.min(), f2.sorted()[f2.size / 2], f2.max()))
        println("RÉSULTAT : OK")
    }
}
