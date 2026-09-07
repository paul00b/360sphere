package care.primary.sphere360

import care.primary.sphere360.stitch.LensDistortion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LensDistortionTest {

    /**
     * Un barillet de grand angle plausible, plus deux petits termes tangentiels.
     *
     * Les ordres de grandeur comptent : sur un modèle rapporté à un objectif rectilinéaire de 111°
     * de diagonale, la compression au coin du cadre est d'environ 15 %, pas de 40 %. Un k1 trop
     * gros rend le polynôme non monotone avant le bord du cadre, donc inutilisable.
     */
    private val wide = LensDistortion(-0.062, 0.009, 0.0012, -0.0008, -0.0018)

    /** Champ paysage d'un module ultra grand angle 4:3 : 111° de diagonale. */
    private val wideH = 100.0
    private val wideV = 80.0

    /** Le même modèle borné au cadre, tel que l'assemblage l'utilisera. */
    private val bounded = wide.boundedTo(wideH, wideV)!!

    @Test
    fun undistortInvertsDistort() {
        val out = DoubleArray(2)
        val back = DoubleArray(2)
        var worst = 0.0
        var tested = 0
        for (i in -12..12) for (j in -12..12) {
            val x = i * 0.15
            val y = j * 0.15
            // On reste dans le domaine du modèle : au-delà du coin du cadre, la question n'a pas
            // de sens et la projection déclare la direction hors champ.
            if (!bounded.distort(x, y, out)) continue
            tested++
            bounded.undistort(out[0], out[1], back)
            worst = maxOf(worst, abs(back[0] - x), abs(back[1] - y))
        }
        assertTrue("domaine testé trop petit : $tested points", tested > 300)
        // Une pente de rayon de 1e-7 vaut un dix-millième de pixel sur une focale de 600 px.
        assertTrue("erreur d'inversion $worst", worst < 1e-7)
    }

    @Test
    fun identityLeavesRaysUntouched() {
        val out = DoubleArray(2)
        assertTrue(LensDistortion.NONE.identity)
        assertTrue(LensDistortion.NONE.distort(0.7, -0.3, out))
        assertEquals(0.7, out[0], 0.0)
        assertEquals(-0.3, out[1], 0.0)
    }

    @Test
    fun barrelPullsTheEdgeTowardsTheCentre() {
        val out = DoubleArray(2)
        // Au centre, aucun effet mesurable : la distorsion est nulle sur l'axe optique.
        assertTrue(wide.distort(0.0, 0.0, out))
        assertEquals(0.0, out[0], 1e-12)
        assertEquals(0.0, out[1], 1e-12)

        // Au coin du cadre, l'écart est ce qui justifie tout le modèle. Sur une photo portrait de
        // 960 px de large et 100° de champ diagonal, la focale vaut environ 570 px : 15 % de
        // compression au coin, c'est 150 px de décalage, très loin d'être négligeable.
        val observed = LensDistortion.cornerRadius(wideH, wideV)
        val ideal = wide.idealRadius(observed)
        assertTrue("rayon idéal atteignable : $ideal", ideal.isFinite() && ideal > observed)
        val ratio = observed / ideal
        assertTrue("compression au coin : $ratio", ratio > 0.75 && ratio < 0.92)

        // Le rayon décroît strictement avec la compression : plus loin de l'axe, plus d'écart.
        val half = wide.idealRadius(observed / 2)
        assertTrue((observed / 2) / half > ratio)
    }

    @Test
    fun androidOrderMapsTangentialCoefficientsCorrectly() {
        // LENS_DISTORTION range [κ1, κ2, κ3, κ4, κ5] ; OpenCV attend [k1, k2, p1, p2, k3].
        val android = floatArrayOf(-0.1f, 0.02f, -0.003f, 0.0011f, -0.0007f)
        val d = LensDistortion.fromAndroid(android)
        // Tolérance de flottant simple : les coefficients arrivent en Float côté Android.
        assertEquals(-0.1, d.k1, 1e-7)
        assertEquals(0.02, d.k2, 1e-7)
        assertEquals(-0.003, d.k3, 1e-7)
        assertEquals(0.0011, d.p1, 1e-7)
        assertEquals(-0.0007, d.p2, 1e-7)

        // Le modèle reconstruit doit reproduire exactement la formule publiée par Android.
        val x = 0.6
        val y = -0.35
        val r2 = x * x + y * y
        val radial = 1 + android[0] * r2 + android[1] * r2 * r2 + android[2] * r2 * r2 * r2
        val expectedX = x * radial + android[3] * (2 * x * y) + android[4] * (r2 + 2 * x * x)
        val expectedY = y * radial + android[4] * (2 * x * y) + android[3] * (r2 + 2 * y * y)
        val out = DoubleArray(2)
        assertTrue(d.distort(x, y, out))
        assertEquals(expectedX, out[0], 1e-9)
        assertEquals(expectedY, out[1], 1e-9)
    }

    @Test
    fun quarterTurnCommutesWithTheImageRotation() {
        // Tourner l'image d'un quart de tour horaire envoie (x, y) sur (-y, x). Le modèle tourné
        // doit distordre le point tourné exactement comme le modèle d'origine distord l'original.
        val direct = DoubleArray(2)
        val rotatedModel = wide.rotatedClockwise(90)
        val viaModel = DoubleArray(2)
        for (i in -6..6) for (j in -6..6) {
            val x = i * 0.2
            val y = j * 0.2
            assertTrue(wide.distort(x, y, direct))
            assertTrue(rotatedModel.distort(-y, x, viaModel))
            assertEquals(-direct[1], viaModel[0], 1e-12)
            assertEquals(direct[0], viaModel[1], 1e-12)
        }
        // Quatre quarts de tour ramènent au modèle de départ.
        val full = wide.rotatedClockwise(360)
        assertEquals(wide.p1, full.p1, 1e-12)
        assertEquals(wide.p2, full.p2, 1e-12)
        // 270° horaire est l'inverse de 90°.
        val there = wide.rotatedClockwise(90).rotatedClockwise(270)
        assertEquals(wide.p1, there.p1, 1e-12)
        assertEquals(wide.p2, there.p2, 1e-12)
    }

    @Test
    fun radialTermsSurviveTheRotation() {
        val r = wide.rotatedClockwise(90)
        assertEquals(wide.k1, r.k1, 0.0)
        assertEquals(wide.k2, r.k2, 0.0)
        assertEquals(wide.k3, r.k3, 0.0)
    }

    @Test
    fun boundedToRejectsFoldedModels() {
        // Un modèle plausible est accepté, et son domaine dépasse le rayon observé au coin.
        val ok = wide.boundedTo(wideH, wideV)
        assertTrue(ok != null)
        assertTrue(ok!!.maxRadius > LensDistortion.cornerRadius(wideH, wideV))
        assertTrue(ok.usableUpTo(ok.maxRadius / 1.05))

        // k1 trop négatif : la fonction radiale retombe avant d'atteindre le bord du cadre, donc
        // aucun rayon idéal ne correspond au coin de l'image. Le modèle est incompatible avec le
        // champ de vue annoncé et l'assemblage repasse en projection rectilinéaire.
        assertTrue(LensDistortion(-1.5, 0.0, 0.0, 0.0, 0.0).boundedTo(wideH, wideV) == null)
        assertTrue(LensDistortion(-0.45, 0.0, 0.0, 0.0, 0.0).boundedTo(wideH, wideV) == null)
        // Sans coefficient, il n'y a rien à borner.
        assertTrue(LensDistortion.NONE.boundedTo(wideH, wideV) == null)
        assertTrue(LensDistortion.NONE.usableUpTo(2.0))
    }

    @Test
    fun idealRadiusReportsUnreachableTargets() {
        // Le polynôme plafonne bien avant : aucun rayon idéal ne donne ce rayon observé.
        assertFalse(LensDistortion(-1.5, 0.0, 0.0, 0.0, 0.0).idealRadius(1.4).isFinite())
        // Sur un modèle sain, l'inversion et la projection radiale se répondent.
        val r = bounded.idealRadius(1.2)
        assertTrue(r.isFinite())
        assertEquals(1.2, bounded.radialMap(r), 1e-9)
        assertEquals(0.0, bounded.idealRadius(0.0), 0.0)
    }

    @Test
    fun outOfDomainRaysAreRejected() {
        val out = DoubleArray(2)
        val limited = wide.withMaxRadius(1.5)
        assertTrue(limited.distort(1.0, 0.5, out))
        assertFalse("au-delà du cadre, la direction doit être déclarée hors champ",
            limited.distort(2.0, 1.0, out))
    }

    @Test
    fun coefficientsRoundTrip() {
        val c = wide.coefficients()
        val again = LensDistortion.fromOpenCvOrder(c)
        assertEquals(wide.k1, again.k1, 0.0)
        assertEquals(wide.p1, again.p1, 0.0)
        assertEquals(wide.p2, again.p2, 0.0)
        assertTrue(LensDistortion.fromOpenCvOrder(null).identity)
        assertTrue(LensDistortion.fromOpenCvOrder(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)).identity)
        assertTrue(LensDistortion.fromOpenCvOrder(doubleArrayOf(Double.NaN, 0.0, 0.0, 0.0, 0.0)).identity)
    }
}
