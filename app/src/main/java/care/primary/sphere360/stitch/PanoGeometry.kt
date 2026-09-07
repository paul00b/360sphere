package care.primary.sphere360.stitch

import care.primary.sphere360.data.CameraMeta
import care.primary.sphere360.data.ShotMeta
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Géométrie de la recomposition sphérique guidée par les capteurs.
 *
 * Repères :
 *  - monde (capteurs Android) : X est, Y nord, Z haut ;
 *  - appareil : X droite, Y haut de l'écran, Z sortant de l'écran ; la matrice des capteurs
 *    (`ShotMeta.rotation`, 3x3 ligne par ligne) va de l'appareil vers le monde ;
 *  - caméra (convention image) : X droite, Y bas, Z axe optique. Le passage appareil → caméra est
 *    la diagonale (1, -1, -1) : la photo redressée a son axe X sur la droite de l'appareil et son
 *    axe Y vers le bas de l'écran ;
 *  - référence : le monde tourné pour que l'azimut de la première photo soit à yaw 0, ce qui place
 *    le centre de l'équirectangulaire sur la direction de départ de la capture.
 *
 * Convention équirectangulaire (identique à Photo Sphere Viewer) : la colonne W/2 est yaw 0,
 * yaw croît vers la droite, la ligne 0 est le zénith.
 */
object PanoGeometry {

    /** Focale horizontale en pixels déduite du champ de vue. */
    fun focalPxX(camera: CameraMeta, width: Int): Double = (width / 2.0) / tan(camera.hfovDeg * PI / 360.0)

    /**
     * Focale verticale en pixels. Sur un objectif rectilinéaire à pixels carrés elle est égale à
     * l'horizontale ; on garde les deux séparément pour absorber un champ de vue mal renseigné par
     * le pilote de la caméra, qui décalerait sinon les bords de chaque photo.
     */
    fun focalPxY(camera: CameraMeta, height: Int): Double = (height / 2.0) / tan(camera.vfovDeg * PI / 360.0)

    /** Matrice de rotation appareil → monde, reconstruite depuis yaw/pitch/roll (sessions anciennes). */
    fun deviceRotationFrom(yawDeg: Double, pitchDeg: Double, rollDeg: Double): FloatArray {
        val yaw = yawDeg * PI / 180
        val pitch = pitchDeg * PI / 180
        val roll = rollDeg * PI / 180
        val f = doubleArrayOf(sin(yaw) * cos(pitch), cos(yaw) * cos(pitch), sin(pitch))
        val right0 = doubleArrayOf(cos(yaw), -sin(yaw), 0.0)
        val up0 = doubleArrayOf(-sin(yaw) * sin(pitch), -cos(yaw) * sin(pitch), cos(pitch))
        val cr = cos(roll)
        val sr = sin(roll)
        // inverse exacte de SphereMath.rollOf
        val right = DoubleArray(3) { right0[it] * cr - up0[it] * sr }
        val up = DoubleArray(3) { up0[it] * cr + right0[it] * sr }
        // colonnes = (droite, haut de l'écran, -avant)
        return floatArrayOf(
            right[0].toFloat(), up[0].toFloat(), (-f[0]).toFloat(),
            right[1].toFloat(), up[1].toFloat(), (-f[1]).toFloat(),
            right[2].toFloat(), up[2].toFloat(), (-f[2]).toFloat()
        )
    }

    fun deviceRotationOf(shot: ShotMeta): FloatArray =
        shot.rotation?.takeIf { it.size == 9 } ?: deviceRotationFrom(shot.yawDeg, shot.pitchDeg, shot.rollDeg)

    /**
     * Matrice qui envoie une direction du repère de référence vers le repère caméra de la photo :
     * A = D · R_devᵀ · Rref, avec D = diag(1, -1, -1).
     * `yaw0Deg` est l'azimut monde qui doit correspondre au centre de l'équirectangulaire.
     */
    fun refToCamera(deviceRotation: FloatArray, yaw0Deg: Double): DoubleArray {
        val c = cos(yaw0Deg * PI / 180)
        val s = sin(yaw0Deg * PI / 180)
        // Rref : repère de référence → monde
        val ref = doubleArrayOf(c, s, 0.0, -s, c, 0.0, 0.0, 0.0, 1.0)
        val out = DoubleArray(9)
        for (row in 0 until 3) {
            val sign = if (row == 0) 1.0 else -1.0
            for (col in 0 until 3) {
                var acc = 0.0
                // R_devᵀ[row][k] = deviceRotation[k*3 + row]
                for (k in 0 until 3) acc += deviceRotation[k * 3 + row] * ref[k * 3 + col]
                out[row * 3 + col] = sign * acc
            }
        }
        return out
    }

    /** Direction (repère de référence) vers le repère caméra. */
    fun applyMatrix(m: DoubleArray, x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
        m[0] * x + m[1] * y + m[2] * z,
        m[3] * x + m[4] * y + m[5] * z,
        m[6] * x + m[7] * y + m[8] * z
    )

    /** Transposée (repère caméra vers repère de référence : les matrices sont orthonormales). */
    fun applyTranspose(m: DoubleArray, x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
        m[0] * x + m[3] * y + m[6] * z,
        m[1] * x + m[4] * y + m[7] * z,
        m[2] * x + m[5] * y + m[8] * z
    )

    fun yawOfCol(col: Int, width: Int): Double = ((col + 0.5) / width - 0.5) * 2 * PI
    fun pitchOfRow(row: Int, height: Int): Double = PI / 2 - (row + 0.5) / height * PI
    fun colOfYaw(yaw: Double, width: Int): Double = (yaw / (2 * PI) + 0.5) * width - 0.5
    fun rowOfPitch(pitch: Double, height: Int): Double = (PI / 2 - pitch) / PI * height - 0.5

    /**
     * Empreinte d'une photo sur le canevas équirectangulaire : colonnes couvertes (avec passage
     * ±180°) et intervalle de lignes. `a` est la matrice référence → caméra.
     */
    /**
     * Empreinte d'une photo sur le canevas.
     *
     * @param cols colonnes du canevas couvertes, indexées de 0 à la largeur, passage ±180° résolu
     * @param colStart première colonne en numérotation continue : elle peut être négative ou
     *   dépasser la largeur du canevas, ce qui permet de traiter l'empreinte comme un rectangle
     *   même lorsqu'elle chevauche la couture
     * @param colSpan nombre de colonnes de ce rectangle
     */
    class Footprint(
        val cols: BooleanArray,
        val rowMin: Int,
        val rowMax: Int,
        val colCount: Int,
        val colStart: Int,
        val colSpan: Int
    )

    fun footprint(a: DoubleArray, focal: Double, focalY: Double, imgW: Int, imgH: Int, canvasW: Int, canvasH: Int, marginPx: Double = 1.5): Footprint {
        val cx = imgW / 2.0
        val cy = imgH / 2.0
        val samples = 48
        var yawCenter = 0.0
        run {
            val d = applyTranspose(a, 0.0, 0.0, 1.0)
            yawCenter = atan2(d[0], d[1])
        }
        var minRel = Double.MAX_VALUE
        var maxRel = -Double.MAX_VALUE
        var pitchMin = Double.MAX_VALUE
        var pitchMax = -Double.MAX_VALUE
        // parcours du bord de l'image (élargi de la marge) : l'empreinte est délimitée par ses bords
        for (i in 0 until samples) {
            val t = i.toDouble() / samples
            val border = listOf(
                doubleArrayOf(-marginPx + t * (imgW + 2 * marginPx), -marginPx),
                doubleArrayOf(-marginPx + t * (imgW + 2 * marginPx), imgH + marginPx),
                doubleArrayOf(-marginPx, -marginPx + t * (imgH + 2 * marginPx)),
                doubleArrayOf(imgW + marginPx, -marginPx + t * (imgH + 2 * marginPx))
            )
            for (p in border) {
                val d = applyTranspose(a, (p[0] - cx) / focal, (p[1] - cy) / focalY, 1.0)
                val len = kotlin.math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
                val yaw = atan2(d[0], d[1])
                val pitch = asin((d[2] / len).coerceIn(-1.0, 1.0))
                var rel = yaw - yawCenter
                while (rel > PI) rel -= 2 * PI
                while (rel < -PI) rel += 2 * PI
                minRel = min(minRel, rel); maxRel = max(maxRel, rel)
                pitchMin = min(pitchMin, pitch); pitchMax = max(pitchMax, pitch)
            }
        }
        // un pôle dans le champ élargit l'empreinte à toutes les colonnes
        val poleInside = polesInside(a, focal, focalY, imgW, imgH, marginPx)
        val cols = BooleanArray(canvasW)
        var count = 0
        var colStart = 0
        var colSpan = canvasW
        if (poleInside) {
            cols.fill(true); count = canvasW
            if (pitchMax > 0) pitchMax = PI / 2 else pitchMin = -PI / 2
        } else {
            val c0 = kotlin.math.floor(colOfYaw(yawCenter + minRel, canvasW)).toInt() - 1
            val c1 = kotlin.math.ceil(colOfYaw(yawCenter + maxRel, canvasW)).toInt() + 1
            colStart = c0
            colSpan = min(canvasW, c1 - c0 + 1)
            var c = c0
            while (c <= c1) {
                val idx = ((c % canvasW) + canvasW) % canvasW
                if (!cols[idx]) { cols[idx] = true; count++ }
                c++
            }
        }
        val rowMin = kotlin.math.floor(rowOfPitch(pitchMax, canvasH)).toInt().coerceIn(0, canvasH - 1)
        val rowMax = kotlin.math.ceil(rowOfPitch(pitchMin, canvasH)).toInt().coerceIn(0, canvasH - 1)
        return Footprint(cols, min(rowMin, rowMax), max(rowMin, rowMax), count, colStart, colSpan)
    }

    /** Le zénith ou le nadir tombe-t-il dans le champ de la photo ? */
    private fun polesInside(a: DoubleArray, focal: Double, focalY: Double, imgW: Int, imgH: Int, marginPx: Double): Boolean {
        for (z in listOf(1.0, -1.0)) {
            val c = applyMatrix(a, 0.0, 0.0, z)
            if (c[2] <= 1e-9) continue
            val x = focal * c[0] / c[2] + imgW / 2.0
            val y = focalY * c[1] / c[2] + imgH / 2.0
            if (x >= -marginPx && x <= imgW + marginPx && y >= -marginPx && y <= imgH + marginPx) return true
        }
        return false
    }

    /**
     * Poids de sélection d'un pixel dans sa photo : 1 au centre, 0 sur le bord, décroissant
     * strictement entre les deux. C'est ce poids qui désigne, pour chaque pixel du canevas, la
     * photo qui le regarde le plus au centre.
     *
     * La décroissance doit être stricte : un plateau à 1 sur la zone centrale rendrait plusieurs
     * photos à égalité sur de larges recouvrements, et les moyenner produirait le dédoublement
     * translucide que la composition cherche justement à éviter. La distance retenue est la plus
     * grande des deux écarts normalisés, ce qui découpe la sphère en cellules rectangulaires dont
     * les frontières passent à mi-chemin entre les centres des photos voisines.
     */
    fun centreWeight(imgW: Int, imgH: Int): FloatArray {
        val w = FloatArray(imgW * imgH)
        val dx = DoubleArray(imgW) { kotlin.math.abs((it + 0.5) / imgW * 2 - 1) }
        val dy = DoubleArray(imgH) { kotlin.math.abs((it + 0.5) / imgH * 2 - 1) }
        for (y in 0 until imgH) for (x in 0 until imgW) {
            w[y * imgW + x] = (1.0 - max(dx[x], dy[y])).toFloat().coerceIn(0f, 1f)
        }
        return w
    }

    private fun smooth(t: Double): Double = t * t * (3 - 2 * t)

    /**
     * Part de la sphère que couvrirait un jeu de vues, en angle solide, estimée sur une grille
     * grossière. Sert à comparer deux placements (recalage contre capteurs) sans avoir à composer
     * réellement les deux canevas.
     */
    fun estimateCoverage(views: List<ShotView>, imgW: Int, imgH: Int, gridW: Int = 256): Double {
        if (views.isEmpty()) return 0.0
        val gridH = gridW / 2
        var covered = 0.0
        var total = 0.0
        val sinY = DoubleArray(gridW); val cosY = DoubleArray(gridW)
        for (c in 0 until gridW) { val y = yawOfCol(c, gridW); sinY[c] = sin(y); cosY[c] = cos(y) }
        for (row in 0 until gridH) {
            val pitch = pitchOfRow(row, gridH)
            val weight = cos(pitch)
            val sp = sin(pitch)
            val cp = cos(pitch)
            total += weight * gridW
            for (col in 0 until gridW) {
                val dx = sinY[col] * cp
                val dy = cosY[col] * cp
                var hit = false
                for (v in views) {
                    val a = v.matrix
                    val zc = a[6] * dx + a[7] * dy + a[8] * sp
                    if (zc <= 1e-9) continue
                    val x = v.focal * (a[0] * dx + a[1] * dy + a[2] * sp) / zc + v.ppx
                    if (x < 0 || x > imgW - 1) continue
                    val y = v.focalY * (a[3] * dx + a[4] * dy + a[5] * sp) / zc + v.ppy
                    if (y < 0 || y > imgH - 1) continue
                    hit = true
                    break
                }
                if (hit) covered += weight
            }
        }
        return if (total <= 0) 0.0 else covered / total
    }

    /** Angle de la rotation qui mène d'une orientation à l'autre, en degrés. */
    fun angleBetweenRotationsDeg(a: DoubleArray, b: DoubleArray): Double {
        val m = multiply(a, transpose(b))
        val trace = (m[0] + m[4] + m[8]).coerceIn(-1.0, 3.0)
        return kotlin.math.acos(((trace - 1) / 2).coerceIn(-1.0, 1.0)) * 180.0 / PI
    }

    /** Produit de deux matrices 3x3 stockées ligne par ligne. */
    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (row in 0 until 3) for (col in 0 until 3) {
            var acc = 0.0
            for (k in 0 until 3) acc += a[row * 3 + k] * b[k * 3 + col]
            out[row * 3 + col] = acc
        }
        return out
    }

    fun transpose(m: DoubleArray): DoubleArray = doubleArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

    /** Quaternion (w, x, y, z) d'une matrice de rotation, par la méthode de Shepperd (stable). */
    fun toQuaternion(m: DoubleArray): DoubleArray {
        val trace = m[0] + m[4] + m[8]
        return when {
            trace > 0 -> {
                val s = kotlin.math.sqrt(trace + 1.0) * 2
                doubleArrayOf(0.25 * s, (m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s)
            }
            m[0] > m[4] && m[0] > m[8] -> {
                val s = kotlin.math.sqrt(1.0 + m[0] - m[4] - m[8]) * 2
                doubleArrayOf((m[7] - m[5]) / s, 0.25 * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s)
            }
            m[4] > m[8] -> {
                val s = kotlin.math.sqrt(1.0 + m[4] - m[0] - m[8]) * 2
                doubleArrayOf((m[2] - m[6]) / s, (m[1] + m[3]) / s, 0.25 * s, (m[5] + m[7]) / s)
            }
            else -> {
                val s = kotlin.math.sqrt(1.0 + m[8] - m[0] - m[4]) * 2
                doubleArrayOf((m[3] - m[1]) / s, (m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25 * s)
            }
        }
    }

    fun fromQuaternion(q: DoubleArray): DoubleArray {
        val n = kotlin.math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (n < 1e-12) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val w = q[0] / n; val x = q[1] / n; val y = q[2] / n; val z = q[3] / n
        return doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
            2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
            2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)
        )
    }

    /**
     * Rotation moyenne d'un ensemble de rotations proches : moyenne des quaternions après
     * alignement des signes (un quaternion et son opposé décrivent la même rotation).
     */
    fun averageRotation(matrices: List<DoubleArray>): DoubleArray {
        if (matrices.isEmpty()) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val first = toQuaternion(matrices[0])
        val acc = DoubleArray(4)
        for (m in matrices) {
            val q = toQuaternion(m)
            val dot = q[0] * first[0] + q[1] * first[1] + q[2] * first[2] + q[3] * first[3]
            val sign = if (dot < 0) -1.0 else 1.0
            for (i in 0 until 4) acc[i] += sign * q[i]
        }
        return fromQuaternion(acc)
    }
}
