package care.primary.sphere360.stitch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class IntRoi(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    override fun toString() = "[$x,$y ${width}x$height]"
}

/**
 * Géométrie du panorama sphérique produit par OpenCV et de son placement sur un canevas
 * équirectangulaire complet (360° x 180°).
 *
 * Le warper sphérique d'OpenCV projette une direction en (u, v) = (scale·θ, scale·(π/2 − élévation)) :
 * la ligne u = 0 est la direction de référence et v = 0 le zénith. Le panorama assemblé est le
 * rectangle englobant des images projetées ; connaître son coin haut-gauche suffit donc à le poser
 * sur un canevas de largeur 2π·scale et de hauteur π·scale.
 */
object EquirectGeometry {

    fun union(rois: List<IntRoi>): IntRoi {
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        for (r in rois) {
            x0 = min(x0, r.x); y0 = min(y0, r.y); x1 = max(x1, r.right); y1 = max(y1, r.bottom)
        }
        return IntRoi(x0, y0, x1 - x0, y1 - y0)
    }

    /** Taille du canevas équirectangulaire (largeur paire, hauteur = largeur/2). */
    fun canvasSize(scale: Double): IntArray {
        var w = (2 * PI * scale).roundToInt()
        if (w % 2 == 1) w++
        w = max(w, 16)
        return intArrayOf(w, w / 2)
    }

    /** Colonne du canevas correspondant à l'abscisse u du plan warpé (u = 0 → centre de l'image). */
    fun canvasCol(u: Int, width: Int): Int = ((u + width / 2) % width + width) % width

    /** Yaw (convention Photo Sphere Viewer : 0 au centre de l'image, croissant vers la droite) d'une colonne. */
    fun yawFromCol(col: Double, width: Int): Double {
        var yaw = (col / width - 0.5) * 2 * PI
        if (yaw > PI) yaw -= 2 * PI
        if (yaw < -PI) yaw += 2 * PI
        return yaw
    }

    /** Même règle que cv::Stitcher::composePanorama. */
    fun composeScale(composeResolMp: Double, width: Int, height: Int): Double =
        if (composeResolMp > 0) min(1.0, sqrt(composeResolMp * 1e6 / (width.toDouble() * height))) else 1.0

    /** Même règle que cv::Stitcher::estimateCameraParams (médiane des focales). */
    fun medianFocal(focals: DoubleArray): Double {
        val s = focals.sortedArray()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) * 0.5
    }

    /** Taille des images au moment de la composition (OpenCV ne redimensionne pas si l'échelle est proche de 1). */
    fun composedSize(width: Int, height: Int, composeScale: Double): IntArray =
        if (abs(composeScale - 1) > 1e-1) intArrayOf((width * composeScale).roundToInt(), (height * composeScale).roundToInt())
        else intArrayOf(width, height)

    /**
     * Découpe les `count` colonnes d'un panorama commençant à l'abscisse `startU` en segments
     * contigus sur le canevas (gestion du passage ±180°). Chaque segment : [offsetSource, colonneCanevas, longueur].
     */
    fun columnSegments(startU: Int, count: Int, width: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        var x = 0
        while (x < count) {
            val c = canvasCol(startU + x, width)
            val len = min(count - x, width - c)
            out.add(intArrayOf(x, c, len))
            x += len
        }
        return out
    }
}
