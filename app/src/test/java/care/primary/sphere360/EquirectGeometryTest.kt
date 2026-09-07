package care.primary.sphere360

import care.primary.sphere360.stitch.EquirectGeometry
import care.primary.sphere360.stitch.IntRoi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class EquirectGeometryTest {
    @Test
    fun canvasIsTwoToOne() {
        val s = EquirectGeometry.canvasSize(651.9)
        assertEquals(0, s[0] % 2)
        assertEquals(s[0] / 2, s[1])
        assertTrue(s[0] in 4094..4098)
    }

    @Test
    fun columnMappingAndYaw() {
        val w = 4096
        assertEquals(w / 2, EquirectGeometry.canvasCol(0, w))
        assertEquals(0, EquirectGeometry.canvasCol(-w / 2, w))
        assertEquals(w - 1, EquirectGeometry.canvasCol(w / 2 - 1, w))
        assertEquals(0, EquirectGeometry.canvasCol(w / 2, w))
        assertEquals(0.0, EquirectGeometry.yawFromCol(w / 2.0, w), 1e-9)
        assertEquals(PI / 2, EquirectGeometry.yawFromCol(w * 0.75, w), 1e-9)
        assertEquals(-PI / 2, EquirectGeometry.yawFromCol(w * 0.25, w), 1e-9)
    }

    @Test
    fun segmentsCoverEveryColumnWithoutCrossingTheSeam() {
        val w = 1000
        for (start in listOf(-1200, -600, -500, -10, 0, 300, 499, 500, 1500)) {
            val count = 700
            val segs = EquirectGeometry.columnSegments(start, count, w)
            assertEquals(count, segs.sumOf { it[2] })
            var expectedOffset = 0
            for (s in segs) {
                assertEquals(expectedOffset, s[0])
                assertTrue(s[1] >= 0 && s[1] + s[2] <= w)
                assertEquals(EquirectGeometry.canvasCol(start + s[0], w), s[1])
                expectedOffset += s[2]
            }
        }
    }

    @Test
    fun medianFocalFollowsOpenCvRule() {
        assertEquals(3.0, EquirectGeometry.medianFocal(doubleArrayOf(5.0, 1.0, 3.0)), 1e-12)
        assertEquals(2.5, EquirectGeometry.medianFocal(doubleArrayOf(4.0, 1.0, 3.0, 2.0)), 1e-12)
    }

    @Test
    fun composeScaleAndSize() {
        assertEquals(1.0, EquirectGeometry.composeScale(-1.0, 1600, 1200), 1e-12)
        assertEquals(0.5, EquirectGeometry.composeScale(0.48, 1600, 1200), 1e-9)
        val small = EquirectGeometry.composedSize(1600, 1200, 0.5)
        assertEquals(800, small[0]); assertEquals(600, small[1])
        val near = EquirectGeometry.composedSize(1600, 1200, 0.95)
        assertEquals(1600, near[0])
    }

    @Test
    fun unionOfRois() {
        val u = EquirectGeometry.union(listOf(IntRoi(-10, 5, 20, 20), IntRoi(5, -3, 30, 10)))
        assertEquals(-10, u.x); assertEquals(-3, u.y); assertEquals(45, u.width); assertEquals(28, u.height)
    }
}
