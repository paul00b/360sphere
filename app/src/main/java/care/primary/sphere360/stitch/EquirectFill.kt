package care.primary.sphere360.stitch

import kotlin.math.max
import kotlin.math.min

/**
 * Complète un canevas équirectangulaire partiel : trous entre photos, colonnes non couvertes,
 * et calottes polaires (zénith/nadir non capturés) par extrapolation douce des couleurs de bord.
 *
 * img : BGR entrelacé (w*h*3), mask : 1 = pixel valide (w*h). Modifie les deux tableaux en place.
 */
object EquirectFill {
    private const val GAP_GREY = 40

    fun fill(img: ByteArray, mask: ByteArray, w: Int, h: Int) {
        val first = IntArray(w) { -1 }
        val last = IntArray(w) { -1 }
        for (x in 0 until w) {
            var y = 0
            while (y < h && mask[y * w + x] == 0.toByte()) y++
            if (y < h) {
                first[x] = y
                var yy = h - 1
                while (yy >= 0 && mask[yy * w + x] == 0.toByte()) yy--
                last[x] = yy
            }
        }
        val anyValid = first.any { it >= 0 }
        if (!anyValid) {
            java.util.Arrays.fill(img, GAP_GREY.toByte()); java.util.Arrays.fill(mask, 1.toByte()); return
        }

        fillInteriorHoles(img, mask, w, h, first, last)
        fillCaps(img, w, h, first, last, top = true)
        fillCaps(img, w, h, first, last, top = false)
        fillEmptyColumns(img, w, h, first)
        java.util.Arrays.fill(mask, 1.toByte())
    }

    /** Trous verticaux entre deux pixels valides d'une même colonne : interpolation linéaire. */
    private fun fillInteriorHoles(img: ByteArray, mask: ByteArray, w: Int, h: Int, first: IntArray, last: IntArray) {
        for (x in 0 until w) {
            if (first[x] < 0) continue
            var y = first[x]
            while (y <= last[x]) {
                if (mask[y * w + x] != 0.toByte()) { y++; continue }
                val start = y
                while (y <= last[x] && mask[y * w + x] == 0.toByte()) y++
                val end = y // premier valide après le trou
                val a = (start - 1) * w + x
                val b = end * w + x
                val len = end - start + 1
                for (yy in start until end) {
                    val t = (yy - start + 1).toFloat() / len
                    val o = yy * w + x
                    for (c in 0 until 3) {
                        val va = img[a * 3 + c].toInt() and 0xFF
                        val vb = img[b * 3 + c].toInt() and 0xFF
                        img[o * 3 + c] = (va + (vb - va) * t + 0.5f).toInt().toByte()
                    }
                }
            }
        }
    }

    /**
     * Calottes : couleur de bord par colonne (moyenne de quelques lignes), floutée circulairement,
     * puis fondu vers la couleur moyenne à l'approche du pôle pour éviter toute couture au zénith/nadir.
     */
    private fun fillCaps(img: ByteArray, w: Int, h: Int, first: IntArray, last: IntArray, top: Boolean) {
        val k = 6
        val border = FloatArray(w * 3)      // moyenne de quelques lignes de bord (pour le flou)
        val edge = FloatArray(w * 3)        // pixel exact de bordure (pour la continuité)
        val has = BooleanArray(w)
        for (x in 0 until w) {
            if (first[x] < 0) continue
            val boundary = if (top) first[x] else last[x]
            val y0 = if (top) first[x] else max(first[x], last[x] - k + 1)
            val y1 = if (top) min(last[x], first[x] + k - 1) else last[x]
            var n = 0
            for (y in y0..y1) {
                val o = (y * w + x) * 3
                border[x * 3] += (img[o].toInt() and 0xFF)
                border[x * 3 + 1] += (img[o + 1].toInt() and 0xFF)
                border[x * 3 + 2] += (img[o + 2].toInt() and 0xFF)
                n++
            }
            if (n > 0) {
                border[x * 3] /= n; border[x * 3 + 1] /= n; border[x * 3 + 2] /= n; has[x] = true
                val ob = (boundary * w + x) * 3
                for (c in 0 until 3) edge[x * 3 + c] = (img[ob + c].toInt() and 0xFF).toFloat()
            }
        }
        // colonnes sans donnée : on interpole circulairement la couleur de bord des voisines
        interpolateMissing(border, has, w)
        val blurred = circularBoxBlur(border, w, max(8, w / 32))
        val mean = FloatArray(3)
        for (x in 0 until w) for (c in 0 until 3) mean[c] += blurred[x * 3 + c]
        for (c in 0 until 3) mean[c] /= w

        for (x in 0 until w) {
            if (first[x] < 0) continue
            val boundary = if (top) first[x] else last[x]
            val extent = if (top) boundary else (h - 1 - boundary)
            if (extent <= 0) continue
            for (i in 0 until extent) {
                // t = 0 au pôle, → 1 juste avant la bordure
                val t = (i + 1).toFloat() / (extent + 1)
                val s = t * t * (3 - 2 * t)
                val t3 = t * t * t
                val y = if (top) i else h - 1 - i
                val o = (y * w + x) * 3
                for (c in 0 until 3) {
                    val e = blurred[x * 3 + c] * (1 - t3) + edge[x * 3 + c] * t3
                    val v = mean[c] * (1 - s) + e * s
                    img[o + c] = v.toInt().coerceIn(0, 255).toByte()
                }
            }
        }
    }

    private fun interpolateMissing(values: FloatArray, has: BooleanArray, w: Int) {
        if (has.all { it }) return
        if (has.none { it }) return
        var x = 0
        while (x < w) {
            if (has[x]) { x++; continue }
            // trouver le début et la fin du trou (circulaire)
            var left = (x - 1 + w) % w
            while (!has[left]) left = (left - 1 + w) % w
            var right = x
            var len = 0
            while (!has[right]) { right = (right + 1) % w; len++ }
            for (i in 0 until len) {
                val xi = (x + i) % w
                val t = (i + 1).toFloat() / (len + 1)
                for (c in 0 until 3) values[xi * 3 + c] = values[left * 3 + c] * (1 - t) + values[right * 3 + c] * t
                has[xi] = true
            }
            x += len
        }
    }

    private fun circularBoxBlur(values: FloatArray, w: Int, radius: Int): FloatArray {
        val out = FloatArray(w * 3)
        val r = min(radius, w / 2 - 1)
        val window = 2 * r + 1
        for (c in 0 until 3) {
            var sum = 0f
            for (i in -r..r) sum += values[(((i % w) + w) % w) * 3 + c]
            for (x in 0 until w) {
                out[x * 3 + c] = sum / window
                val outIdx = (((x - r) % w) + w) % w
                val inIdx = (x + r + 1) % w
                sum += values[inIdx * 3 + c] - values[outIdx * 3 + c]
            }
        }
        return out
    }

    /** Colonnes entièrement vides (couverture azimutale incomplète) : gris neutre signalant l'absence de capture. */
    private fun fillEmptyColumns(img: ByteArray, w: Int, h: Int, first: IntArray) {
        for (x in 0 until w) {
            if (first[x] >= 0) continue
            for (y in 0 until h) {
                val o = (y * w + x) * 3
                img[o] = GAP_GREY.toByte(); img[o + 1] = GAP_GREY.toByte(); img[o + 2] = GAP_GREY.toByte()
            }
        }
    }
}
