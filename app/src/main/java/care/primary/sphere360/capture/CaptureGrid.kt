package care.primary.sphere360.capture

import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

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
 * recouvrement horizontal.
 *
 * Le nombre de positions suit le champ de vue de l'objectif : une trentaine avec un module
 * principal (66° en paysage, donc 50° en portrait), une quinzaine avec un ultra grand angle. Un
 * grand angle change aussi la nature du résultat : les rangées inclinées atteignent réellement le
 * zénith et le nadir, là qu'un objectif standard laisse deux calottes à extrapoler.
 */
object CaptureGrid {
    const val DEFAULT_OVERLAP = 0.40

    /** Fraction du champ de vue diagonal en dessous de laquelle deux prises se recouvrent utilement. */
    const val OVERLAP_ANGLE_FACTOR = 0.80

    /** Bornes du champ de vue acceptées : du téléobjectif à l'ultra grand angle. */
    const val MIN_HFOV = 25.0
    const val MAX_HFOV = 140.0
    const val MIN_VFOV = 25.0
    const val MAX_VFOV = 140.0

    fun build(hfovDeg: Double, vfovDeg: Double, overlap: Double = DEFAULT_OVERLAP): CapturePlan {
        val hf = hfovDeg.coerceIn(MIN_HFOV, MAX_HFOV)
        val vf = vfovDeg.coerceIn(MIN_VFOV, MAX_VFOV)
        val e1 = rowPitch(vf)
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
     * d'appariement d'OpenCV : il évite les fausses correspondances entre murs semblables et
     * divise le temps de calcul.
     *
     * Le critère porte sur l'angle entre les axes optiques, comparé au champ de vue diagonal. Un
     * critère séparé en azimut et en élévation exclut à tort les voisins diagonaux et laisse un
     * graphe d'appariement sans cycle, sur lequel l'ajustement de faisceau ne converge pas.
     */
    fun overlaps(yaw1: Double, pitch1: Double, yaw2: Double, pitch2: Double, hfovDeg: Double, vfovDeg: Double): Boolean {
        val angle = axisAngleDeg(yaw1, pitch1, yaw2, pitch2)
        return angle < diagonalFovDeg(hfovDeg, vfovDeg) * OVERLAP_ANGLE_FACTOR
    }

    /** Angle entre les axes optiques de deux prises, en degrés. */
    fun axisAngleDeg(yaw1: Double, pitch1: Double, yaw2: Double, pitch2: Double): Double {
        val a = SphereMath.dirFromYawPitch(yaw1 * SphereMath.DEG, pitch1 * SphereMath.DEG)
        val b = SphereMath.dirFromYawPitch(yaw2 * SphereMath.DEG, pitch2 * SphereMath.DEG)
        return SphereMath.angleBetween(a, b) * SphereMath.RAD
    }

    /**
     * Inclinaison des deux rangées extrêmes, en degrés.
     *
     * Deux exigences se croisent. La rangée doit rester assez proche de l'horizon pour la
     * recouvrir largement, d'où le premier terme, proportionnel au champ vertical. Mais elle doit
     * aussi porter jusqu'au pôle, ce qui demande une inclinaison d'au moins 90° moins la moitié du
     * champ : le second terme borne donc l'inclinaison de façon à ce qu'un objectif suffisamment
     * large ferme complètement la sphère, au lieu de laisser une calotte à extrapoler.
     */
    fun rowPitch(vfovDeg: Double): Double =
        kotlin.math.min(vfovDeg * 0.70, 90.0 - vfovDeg * 0.40).coerceIn(30.0, 62.0)

    /** Champ de vue diagonal d'un objectif rectilinéaire, en degrés. */
    fun diagonalFovDeg(hfovDeg: Double, vfovDeg: Double): Double {
        val th = tan(hfovDeg * SphereMath.DEG / 2)
        val tv = tan(vfovDeg * SphereMath.DEG / 2)
        return 2 * atan(sqrt(th * th + tv * tv)) * SphereMath.RAD
    }
}
