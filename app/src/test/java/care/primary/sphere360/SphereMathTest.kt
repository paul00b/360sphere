package care.primary.sphere360

import care.primary.sphere360.capture.SphereMath
import care.primary.sphere360.capture.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class SphereMathTest {
    /** Téléphone droit (portrait), caméra arrière vers le nord : X=est, Y(haut)=Z monde, Z(écran)=sud. */
    private val upright = floatArrayOf(
        1f, 0f, 0f,
        0f, 0f, -1f,
        0f, 1f, 0f
    )

    @Test
    fun yawPitchRoundTrip() {
        for (yaw in listOf(-3.0, -1.0, 0.0, 0.5, 2.0, 3.1)) for (pitch in listOf(-1.2, -0.3, 0.0, 0.7, 1.3)) {
            val d = SphereMath.dirFromYawPitch(yaw, pitch)
            assertEquals(1.0, d.length(), 1e-9)
            assertEquals(yaw, SphereMath.yawOf(d), 1e-9)
            assertEquals(pitch, SphereMath.pitchOf(d), 1e-9)
        }
    }

    @Test
    fun uprightPhoneLooksNorthWithoutRoll() {
        val f = SphereMath.cameraForward(upright)
        assertEquals(0.0, f.x, 1e-9); assertEquals(1.0, f.y, 1e-9); assertEquals(0.0, f.z, 1e-9)
        assertEquals(0.0, SphereMath.yawOf(f), 1e-9)
        assertEquals(0.0, SphereMath.pitchOf(f), 1e-9)
        assertEquals(0.0, SphereMath.rollOf(upright), 1e-9)
    }

    @Test
    fun flatPhoneLooksDown() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val f = SphereMath.cameraForward(identity)
        assertEquals(-PI / 2, SphereMath.pitchOf(f), 1e-9)
    }

    @Test
    fun projectionSignsMatchScreen() {
        // un point un peu à l'est du nord doit se projeter à droite (x>0), un point plus haut vers le haut (y<0)
        val right = SphereMath.projectToCamera(upright, SphereMath.dirFromYawPitch(0.1, 0.0))!!
        assertTrue(right[0] > 0); assertEquals(0.0, right[1], 1e-9)
        val up = SphereMath.projectToCamera(upright, SphereMath.dirFromYawPitch(0.0, 0.1))!!
        assertTrue(up[1] < 0); assertEquals(0.0, up[0], 1e-9)
        // derrière la caméra : pas de projection
        assertNull(SphereMath.projectToCamera(upright, SphereMath.dirFromYawPitch(PI, 0.0)))
        assertNotNull(SphereMath.projectToCamera(upright, SphereMath.dirFromYawPitch(0.0, 0.0)))
    }

    @Test
    fun angleBetweenAndNormalize() {
        assertEquals(PI / 2, SphereMath.angleBetween(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0)), 1e-9)
        assertEquals(-PI + 0.5, SphereMath.normalizeAngle(PI + 0.5), 1e-9)
        assertEquals(PI, SphereMath.normalizeAngle(-PI), 1e-9)
    }
}
