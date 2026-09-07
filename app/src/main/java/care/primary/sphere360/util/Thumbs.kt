package care.primary.sphere360.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.File

/** Chargement asynchrone de vignettes avec cache mémoire et protection contre le recyclage des vues. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun invalidate(key: String) { cache.remove(key) }

    fun load(view: ImageView, file: File, key: String, maxWidth: Int = 800) {
        view.tag = key
        val cached = cache.get(key)
        if (cached != null) { view.setImageBitmap(cached); return }
        view.setImageDrawable(null)
        if (!file.exists()) return
        Bg.io {
            val bmp = decodeSampled(file, maxWidth) ?: return@io
            cache.put(key, bmp)
            Bg.onMain { if (view.tag == key) view.setImageBitmap(bmp) }
        }
    }

    fun decodeSampled(file: File, maxWidth: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Throwable) {
            null
        }
    }
}
