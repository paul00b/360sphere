package care.primary.sphere360

import care.primary.sphere360.capture.CaptureGrid
import care.primary.sphere360.capture.SphereMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CaptureGridTest {
    @Test
    fun typicalPhoneGivesThreeRowsAndAboutThirtyShots() {
        val plan = CaptureGrid.build(50.0, 66.0)
        assertEquals(3, plan.rowPitches.size)
        assertEquals(12, plan.middleRowCount)
        assertTrue("total=${plan.size}", plan.size in 26..34)
        assertTrue(plan.targets.all { it.yawDeg > -180.0 && it.yawDeg <= 180.0 })
        assertTrue(plan.targets.all { abs(it.pitchDeg) <= 55.0 })
        assertEquals(plan.size, plan.targets.map { it.index }.distinct().size)
        // couverture verticale : rangée haute + demi champ ≥ 75°
        assertTrue(plan.rowPitches.maxOrNull()!! + 66.0 / 2 >= 75.0)
    }

    @Test
    fun rowsAreStaggeredAndEvenlySpaced() {
        val plan = CaptureGrid.build(50.0, 66.0)
        val row0 = plan.targets.filter { it.row == 0 }.map { it.yawDeg }.sorted()
        val step = row0[1] - row0[0]
        for (i in 1 until row0.size) assertEquals(step, row0[i] - row0[i - 1], 1e-6)
        val row1 = plan.targets.filter { it.row == 1 }.map { it.yawDeg }.sorted()
        val step1 = row1[1] - row1[0]
        for (i in 1 until row1.size) assertEquals(step1, row1[i] - row1[i - 1], 1e-6)
        // rangée inclinée décalée d'un demi-pas par rapport à un multiple de son pas
        assertEquals(step1 / 2, row1.first { it >= 0.0 } % step1, 1e-6)
    }

    @Test
    fun overlapDetectsNeighboursOnly() {
        // voisins immédiats de la rangée du milieu
        assertTrue(CaptureGrid.overlaps(0.0, 0.0, 30.0, 0.0, 50.0, 64.0))
        // voisin diagonal de la rangée inclinée : c'est ce cas qui donne les cycles du graphe
        assertTrue(CaptureGrid.overlaps(0.0, 0.0, 20.0, 45.0, 50.0, 64.0))
        assertTrue(CaptureGrid.overlaps(0.0, 46.0, 40.0, 46.0, 50.0, 64.0))
        // passage ±180°
        assertTrue(CaptureGrid.overlaps(170.0, 0.0, -170.0, 0.0, 50.0, 64.0))
        // opposés : aucun recouvrement
        assertFalse(CaptureGrid.overlaps(0.0, 0.0, 180.0, 0.0, 50.0, 64.0))
        assertFalse(CaptureGrid.overlaps(0.0, 0.0, 90.0, 0.0, 50.0, 64.0))
        assertFalse(CaptureGrid.overlaps(0.0, -46.0, 0.0, 46.0, 50.0, 64.0))
    }

    @Test
    fun theMatchingGraphHasCycles() {
        // Un graphe en arbre suffit à faire échouer l'ajustement de faisceau d'OpenCV : chaque
        // position doit avoir au moins trois voisins pour que des boucles se forment.
        val plan = CaptureGrid.build(50.0, 64.0)
        val degrees = plan.targets.map { t ->
            plan.targets.count { o ->
                o.index != t.index && CaptureGrid.overlaps(t.yawDeg, t.pitchDeg, o.yawDeg, o.pitchDeg, 50.0, 64.0)
            }
        }
        assertTrue("degré minimum ${degrees.min()}", degrees.min()!! >= 3)
        val edges = degrees.sum() / 2
        assertTrue("arêtes $edges pour ${plan.size} positions", edges > plan.size + 5)
    }

    @Test
    fun ultraWideNeedsFarFewerShotsAndClosesThePoles() {
        // Module ultra grand angle : 100° en paysage, donc 80° en portrait sur du 4:3.
        val wide = CaptureGrid.build(80.0, 100.0)
        val standard = CaptureGrid.build(50.0, 66.0)
        // 18 positions contre 30 : la capture est nettement plus courte, donc moins exposée à ce
        // que l'utilisateur se déplace en route.
        assertTrue("grand angle ${wide.size} contre standard ${standard.size}",
            wide.size <= standard.size * 2 / 3)
        assertEquals(3, wide.rowPitches.size)
        // Les rangées inclinées doivent porter au-delà du pôle : plus de calotte à extrapoler.
        assertTrue("portée verticale ${wide.rowPitches.max()!! + 100.0 / 2}",
            wide.rowPitches.max()!! + 100.0 / 2 >= 90.0)
        // Le graphe d'appariement garde ses cycles, condition de convergence du recalage.
        val degrees = wide.targets.map { t ->
            wide.targets.count { o ->
                o.index != t.index && CaptureGrid.overlaps(t.yawDeg, t.pitchDeg, o.yawDeg, o.pitchDeg, 80.0, 100.0)
            }
        }
        assertTrue("degré minimum ${degrees.min()}", degrees.min()!! >= 3)
    }

    @Test
    fun rowPitchStaysUsableAcrossTheWholeLensRange() {
        // Un objectif standard reste sous la barre des 90° : la calotte polaire est extrapolée.
        assertTrue(CaptureGrid.rowPitch(66.0) + 33.0 < 90.0)
        // À partir d'un champ vertical d'environ 85°, la sphère se ferme complètement.
        for (vfov in 85..130 step 5) {
            val pitch = CaptureGrid.rowPitch(vfov.toDouble())
            assertTrue("vfov $vfov : $pitch", pitch + vfov / 2.0 >= 90.0)
            // et la rangée reste assez près de l'horizon pour la recouvrir
            assertTrue("vfov $vfov recouvrement", pitch < vfov * 0.75)
        }
        // Bornes : jamais de rangée absurde, quel que soit ce que rapporte le pilote.
        assertTrue(CaptureGrid.rowPitch(10.0) >= 30.0)
        assertTrue(CaptureGrid.rowPitch(200.0) >= 30.0)
        assertTrue(CaptureGrid.build(1.0, 1.0).size > 0)
        assertTrue(CaptureGrid.build(400.0, 400.0).size > 0)
    }

    @Test
    fun axisAngleIsSymmetricAndBounded() {
        assertEquals(0.0, CaptureGrid.axisAngleDeg(12.0, 34.0, 12.0, 34.0), 1e-9)
        assertEquals(90.0, CaptureGrid.axisAngleDeg(0.0, 0.0, 90.0, 0.0), 1e-9)
        assertEquals(180.0, CaptureGrid.axisAngleDeg(0.0, 0.0, 180.0, 0.0), 1e-9)
        assertEquals(
            CaptureGrid.axisAngleDeg(20.0, 10.0, -50.0, 30.0),
            CaptureGrid.axisAngleDeg(-50.0, 30.0, 20.0, 10.0), 1e-12
        )
    }

    @Test
    fun minShotsToFinishIsReasonable() {
        val plan = CaptureGrid.build(50.0, 66.0)
        assertTrue(plan.minShotsToFinish in 6..plan.middleRowCount)
        assertEquals(6, CaptureGrid.build(90.0, 100.0).minShotsToFinish.coerceAtMost(6))
    }

    @Test
    fun normalizeDeg() {
        assertEquals(-170.0, SphereMath.normalizeDeg(190.0), 1e-9)
        assertEquals(180.0, SphereMath.normalizeDeg(-180.0), 1e-9)
        assertEquals(0.0, SphereMath.normalizeDeg(720.0), 1e-9)
    }
}
