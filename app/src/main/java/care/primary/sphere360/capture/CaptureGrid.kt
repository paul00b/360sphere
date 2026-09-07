package care.primary.sphere360.capture

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max

/** Une position à photographier (angles relatifs à la direction de départ, en degrés). */
class CaptureTarget(val index: Int, val row: Int, val yawDeg: Double, val pitchDeg: Double)

class CapturePlan(val targets: List<CaptureTarget>, val rowPitches: List<Double>, val hfovDeg: Double, val vfovDeg: Double) {
    val size: Int get() = targets.size
    val middleRowCount: Int get() = targets.count { it.row == 0 }
    /** Nombre minimal de photos pour autoriser une fin anticipée. */
    val minShotsToFinish: Int get() = max(6, middleRowCount * 2 / 3)
}

/**
 * Grille de capture façon Photo Sphere : une rangée à l'horizon et deux rangées inclinées
 * (haut/bas), avec un pas en azimut calculé depuis le champ de vue pour garantir ~40 % de
 * recouvrement horizontal. Les pôles (zénith/nadir) ne sont pas capturés : ils sont
 * complétés par extrapolation à l'assemblage (limitation documentée).
 */
object CaptureGrid {
    const val DEFAULT_OVERLAP = 0.40

    fun build(hfovDeg: Double, vfovDeg: Double, overlap: Double = DEFAULT_OVERLAP): CapturePlan {
        val hf = hfovDeg.coerceIn(30.0, 100.0)
        val vf = vfovDeg.coerceIn(35.0, 110.0)
        val e1 = (vf * 0.70).coerceIn(30.0, 55.0)
        val rows = listOf(0.0, e1, -e1)
        val targets = ArrayList<CaptureTarget>()
        rows.forEachIndexed { ri, pitch ->
            val effectiveWidth = hf / cos(pitch * SphereMath.DEG)
            val n = max(4, ceil(360.0 / (effectiveWidth * (1 - overlap))).toInt())
            val step = 360.0 / n
            val offset = if (ri == 0) 0.0 else step / 2
            for (j in 0 until n) {
                targets.add(CaptureTarget(targets.size, ri, SphereMath.normalizeDeg(j * step + offset), pitch))
            }
        }
        return CapturePlan(targets, rows, hf, vf)
    }

    /**
     * Deux photos se recouvrent-elles assez pour être appariées ? Sert à construire le masque
     * d'appariement d'OpenCV (évite les fausses correspondances entre murs semblables et
     * divise le temps de calcul).
     */
    fun overlaps(yaw1: Double, pitch1: Double, yaw2: Double, pitch2: Double, hfovDeg: Double, vfovDeg: Double): Boolean {
        val dPitch = abs(pitch1 - pitch2)
        if (dPitch > vfovDeg * 0.95) return false
        val meanPitch = (pitch1 + pitch2) / 2
        val dYaw = abs(SphereMath.normalizeDeg(yaw1 - yaw2)) * cos(meanPitch * SphereMath.DEG)
        return dYaw < hfovDeg * 0.95
    }
}
