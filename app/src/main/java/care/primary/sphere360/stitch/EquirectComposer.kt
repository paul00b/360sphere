package care.primary.sphere360.stitch

import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Scalar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Canevas équirectangulaire en cours de composition : BGR entrelacé + masque de couverture. */
class ComposedCanvas(val bgr: ByteArray, val mask: ByteArray, val width: Int, val height: Int, val usedShots: Int) {
    /**
     * Part de la sphère effectivement couverte par des photos, en angle solide.
     *
     * Compter les pixels du canevas donnerait une valeur trompeuse : les lignes proches des pôles
     * occupent autant de pixels que celles de l'équateur alors qu'elles représentent une portion
     * de sphère bien plus petite. Une capture normale, qui ne photographie ni le zénith ni le
     * nadir, couvre ainsi environ 94 % de la sphère mais seulement 83 % des pixels.
     */
    fun coverage(): Double {
        var covered = 0.0
        var total = 0.0
        for (row in 0 until height) {
            val w = kotlin.math.cos(PanoGeometry.pitchOfRow(row, height))
            total += w * width
            var n = 0
            val base = row * width
            for (x in 0 until width) if (mask[base + x] != 0.toByte()) n++
            covered += w * n
        }
        return if (total <= 0) 0.0 else covered / total
    }
}

/** Fournit les images à la demande : déjà en mémoire, ou décodées à la volée. */
interface ImageSource {
    val size: Int
    fun get(index: Int): Mat?
    fun release(index: Int)
}

/**
 * Projette un ensemble de photos dans un canevas équirectangulaire à partir de leurs rotations et
 * de leur focale, quelle que soit la provenance de ces paramètres (capteurs ou recalage OpenCV).
 *
 * Les recouvrements sont fondus par un poids en cloche élevé à la puissance 3 : chaque pixel vient
 * pour l'essentiel de la photo qui le regarde le plus au centre, ce qui évite de flouter l'image
 * tout en adoucissant les raccords.
 *
 * Le canevas est traité par bandes de colonnes pour tenir dans la mémoire d'un téléphone.
 */
class EquirectComposer(private val progress: (StitchJobs.Stage, Int) -> Unit) {

    companion object {
        private const val BAND_WIDTH = 1024
        private const val MIN_WEIGHT = 1e-3f
    }

    fun compose(views: List<ShotView>, source: ImageSource, targetWidth: Int, progressFrom: Int, progressTo: Int): ComposedCanvas {
        if (views.isEmpty()) throw StitchException(StitchException.Kind.NEED_MORE, "aucune photo à projeter")
        val canvasW = (targetWidth / 2) * 2
        val canvasH = canvasW / 2
        val bgr = ByteArray(canvasW * canvasH * 3)
        val mask = ByteArray(canvasW * canvasH)

        // Dimensions communes : celles de la première image disponible.
        var imgW = 0
        var imgH = 0
        for (v in views) {
            val m = source.get(v.imageIndex) ?: continue
            imgW = m.cols(); imgH = m.rows()
            source.release(v.imageIndex)
            break
        }
        if (imgW == 0) throw StitchException(StitchException.Kind.NEED_MORE, "aucune photo lisible")

        val prints = views.map { PanoGeometry.footprint(it.matrix, it.focal, it.focalY, imgW, imgH, canvasW, canvasH) }
        val weight = PanoGeometry.featherWeight(imgW, imgH)
        val weightPtr = FloatPointer(weight.size.toLong()).apply { put(weight, 0, weight.size) }
        val weightMat = Mat(imgH, imgW, opencv_core.CV_32F, weightPtr)

        val sinPitch = DoubleArray(canvasH); val cosPitch = DoubleArray(canvasH)
        for (r in 0 until canvasH) {
            val p = PanoGeometry.pitchOfRow(r, canvasH); sinPitch[r] = sin(p); cosPitch[r] = cos(p)
        }
        val sinYaw = DoubleArray(canvasW); val cosYaw = DoubleArray(canvasW)
        for (c in 0 until canvasW) {
            val y = PanoGeometry.yawOfCol(c, canvasW); sinYaw[c] = sin(y); cosYaw[c] = cos(y)
        }

        val projected = BooleanArray(views.size)
        try {
            val bands = (canvasW + BAND_WIDTH - 1) / BAND_WIDTH
            for (band in 0 until bands) {
                val b0 = band * BAND_WIDTH
                val bw = min(BAND_WIDTH, canvasW - b0)
                val sum = FloatArray(bw * canvasH * 3)
                val wsum = FloatArray(bw * canvasH)
                for (i in views.indices) {
                    val fp = prints[i]
                    var c0 = -1
                    var c1 = -1
                    for (c in b0 until b0 + bw) if (fp.cols[c]) { if (c0 < 0) c0 = c; c1 = c }
                    if (c0 < 0) continue
                    val img = source.get(views[i].imageIndex) ?: continue
                    try {
                        if (img.cols() != imgW || img.rows() != imgH) continue
                        accumulate(img, weightMat, views[i], c0, c1, fp.rowMin, fp.rowMax, b0, bw,
                            sinYaw, cosYaw, sinPitch, cosPitch, sum, wsum)
                        projected[i] = true
                    } finally {
                        source.release(views[i].imageIndex)
                    }
                }
                for (r in 0 until canvasH) {
                    val rowOff = r * bw
                    val dstRow = r * canvasW + b0
                    for (x in 0 until bw) {
                        val w = wsum[rowOff + x]
                        if (w <= MIN_WEIGHT) continue
                        val o = (rowOff + x) * 3
                        val d = (dstRow + x) * 3
                        bgr[d] = clamp(sum[o] / w)
                        bgr[d + 1] = clamp(sum[o + 1] / w)
                        bgr[d + 2] = clamp(sum[o + 2] / w)
                        mask[dstRow + x] = 1
                    }
                }
                progress(StitchJobs.Stage.COMPOSE, progressFrom + (progressTo - progressFrom) * (band + 1) / bands)
            }
        } finally {
            weightMat.close(); weightPtr.close()
        }
        val used = projected.count { it }
        if (used == 0) throw StitchException(StitchException.Kind.NEED_MORE, "aucune photo projetable")
        return ComposedCanvas(bgr, mask, canvasW, canvasH, used)
    }

    /** Reprojette une photo dans la sous-région (c0..c1) x (rowMin..rowMax) et cumule avec son poids. */
    private fun accumulate(
        img: Mat, weightMat: Mat, view: ShotView,
        c0: Int, c1: Int, rowMin: Int, rowMax: Int, bandStart: Int, bandWidth: Int,
        sinYaw: DoubleArray, cosYaw: DoubleArray, sinPitch: DoubleArray, cosPitch: DoubleArray,
        sum: FloatArray, wsum: FloatArray
    ) {
        val tw = c1 - c0 + 1
        val th = rowMax - rowMin + 1
        if (tw <= 0 || th <= 0) return
        val n = tw * th
        val mx = FloatArray(n)
        val my = FloatArray(n)
        val a = view.matrix
        val focal = view.focal
        val focalY = view.focalY
        val cx = view.ppx
        val cy = view.ppy
        for (r in 0 until th) {
            val sp = sinPitch[rowMin + r]
            val cp = cosPitch[rowMin + r]
            val base = r * tw
            for (c in 0 until tw) {
                val col = c0 + c
                val dx = sinYaw[col] * cp
                val dy = cosYaw[col] * cp
                val zc = a[6] * dx + a[7] * dy + a[8] * sp
                if (zc <= 1e-9) { mx[base + c] = -2f; my[base + c] = -2f; continue }
                val xc = a[0] * dx + a[1] * dy + a[2] * sp
                val yc = a[3] * dx + a[4] * dy + a[5] * sp
                mx[base + c] = (focal * xc / zc + cx).toFloat()
                my[base + c] = (focalY * yc / zc + cy).toFloat()
            }
        }

        val mxPtr = FloatPointer(n.toLong()); val myPtr = FloatPointer(n.toLong())
        mxPtr.put(mx, 0, n); myPtr.put(my, 0, n)
        val mapX = Mat(th, tw, opencv_core.CV_32F, mxPtr)
        val mapY = Mat(th, tw, opencv_core.CV_32F, myPtr)
        val tilePtr = BytePointer((n * 3).toLong())
        val tile = Mat(th, tw, opencv_core.CV_8UC3, tilePtr)
        val wPtr = FloatPointer(n.toLong())
        val wTile = Mat(th, tw, opencv_core.CV_32F, wPtr)
        try {
            opencv_imgproc.remap(img, tile, mapX, mapY, opencv_imgproc.INTER_LINEAR,
                opencv_core.BORDER_CONSTANT, Scalar.all(0.0))
            opencv_imgproc.remap(weightMat, wTile, mapX, mapY, opencv_imgproc.INTER_LINEAR,
                opencv_core.BORDER_CONSTANT, Scalar.all(0.0))
            val pix = ByteArray(n * 3)
            val wgt = FloatArray(n)
            tilePtr.get(pix, 0, pix.size)
            wPtr.get(wgt, 0, n)
            val colOffset = c0 - bandStart
            for (r in 0 until th) {
                val srcRow = r * tw
                val dstRow = (rowMin + r) * bandWidth + colOffset
                for (c in 0 until tw) {
                    val w = wgt[srcRow + c]
                    if (w <= MIN_WEIGHT) continue
                    val s = (srcRow + c) * 3
                    val d = (dstRow + c) * 3
                    sum[d] += (pix[s].toInt() and 0xFF) * w
                    sum[d + 1] += (pix[s + 1].toInt() and 0xFF) * w
                    sum[d + 2] += (pix[s + 2].toInt() and 0xFF) * w
                    wsum[dstRow + c] += w
                }
            }
        } finally {
            tile.close(); wTile.close(); mapX.close(); mapY.close()
            tilePtr.close(); wPtr.close(); mxPtr.close(); myPtr.close()
        }
    }

    private fun clamp(v: Float): Byte = v.roundToInt().coerceIn(0, 255).toByte()
}
