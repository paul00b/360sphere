package care.primary.sphere360

import care.primary.sphere360.data.CameraMeta
import care.primary.sphere360.data.CaptureSessionMeta
import care.primary.sphere360.data.Portal
import care.primary.sphere360.data.SessionState
import care.primary.sphere360.data.ShotMeta
import care.primary.sphere360.data.Sphere
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {
    @Test
    fun sphereRoundTrip() {
        val s = Sphere("abc", "Salon", 123L, 4096, 2048, 0.5, -0.1, mutableListOf(Portal("p1", "def", 1.0, 0.2, "Cuisine")), false, 30, 28)
        val back = Sphere.fromJson(JSONObject(s.toJson().toString()))
        assertEquals("abc", back.id); assertEquals("Salon", back.name); assertEquals(4096, back.width)
        assertEquals(0.5, back.defaultYaw, 1e-12); assertEquals(1, back.portals.size)
        assertEquals("Cuisine", back.portals[0].label); assertEquals("def", back.portals[0].toSphereId)
        assertEquals(28, back.usedShots)
    }

    @Test
    fun sessionRoundTripWithError() {
        val m = CaptureSessionMeta("s1", 5L, "Pièce 1", CameraMeta(50.0, 66.0, 1200, 1600, 90),
            mutableListOf(ShotMeta("shot_000.jpg", 10.0, -46.0, 1.5, 3)), SessionState.FAILED, "boom", 2, 30)
        val back = CaptureSessionMeta.fromJson(JSONObject(m.toJson().toString()))
        assertEquals(SessionState.FAILED, back.state); assertEquals("boom", back.error); assertEquals(2, back.attempts)
        assertEquals(1, back.shots.size); assertEquals(3, back.shots[0].targetIndex); assertEquals(90, back.camera.jpegRotation)
        m.error = null
        assertNull(CaptureSessionMeta.fromJson(JSONObject(m.toJson().toString())).error)
    }
}
