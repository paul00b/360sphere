package care.primary.sphere360

import care.primary.sphere360.capture.SphereMath
import care.primary.sphere360.data.CameraMeta
import care.primary.sphere360.data.ShotMeta
import care.primary.sphere360.stitch.PanoGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt

class PanoGeometryTest {

    private fun camera(hfov: Double, w: Int, h: Int): CameraMeta {
        val f = (w / 2.0) / kotlin.math.tan(hfov * PI / 360.0)
        val vfov = 2 * atan((h / 2.0) / f) * 180 / PI
        return CameraMeta(hfov, vfov, w, h, 90)
    }

    /** Tolérance adaptée à la précision simple des matrices fournies par les capteurs Android. */
    private val eps = 1e-6

    private fun isOrthonormal(m: DoubleArray): Boolean {
        val p = PanoGeometry.multiply(m, PanoGeometry.transpose(m))
        for (i in 0 until 3) for (j in 0 until 3) {
            val expected = if (i == j) 1.0 else 0.0
            if (abs(p[i * 3 + j] - expected) > eps) return false
        }
        return true
    }

    @Test
    fun focalsAgreeForASquarePixelLens() {
        val cam = camera(50.0, 960, 1280)
        val fx = PanoGeometry.focalPxX(cam, 960)
        val fy = PanoGeometry.focalPxY(cam, 1280)
        assertEquals(1029.4, fx, 0.5)
        assertEquals(fx, fy, 0.5)
    }

    @Test
    fun deviceRotationIsOrthonormalAndPointsWhereExpected() {
        for (yaw in listOf(-170.0, -40.0, 0.0, 35.0, 179.0)) for (pitch in listOf(-45.0, 0.0, 46.0)) for (roll in listOf(-12.0, 0.0, 20.0)) {
            val r = PanoGeometry.deviceRotationFrom(yaw, pitch, roll)
            val m = DoubleArray(9) { r[it].toDouble() }
            assertTrue("orthonormale yaw=$yaw pitch=$pitch roll=$roll", isOrthonormal(m))
            // l'axe optique de la caméra arrière doit viser (yaw, pitch)
            val f = SphereMath.cameraForward(r)
            assertEquals(yaw * SphereMath.DEG, SphereMath.normalizeAngle(SphereMath.yawOf(f)), eps)
            assertEquals(pitch * SphereMath.DEG, SphereMath.pitchOf(f), eps)
            // et le roulis mesuré doit être celui demandé
            assertEquals(roll * SphereMath.DEG, SphereMath.rollOf(r), eps)
        }
    }

    @Test
    fun theOpticalAxisLandsAtTheImageCentre() {
        val cam = camera(50.0, 960, 1280)
        val focal = PanoGeometry.focalPxX(cam, 960)
        val focalY = PanoGeometry.focalPxY(cam, 1280)
        val yaw0 = 37.0
        for (yaw in listOf(37.0, 90.0, -120.0)) for (pitch in listOf(0.0, 44.0, -44.0)) {
            val a = PanoGeometry.refToCamera(PanoGeometry.deviceRotationFrom(yaw, pitch, 0.0), yaw0)
            assertTrue(isOrthonormal(a))
            // direction de l'axe optique exprimée dans le repère de référence
            val d = SphereMath.dirFromYawPitch((yaw - yaw0) * SphereMath.DEG, pitch * SphereMath.DEG)
            val c = PanoGeometry.applyMatrix(a, d.x, d.y, d.z)
            assertTrue("axe optique vers l'avant : c=${c.joinToString()}", c[2] > 0.999)
            assertEquals("colonne", 480.0, focal * c[0] / c[2] + 480.0, 1e-3)
            assertEquals("ligne", 640.0, focalY * c[1] / c[2] + 640.0, 1e-3)
        }
    }

    @Test
    fun aPointToTheRightOfTheAxisLandsRightOfCentre() {
        val cam = camera(50.0, 960, 1280)
        val focal = PanoGeometry.focalPxX(cam, 960)
        val a = PanoGeometry.refToCamera(PanoGeometry.deviceRotationFrom(0.0, 0.0, 0.0), 0.0)
        val d = SphereMath.dirFromYawPitch(10 * SphereMath.DEG, 0.0)
        val c = PanoGeometry.applyMatrix(a, d.x, d.y, d.z)
        assertTrue("à droite du centre", focal * c[0] / c[2] > 0)
        val up = SphereMath.dirFromYawPitch(0.0, 10 * SphereMath.DEG)
        val cu = PanoGeometry.applyMatrix(a, up.x, up.y, up.z)
        assertTrue("au-dessus du centre (y croît vers le bas)", cu[1] < 0)
    }

    @Test
    fun canvasAnglesRoundTrip() {
        val w = 4096
        val h = 2048
        // la colonne w/2 est à un demi-pixel du yaw 0, la ligne 0 à un demi-pixel du zénith
        assertEquals(0.0, PanoGeometry.yawOfCol(w / 2, w) - PI / w, 1e-12)
        assertEquals(PI / 2, PanoGeometry.pitchOfRow(0, h) + PI / (2 * h), 1e-12)
        for (col in listOf(0, 1, 1000, 2048, 4095)) {
            assertEquals(col.toDouble(), PanoGeometry.colOfYaw(PanoGeometry.yawOfCol(col, w), w), 1e-6)
        }
        for (row in listOf(0, 5, 1024, 2047)) {
            assertEquals(row.toDouble(), PanoGeometry.rowOfPitch(PanoGeometry.pitchOfRow(row, h), h), 1e-6)
        }
    }

    @Test
    fun quaternionRoundTripAndAveraging() {
        for (yaw in listOf(-150.0, 0.0, 80.0)) for (pitch in listOf(-40.0, 0.0, 40.0)) for (roll in listOf(-20.0, 0.0, 25.0)) {
            val r = PanoGeometry.deviceRotationFrom(yaw, pitch, roll)
            val m = DoubleArray(9) { r[it].toDouble() }
            val back = PanoGeometry.fromQuaternion(PanoGeometry.toQuaternion(m))
            for (i in 0 until 9) assertEquals("yaw=$yaw pitch=$pitch roll=$roll [$i]", m[i], back[i], eps)
            // la conversion passe par les matrices float32 des capteurs : l'écart résiduel se
            // compte en fractions de minute d'angle, sans effet sur le rendu
            assertEquals(0.0, PanoGeometry.angleBetweenRotationsDeg(m, back), 0.02)
        }
        val a = DoubleArray(9) { PanoGeometry.deviceRotationFrom(20.0, 10.0, 5.0)[it].toDouble() }
        val avg = PanoGeometry.averageRotation(listOf(a, a, a))
        assertEquals(0.0, PanoGeometry.angleBetweenRotationsDeg(a, avg), 0.02)
    }

    @Test
    fun averagingTwoCloseRotationsLandsBetweenThem() {
        val a = DoubleArray(9) { PanoGeometry.deviceRotationFrom(0.0, 0.0, 0.0)[it].toDouble() }
        val b = DoubleArray(9) { PanoGeometry.deviceRotationFrom(10.0, 0.0, 0.0)[it].toDouble() }
        val avg = PanoGeometry.averageRotation(listOf(a, b))
        assertEquals(5.0, PanoGeometry.angleBetweenRotationsDeg(a, avg), 0.01)
        assertEquals(5.0, PanoGeometry.angleBetweenRotationsDeg(b, avg), 0.01)
    }

    @Test
    fun rotationsRecoverTheFrameChange() {
        // Q relie deux repères ; on doit le retrouver à partir des couples de matrices.
        val q = DoubleArray(9) { PanoGeometry.deviceRotationFrom(23.0, 7.0, -4.0)[it].toDouble() }
        val samples = listOf(Triple(0.0, 0.0, 0.0), Triple(60.0, 30.0, 5.0), Triple(-90.0, -20.0, 10.0))
        val candidates = samples.map { (y, p, r) ->
            val sens = PanoGeometry.refToCamera(PanoGeometry.deviceRotationFrom(y, p, r), 0.0)
            val feat = PanoGeometry.multiply(sens, q)
            PanoGeometry.multiply(PanoGeometry.transpose(sens), feat)
        }
        assertEquals(0.0, PanoGeometry.angleBetweenRotationsDeg(q, PanoGeometry.averageRotation(candidates)), 0.02)
    }

    @Test
    fun footprintCoversTheAxisAndStaysLocal() {
        val cam = camera(50.0, 960, 1280)
        val focal = PanoGeometry.focalPxX(cam, 960)
        val focalY = PanoGeometry.focalPxY(cam, 1280)
        val w = 2048
        val h = 1024
        val a = PanoGeometry.refToCamera(PanoGeometry.deviceRotationFrom(0.0, 0.0, 0.0), 0.0)
        val fp = PanoGeometry.footprint(a, focal, focalY, 960, 1280, w, h)
        assertTrue("colonne centrale couverte", fp.cols[w / 2])
        // une photo de 50° de large ne doit pas couvrir tout le tour
        assertTrue("empreinte locale : ${fp.colCount}", fp.colCount in (w * 50 / 360)..(w * 90 / 360))
        assertTrue(fp.rowMin < h / 2 && fp.rowMax > h / 2)

        // une photo tournée de 180° couvre la couture sans exploser l'empreinte
        val back = PanoGeometry.footprint(
            PanoGeometry.refToCamera(PanoGeometry.deviceRotationFrom(180.0, 0.0, 0.0), 0.0), focal, focalY, 960, 1280, w, h)
        assertTrue("couture couverte", back.cols[0] && back.cols[w - 1])
        assertTrue("empreinte locale malgré la couture : ${back.colCount}", back.colCount in (w * 50 / 360)..(w * 90 / 360))
        assertTrue("le centre n'est pas couvert", !back.cols[w / 2])

        // une photo visant le zénith couvre toutes les colonnes
        val up = PanoGeometry.footprint(
            PanoGeometry.refToCamera(PanoGeometry.deviceRotationFrom(0.0, 90.0, 0.0), 0.0), focal, focalY, 960, 1280, w, h)
        assertEquals(w, up.colCount)
        assertEquals(0, up.rowMin)
    }

    @Test
    fun featherWeightIsHighestAtTheCentreAndZeroOnTheBorder() {
        val w = 64
        val h = 96
        val weight = PanoGeometry.featherWeight(w, h)
        val centre = weight[(h / 2) * w + w / 2]
        assertEquals(1.0f, centre, 1e-3f)
        assertTrue(weight[0] < 1e-3f)
        assertTrue(weight[(h / 2) * w] < centre)
        // décroissance monotone du centre vers le bord sur une ligne
        var previous = centre
        for (x in w / 2 downTo 0) {
            val v = weight[(h / 2) * w + x]
            assertTrue("monotone en x=$x", v <= previous + 1e-6f)
            previous = v
        }
    }

    @Test
    fun deviceRotationFallbackMatchesStoredMatrix() {
        val stored = PanoGeometry.deviceRotationFrom(42.0, -13.0, 6.0)
        val shot = ShotMeta("a.jpg", 42.0, -13.0, 6.0, 0, stored)
        val withoutMatrix = ShotMeta("a.jpg", 42.0, -13.0, 6.0, 0, null)
        val a = PanoGeometry.deviceRotationOf(shot)
        val b = PanoGeometry.deviceRotationOf(withoutMatrix)
        for (i in 0 until 9) assertEquals(a[i].toDouble(), b[i].toDouble(), eps)
    }

    @Test
    fun coverageEstimateMatchesWhatTheGridCanSee() {
        val cam = camera(50.0, 960, 1280)
        val focal = PanoGeometry.focalPxX(cam, 960)
        val focalY = PanoGeometry.focalPxY(cam, 1280)
        val plan = care.primary.sphere360.capture.CaptureGrid.build(cam.hfovDeg, cam.vfovDeg)
        val views = plan.targets.mapIndexed { i, t ->
            val rot = PanoGeometry.deviceRotationFrom(t.yawDeg, t.pitchDeg, 0.0)
            care.primary.sphere360.stitch.ShotView(
                ShotMeta("s$i.jpg", t.yawDeg, t.pitchDeg, 0.0, i, rot),
                i, PanoGeometry.refToCamera(rot, 0.0), focal, 480.0, 640.0, focalY
            )
        }
        val coverage = PanoGeometry.estimateCoverage(views, 960, 1280)
        // La grille laisse une calotte à chaque pôle. Elle est plus petite que ne le suggère le
        // champ vertical : les coins d'une photo portrait montent à 44,6° + 40,5° de demi-champ
        // diagonal, soit près de 85°, contre 76,5° pour le milieu du bord haut.
        assertEquals(0.97, coverage, 0.02)

        // La seule rangée du milieu ne couvre qu'une bande équatoriale.
        val middle = views.filterIndexed { i, _ -> plan.targets[i].row == 0 }
        val middleCoverage = PanoGeometry.estimateCoverage(middle, 960, 1280)
        assertTrue("bande équatoriale : $middleCoverage", middleCoverage in 0.45..0.60)
        assertTrue(middleCoverage < coverage)
        assertEquals(0.0, PanoGeometry.estimateCoverage(emptyList(), 960, 1280), 0.0)
    }

    @Test
    fun diagonalFovMatchesTheLensGeometry() {
        val expected = 2 * atan(sqrt(
            kotlin.math.tan(50.0 * PI / 360) * kotlin.math.tan(50.0 * PI / 360) +
                kotlin.math.tan(63.74 * PI / 360) * kotlin.math.tan(63.74 * PI / 360))) * 180 / PI
        assertEquals(expected, care.primary.sphere360.capture.CaptureGrid.diagonalFovDeg(50.0, 63.74), 1e-6)
    }
}
