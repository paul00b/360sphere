package care.primary.sphere360

import care.primary.sphere360.stitch.EquirectFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class EquirectFillTest {
    private fun px(img: ByteArray, w: Int, x: Int, y: Int, c: Int) = img[(y * w + x) * 3 + c].toInt() and 0xFF

    @Test
    fun fillsCapsSmoothlyAndUniformlyAtPoles() {
        val w = 128; val h = 64
        val img = ByteArray(w * h * 3); val mask = ByteArray(w * h)
        val rnd = Random(1)
        for (y in 20 until 44) for (x in 0 until w) {
            mask[y * w + x] = 1
            img[(y * w + x) * 3] = (100 + rnd.nextInt(40)).toByte()
            img[(y * w + x) * 3 + 1] = (50 + (x % 30)).toByte()
            img[(y * w + x) * 3 + 2] = 200.toByte()
        }
        EquirectFill.fill(img, mask, w, h)
        assertTrue(mask.all { it == 1.toByte() })
        // ligne du pôle uniforme (aucune couture au zénith)
        for (x in 1 until w) for (c in 0 until 3) assertEquals(px(img, w, 0, 0, c), px(img, w, x, 0, c))
        for (x in 1 until w) for (c in 0 until 3) assertEquals(px(img, w, 0, h - 1, c), px(img, w, x, h - 1, c))
        // continuité juste au-dessus de la bordure
        for (x in 0 until w) for (c in 0 until 3) assertTrue(abs(px(img, w, x, 19, c) - px(img, w, x, 20, c)) <= 12)
        // le canal bleu constant est préservé partout
        for (y in 0 until h) for (x in 0 until w) assertTrue(abs(px(img, w, x, y, 2) - 200) <= 2)
    }

    @Test
    fun interiorHolesAreInterpolatedAndEmptyColumnsGreyed() {
        val w = 32; val h = 16
        val img = ByteArray(w * h * 3); val mask = ByteArray(w * h)
        for (y in 2 until 14) for (x in 0 until 24) {
            if (y in 6..8 && x == 5) continue // trou
            mask[y * w + x] = 1
            val v = if (y < 6) 40 else if (y > 8) 120 else 0
            img[(y * w + x) * 3] = v.toByte(); img[(y * w + x) * 3 + 1] = v.toByte(); img[(y * w + x) * 3 + 2] = v.toByte()
        }
        EquirectFill.fill(img, mask, w, h)
        assertEquals(60, px(img, w, 5, 6, 0)); assertEquals(80, px(img, w, 5, 7, 0)); assertEquals(100, px(img, w, 5, 8, 0))
        for (x in 24 until 32) for (y in 0 until h) assertEquals(40, px(img, w, x, y, 0))
    }
}
