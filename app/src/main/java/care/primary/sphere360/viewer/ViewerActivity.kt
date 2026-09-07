package care.primary.sphere360.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import care.primary.sphere360.App
import care.primary.sphere360.R
import care.primary.sphere360.data.Sphere
import care.primary.sphere360.data.TourStore
import care.primary.sphere360.util.Bg
import care.primary.sphere360.util.padTopWithStatusBar
import care.primary.sphere360.util.toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Viewer 360 : Photo Sphere Viewer (+ VirtualTourPlugin, MarkersPlugin) dans une WebView,
 * alimentée par les fichiers locaux via LocalContentServer. Le pont JS ↔ Kotlin passe par
 * l'objet `Android` (JavascriptInterface) et `window.app` côté web.
 */
open class ViewerActivity : Activity() {

    companion object {
        private const val TAG = "Viewer"
        const val EXTRA_SPHERE = "sphere"

        fun start(context: Context, sphereId: String) {
            context.startActivity(Intent(context, ViewerActivity::class.java).putExtra(EXTRA_SPHERE, sphereId))
        }
    }

    protected lateinit var store: TourStore
    protected lateinit var web: WebView
    protected lateinit var title: TextView
    protected lateinit var hint: TextView
    protected lateinit var btnEdit: ImageButton
    protected lateinit var btnEntryView: ImageButton
    private lateinit var loading: ProgressBar
    private lateinit var server: LocalContentServer

    protected var currentId: String = ""
    protected var editMode = false
    private var webReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = App.store
        currentId = intent.getStringExtra(EXTRA_SPHERE) ?: ""
        if (store.get(currentId) == null) { finish(); return }
        setContentView(R.layout.activity_viewer)
        edgeToEdge()

        web = findViewById(R.id.web)
        title = findViewById(R.id.title)
        hint = findViewById(R.id.hint)
        loading = findViewById(R.id.loading)
        btnEdit = findViewById(R.id.btn_edit)
        btnEntryView = findViewById(R.id.btn_entry_view)
        findViewById<View>(R.id.top_bar).padTopWithStatusBar()
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { onBackPressed() }
        title.text = store.get(currentId)?.name ?: ""

        server = LocalContentServer(this, store)
        setupWebView()
        web.loadUrl("${LocalContentServer.BASE}/viewer/index.html")
    }

    @Suppress("DEPRECATION")
    private fun edgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true)
        web.setBackgroundColor(Color.BLACK)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                server.intercept(request) ?: super.shouldInterceptRequest(view, request)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d(TAG, "[js] ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
    }

    // ---- Données envoyées au viewer ----

    protected fun nodeJson(s: Sphere): JSONObject {
        val links = JSONArray()
        for (p in s.portals) {
            if (store.get(p.toSphereId) == null) continue
            links.put(JSONObject().put("portalId", p.id).put("nodeId", p.toSphereId).put("yaw", p.yaw).put("pitch", p.pitch).put("label", p.label))
        }
        return JSONObject()
            .put("id", s.id).put("name", s.name)
            .put("panorama", LocalContentServer.sphereUrl(s.id, "equirect.jpg"))
            .put("thumbnail", LocalContentServer.sphereUrl(s.id, "thumb.jpg"))
            .put("defaultYaw", s.defaultYaw).put("defaultPitch", s.defaultPitch)
            .put("links", links)
    }

    private fun tourJson(): JSONObject {
        val nodes = JSONArray()
        store.all().forEach { nodes.put(nodeJson(it)) }
        return JSONObject().put("mode", if (editMode) "edit" else "view").put("startId", currentId).put("nodes", nodes)
    }

    protected fun js(code: String, callback: ((String) -> Unit)? = null) {
        if (!webReady) return
        Bg.onMain { web.evaluateJavascript(code) { result -> callback?.invoke(result ?: "null") } }
    }

    protected fun pushTour() { js("window.app.load(${tourJson()})") }

    protected fun pushNode(s: Sphere) { js("window.app.updateNode(${nodeJson(s)})") }

    protected open fun onWebReady() {
        webReady = true
        pushTour()
    }

    protected open fun onViewerReady() {
        loading.visibility = View.GONE
    }

    protected open fun onNodeChanged(id: String) {
        currentId = id
        title.text = store.get(id)?.name ?: ""
    }

    protected open fun onSphereTapped(yaw: Double, pitch: Double) {}
    protected open fun onPortalTapped(portalId: String) {}

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!webReady) { @Suppress("DEPRECATION") super.onBackPressed(); return }
        js("window.app.goBack()") { r -> if (r != "true") finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::web.isInitialized) {
            web.loadUrl("about:blank")
            web.destroy()
        }
    }

    /** Méthodes appelées depuis le JavaScript (thread WebView → main). */
    private inner class Bridge {
        @JavascriptInterface fun onReady() { Bg.onMain { onWebReady() } }
        @JavascriptInterface fun onViewerReady() { Bg.onMain { this@ViewerActivity.onViewerReady() } }
        @JavascriptInterface fun onNodeChanged(id: String) { Bg.onMain { this@ViewerActivity.onNodeChanged(id) } }
        @JavascriptInterface fun onSphereTapped(yaw: Double, pitch: Double) { Bg.onMain { this@ViewerActivity.onSphereTapped(yaw, pitch) } }
        @JavascriptInterface fun onPortalTapped(portalId: String) { Bg.onMain { this@ViewerActivity.onPortalTapped(portalId) } }
        @JavascriptInterface fun onError(message: String) {
            Bg.onMain { loading.visibility = View.GONE; toast(getString(R.string.viewer_error) + " " + message) }
        }
        @JavascriptInterface fun log(message: String) { Log.d(TAG, "[app] $message") }
    }
}
