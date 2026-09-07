package care.primary.sphere360.capture

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Vecteur 3D minimal (double précision). */
class Vec3(val x: Double, val y: Double, val z: Double) {
    fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3): Vec3 = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length(): Double = sqrt(dot(this))
    fun normalized(): Vec3 { val l = length(); return if (l < 1e-12) this else Vec3(x / l, y / l, z / l) }
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    override fun toString() = "(%.3f, %.3f, %.3f)".format(x, y, z)
}

/**
 * Géométrie de la capture.
 *
 * Repère monde des capteurs Android : X vers l'est, Y vers le nord (ou une référence
 * arbitraire pour GAME_ROTATION_VECTOR), Z vers le haut. Repère appareil : X à droite,
 * Y vers le haut de l'écran, Z sortant de l'écran ; la caméra arrière regarde vers -Z.
 *
 * Convention interne : yaw = angle horaire (vu du dessus) à partir de +Y vers +X,
 * pitch = élévation (positif vers le haut).
 */
object SphereMath {
    const val DEG = PI / 180.0
    const val RAD = 180.0 / PI

    fun dirFromYawPitch(yawRad: Double, pitchRad: Double): Vec3 =
        Vec3(sin(yawRad) * cos(pitchRad), cos(yawRad) * cos(pitchRad), sin(pitchRad))

    fun yawOf(d: Vec3): Double = atan2(d.x, d.y)
    fun pitchOf(d: Vec3): Double = asin((d.z / d.length()).coerceIn(-1.0, 1.0))

    fun angleBetween(a: Vec3, b: Vec3): Double =
        acos((a.dot(b) / (a.length() * b.length())).coerceIn(-1.0, 1.0))

    /** Ramène un angle dans (-π, π]. */
    fun normalizeAngle(a: Double): Double {
        var r = a % (2 * PI)
        if (r <= -PI) r += 2 * PI
        if (r > PI) r -= 2 * PI
        return r
    }

    /** Ramène un angle en degrés dans (-180, 180]. */
    fun normalizeDeg(a: Double): Double {
        var r = a % 360.0
        if (r <= -180.0) r += 360.0
        if (r > 180.0) r -= 360.0
        return r
    }

    /** Axe optique de la caméra arrière dans le repère monde (R : appareil → monde, 3x3 ligne par ligne). */
    fun cameraForward(r: FloatArray): Vec3 = Vec3(-r[2].toDouble(), -r[5].toDouble(), -r[8].toDouble())

    /** Direction du haut de l'écran dans le repère monde. */
    fun deviceUp(r: FloatArray): Vec3 = Vec3(r[1].toDouble(), r[4].toDouble(), r[7].toDouble())

    /** Passe un vecteur du repère monde au repère appareil (multiplication par Rᵀ). */
    fun worldToDevice(r: FloatArray, d: Vec3): Vec3 = Vec3(
        r[0] * d.x + r[3] * d.y + r[6] * d.z,
        r[1] * d.x + r[4] * d.y + r[7] * d.z,
        r[2] * d.x + r[5] * d.y + r[8] * d.z
    )

    /** Roulis autour de l'axe optique (0 = haut du téléphone vers le ciel, positif = penché vers la droite). */
    fun rollOf(r: FloatArray): Double {
        val f = cameraForward(r).normalized()
        val up = deviceUp(r)
        val worldUp = Vec3(0.0, 0.0, 1.0)
        val up0 = (worldUp - f * worldUp.dot(f)).normalized()
        if (up0.length() < 1e-6) return 0.0
        val right0 = f.cross(up0)
        return atan2(up.dot(right0), up.dot(up0))
    }

    /**
     * Projette une direction monde dans le plan image de la caméra (x à droite, y vers le bas,
     * en unités de focale). null si la direction est derrière la caméra.
     */
    fun projectToCamera(r: FloatArray, dWorld: Vec3): DoubleArray? {
        val d = worldToDevice(r, dWorld)
        if (d.z >= -1e-6) return null
        return doubleArrayOf(d.x / -d.z, d.y / d.z)
    }
}
