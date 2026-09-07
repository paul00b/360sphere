package care.primary.sphere360.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import care.primary.sphere360.App
import care.primary.sphere360.R
import care.primary.sphere360.capture.SphereMath
import care.primary.sphere360.data.Portal
import care.primary.sphere360.data.Sphere
import care.primary.sphere360.data.TourStore
import care.primary.sphere360.gallery.RetouchActivity
import care.primary.sphere360.util.Bg
import care.primary.sphere360.util.padTopWithStatusBar
import care.primary.sphere360.util.toast
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Viewer 360 et éditeur de visite : Photo Sphere Viewer (+ VirtualTourPlugin, MarkersPlugin) dans
 * une WebView alimentée par LocalContentServer. Le pont JS ↔ Kotlin passe par l'objet `Android`
 * (JavascriptInterface) côté web et `window.app` côté Kotlin.
 *
 * Mode visite : glisser/pincer, flèches 3D et libellés cliquables pour changer de sphère.
 * Mode édition : toucher la sphère pose un portail (dialogue cible + libellé), toucher un libellé
 * le modifie/déplace/supprime, un bouton mémorise la vue d'entrée de la sphère.
 */
class ViewerActivity : Activity() {

    companion object {
        private const val TAG = "Viewer"
        const val EXTRA_SPHERE = "sphere"

        /** Amplitude des curseurs d'assiette, en degrés : au-delà, c'est la capture qu'il faut refaire. */
        private const val LEVEL_RANGE_DEG = 15.0

        fun start(context: Context, sphereId: String) {
            context.startActivity(Intent(context, ViewerActivity::class.java).putExtra(EXTRA_SPHERE, sphereId))
        }
    }

    private lateinit var store: TourStore
    private lateinit var web: WebView
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private lateinit var btnEdit: ImageButton
    private lateinit var btnEntryView: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var loading: ProgressBar
    private lateinit var levelPanel: View
    private lateinit var levelTilt: SeekBar
    private lateinit var levelRoll: SeekBar
    private lateinit var levelTiltLabel: TextView
    private lateinit var levelRollLabel: TextView
    private lateinit var server: LocalContentServer

    private var currentId: String = ""
    private var editMode = false
    private var webReady = false
    private var movingPortal: Portal? = null
    private var needsReload = false

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
        btnMore = findViewById(R.id.btn_more)
        levelPanel = findViewById(R.id.level_panel)
        levelTilt = findViewById(R.id.level_tilt)
        levelRoll = findViewById(R.id.level_roll)
        levelTiltLabel = findViewById(R.id.level_tilt_label)
        levelRollLabel = findViewById(R.id.level_roll_label)
        findViewById<View>(R.id.top_bar).padTopWithStatusBar()
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { goBackOrFinish() }
        title.text = store.get(currentId)?.name ?: ""
        btnEdit.visibility = View.VISIBLE
        btnEdit.setOnClickListener { toggleEditMode() }
        btnEntryView.setOnClickListener { saveEntryView() }
        btnMore.setOnClickListener { showMoreMenu() }
        setupLevelPanel()

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

    private fun nodeJson(s: Sphere): JSONObject {
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
            .put("correctionPitch", s.correctionPitch).put("correctionRoll", s.correctionRoll)
            .put("links", links)
    }

    /**
     * Sphères chargées comme nœuds de la visite : celles du dossier de la sphère ouverte.
     *
     * Une visite est fermée sur son dossier. Charger toute la galerie encombrerait le sélecteur de
     * destination d'un portail de captures d'autres lieux, et ferait payer au viewer la
     * pré-lecture de sphères sans rapport.
     */
    private fun tourSpheres(): List<Sphere> {
        val current = store.get(currentId) ?: return emptyList()
        return store.spheresIn(current.folderId)
    }

    private fun tourJson(): JSONObject {
        val nodes = JSONArray()
        tourSpheres().forEach { nodes.put(nodeJson(it)) }
        return JSONObject().put("mode", if (editMode) "edit" else "view").put("startId", currentId).put("nodes", nodes)
    }

    private fun js(code: String, callback: ((String) -> Unit)? = null) {
        if (!webReady) return
        Bg.onMain { web.evaluateJavascript(code) { result -> callback?.invoke(result ?: "null") } }
    }

    private fun pushTour() { js("window.app.load(${tourJson()})") }
    private fun pushNode(s: Sphere) { js("window.app.updateNode(${nodeJson(s)})") }

    private fun currentSphere(): Sphere? = store.get(currentId)

    // ---- Événements du viewer ----

    private fun onWebReady() {
        webReady = true
        pushTour()
    }

    private fun onViewerReady() {
        loading.visibility = View.GONE
        if (!editMode && tourSpheres().size >= 2 && currentSphere()?.portals?.isEmpty() == true) {
            showHint(getString(R.string.viewer_hint_drag), 3500)
        }
    }

    private fun onNodeChanged(id: String) {
        if (levelPanel.visibility == View.VISIBLE) closeLevelPanel(restore = false)
        currentId = id
        title.text = store.get(id)?.name ?: ""
        movingPortal = null
        if (editMode) showHint(getString(R.string.edit_mode_hint), 0)
    }

    private fun onSphereTapped(yaw: Double, pitch: Double) {
        if (!editMode) return
        val sphere = currentSphere() ?: return
        val moving = movingPortal
        if (moving != null) {
            moving.yaw = yaw; moving.pitch = pitch
            movingPortal = null
            store.update(sphere)
            pushNode(sphere)
            showHint(getString(R.string.edit_mode_hint), 0)
            return
        }
        showPortalDialog(sphere, null, yaw, pitch)
    }

    private fun onPortalTapped(portalId: String) {
        if (!editMode) return
        val sphere = currentSphere() ?: return
        val portal = sphere.portals.firstOrNull { it.id == portalId } ?: return
        showPortalDialog(sphere, portal, portal.yaw, portal.pitch)
    }

    // ---- Mode édition ----

    private fun toggleEditMode() {
        editMode = !editMode
        movingPortal = null
        js("window.app.setMode('${if (editMode) "edit" else "view"}')")
        btnEdit.setImageResource(if (editMode) R.drawable.ic_done_edit else R.drawable.ic_edit)
        btnEdit.contentDescription = getString(if (editMode) R.string.edit_exit else R.string.edit_mode)
        btnEntryView.visibility = if (editMode) View.VISIBLE else View.GONE
        if (editMode) showHint(getString(R.string.edit_mode_hint), 0) else hint.visibility = View.GONE
    }

    private fun showMoreMenu() {
        val sphere = currentSphere() ?: return
        val menu = PopupMenu(this, btnMore)
        menu.menu.add(0, 1, 0, R.string.level_menu)
        if (store.hasSessionImages(sphere.sessionId)) menu.menu.add(0, 2, 1, R.string.action_retouch)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> openLevelPanel()
                2 -> RetouchActivity.start(this, sphere.id)
            }
            true
        }
        menu.show()
    }

    // ---- Assiette de la sphère ----

    private fun setupLevelPanel() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                updateLevelLabels()
                if (fromUser) js("window.app.previewCorrection(${tiltRad()}, ${rollRad()})")
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        }
        levelTilt.setOnSeekBarChangeListener(listener)
        levelRoll.setOnSeekBarChangeListener(listener)
        findViewById<Button>(R.id.level_reset).setOnClickListener {
            setLevelSliders(0.0, 0.0)
            js("window.app.previewCorrection(0, 0)")
        }
        findViewById<Button>(R.id.level_cancel).setOnClickListener { closeLevelPanel(restore = true) }
        findViewById<Button>(R.id.level_save).setOnClickListener { saveLevel() }
    }

    /** Le curseur couvre ±[LEVEL_RANGE_DEG] par pas d'un dixième de degré. */
    private fun degOf(bar: SeekBar): Double = bar.progress / 10.0 - LEVEL_RANGE_DEG
    private fun tiltRad(): Double = Math.toRadians(degOf(levelTilt))
    private fun rollRad(): Double = Math.toRadians(degOf(levelRoll))

    private fun setLevelSliders(tiltRad: Double, rollRad: Double) {
        levelTilt.progress = ((Math.toDegrees(tiltRad) + LEVEL_RANGE_DEG) * 10).toInt().coerceIn(0, levelTilt.max)
        levelRoll.progress = ((Math.toDegrees(rollRad) + LEVEL_RANGE_DEG) * 10).toInt().coerceIn(0, levelRoll.max)
        updateLevelLabels()
    }

    private fun updateLevelLabels() {
        levelTiltLabel.text = getString(R.string.level_tilt, "%+.1f".format(degOf(levelTilt)))
        levelRollLabel.text = getString(R.string.level_roll, "%+.1f".format(degOf(levelRoll)))
    }

    private fun openLevelPanel() {
        val sphere = currentSphere() ?: return
        if (editMode) toggleEditMode()
        setLevelSliders(sphere.correctionPitch, sphere.correctionRoll)
        levelPanel.visibility = View.VISIBLE
        hint.visibility = View.GONE
    }

    private fun closeLevelPanel(restore: Boolean) {
        levelPanel.visibility = View.GONE
        val sphere = currentSphere()
        if (restore && sphere != null) {
            js("window.app.previewCorrection(${sphere.correctionPitch}, ${sphere.correctionRoll})")
        }
    }

    /**
     * Enregistre l'assiette et recale les portails de la sphère.
     *
     * La correction fait tourner l'image sous les marqueurs, qui eux vivent dans le repère du
     * monde : sans recalage, un portail posé sur une porte ne la désignerait plus. Le viewer
     * recalcule leurs positions en les rattachant à leur point d'image, et on enregistre les deux
     * ensemble.
     */
    private fun saveLevel() {
        val sphere = currentSphere() ?: return
        val tilt = tiltRad()
        val roll = rollRad()
        js("window.app.reanchorPortals($tilt, $roll)") { raw ->
            try {
                val str = JSONTokener(raw).nextValue() as? String
                if (str != null) {
                    val arr = JSONArray(str)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val portal = sphere.portals.firstOrNull { it.id == o.getString("portalId") } ?: continue
                        portal.yaw = o.getDouble("yaw")
                        portal.pitch = o.getDouble("pitch")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "recalage des portails : $raw", e)
            }
            sphere.correctionPitch = tilt
            sphere.correctionRoll = roll
            store.update(sphere)
            js("window.app.commitCorrection(${nodeJson(sphere)})")
            levelPanel.visibility = View.GONE
            toast(getString(R.string.level_saved))
        }
    }

    private var hintHide: Runnable? = null
    private fun showHint(text: String, autoHideMs: Long) {
        hint.text = text
        hint.visibility = View.VISIBLE
        hintHide?.let { Bg.main.removeCallbacks(it) }
        if (autoHideMs > 0) {
            val r = Runnable { if (!editMode) hint.visibility = View.GONE }
            hintHide = r
            Bg.main.postDelayed(r, autoHideMs)
        }
    }

    private fun saveEntryView() {
        val sphere = currentSphere() ?: return
        js("window.app.getPosition()") { raw ->
            try {
                val str = JSONTokener(raw).nextValue() as? String ?: return@js
                val o = JSONObject(str)
                sphere.defaultYaw = o.getDouble("yaw")
                sphere.defaultPitch = o.getDouble("pitch")
                store.update(sphere)
                toast(getString(R.string.entry_view_saved))
            } catch (e: Exception) {
                Log.w(TAG, "getPosition: $raw", e)
            }
        }
    }

    private fun showPortalDialog(sphere: Sphere, existing: Portal?, yaw: Double, pitch: Double) {
        val targets = tourSpheres().filter { it.id != sphere.id }
        if (targets.isEmpty()) { toast(getString(R.string.portal_no_target)); return }

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_portal, null)
        val spinner = view.findViewById<Spinner>(R.id.spinner_target)
        val editLabel = view.findViewById<EditText>(R.id.edit_label)
        val checkReturn = view.findViewById<CheckBox>(R.id.check_return)
        val btnDelete = view.findViewById<Button>(R.id.btn_delete_portal)

        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, targets.map { it.name })
        val initial = if (existing != null) targets.indexOfFirst { it.id == existing.toSphereId }.coerceAtLeast(0) else 0
        spinner.setSelection(initial)
        editLabel.setText(existing?.label ?: targets[initial].name)
        var labelEdited = existing != null
        editLabel.setOnFocusChangeListener { _, has -> if (has) labelEdited = true }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (!labelEdited) editLabel.setText(targets[position].name)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        checkReturn.visibility = if (existing == null) View.VISIBLE else View.GONE
        btnDelete.visibility = if (existing != null) View.VISIBLE else View.GONE

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.portal_new_title else R.string.portal_edit_title)
            .setView(view)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val target = targets[spinner.selectedItemPosition]
                val label = editLabel.text.toString().trim().ifEmpty { target.name }
                if (existing != null) {
                    existing.toSphereId = target.id
                    existing.label = label
                    store.update(sphere)
                } else {
                    sphere.portals.add(Portal(store.newId(), target.id, yaw, pitch, label))
                    store.update(sphere)
                    if (checkReturn.isChecked && target.portals.none { it.toSphereId == sphere.id }) {
                        target.portals.add(Portal(store.newId(), sphere.id, SphereMath.normalizeAngle(yaw + Math.PI), pitch, sphere.name))
                        store.update(target)
                        pushNode(target)
                    }
                    toast(getString(R.string.portal_added))
                }
                pushNode(sphere)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .apply { if (existing != null) setNeutralButton(R.string.portal_move) { _, _ ->
                movingPortal = existing
                showHint(getString(R.string.edit_move_hint, existing.label), 0)
            } }
            .create()
        btnDelete.setOnClickListener {
            if (existing != null) {
                sphere.portals.remove(existing)
                store.update(sphere)
                pushNode(sphere)
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    // ---- Navigation ----

    private fun goBackOrFinish() {
        if (!webReady) { finish(); return }
        js("window.app.goBack()") { r -> if (r != "true") finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (levelPanel.visibility == View.VISIBLE) { closeLevelPanel(restore = true); return }
        if (editMode) { toggleEditMode(); return }
        goBackOrFinish()
    }

    override fun onResume() {
        super.onResume()
        // Un réassemblage lancé depuis la retouche remplace l'image : on recharge la visite pour
        // ne pas rester sur la version périmée mise en cache par la WebView.
        if (webReady && needsReload) { needsReload = false; pushTour() }
    }

    override fun onPause() {
        super.onPause()
        needsReload = true
    }

    override fun onDestroy() {
        super.onDestroy()
        hintHide?.let { Bg.main.removeCallbacks(it) }
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
