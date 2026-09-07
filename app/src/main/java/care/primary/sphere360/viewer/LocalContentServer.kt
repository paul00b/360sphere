package care.primary.sphere360.viewer

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import care.primary.sphere360.data.TourStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * Sert les fichiers du viewer (assets) et les images des sphères (filesDir) à la WebView
 * sous une origine https unique et fictive : modules, fetch et WebGL fonctionnent sans CORS,
 * sans accès file:// et sans serveur réseau.
 */
class LocalContentServer(private val context: Context, private val store: TourStore) {

    companion object {
        const val HOST = "sphere360.local"
        const val BASE = "https://$HOST"
        fun sphereUrl(sphereId: String, file: String) = "$BASE/spheres/$sphereId/$file"
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (uri.host != HOST) return null
        val path = uri.path ?: return notFound()
        return when {
            path.startsWith("/viewer/") -> asset("viewer/" + sanitize(path.removePrefix("/viewer/")))
            path.startsWith("/spheres/") -> {
                val parts = path.removePrefix("/spheres/").split("/")
                if (parts.size != 2) notFound()
                else file(File(store.sphereDir(sanitize(parts[0])), sanitize(parts[1])))
            }
            else -> notFound()
        }
    }

    private fun sanitize(s: String): String = s.replace("..", "").replace("\\", "")

    private fun asset(name: String): WebResourceResponse {
        return try {
            val stream = context.assets.open(name)
            WebResourceResponse(mime(name), if (isText(name)) "utf-8" else null, 200, "OK", headers(), stream)
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun file(f: File): WebResourceResponse {
        if (!f.exists() || !f.isFile) return notFound()
        return WebResourceResponse(mime(f.name), null, 200, "OK", headers(), FileInputStream(f))
    }

    private fun headers(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "no-cache"
    )

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers(), ByteArrayInputStream(ByteArray(0)))

    private fun isText(name: String) = name.endsWith(".html") || name.endsWith(".js") || name.endsWith(".css") || name.endsWith(".json") || name.endsWith(".svg")

    private fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        else -> "application/octet-stream"
    }
}
