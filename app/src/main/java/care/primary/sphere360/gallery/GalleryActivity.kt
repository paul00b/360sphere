package care.primary.sphere360.gallery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toolbar
import care.primary.sphere360.App
import care.primary.sphere360.R
import care.primary.sphere360.capture.CaptureActivity
import care.primary.sphere360.data.CaptureSessionMeta
import care.primary.sphere360.data.Folder
import care.primary.sphere360.data.SessionState
import care.primary.sphere360.data.Sphere
import care.primary.sphere360.data.TourStore
import care.primary.sphere360.demo.DemoTourInstaller
import care.primary.sphere360.util.Bg
import care.primary.sphere360.util.Prefs
import care.primary.sphere360.stitch.StitchJobs
import care.primary.sphere360.stitch.StitchMode
import care.primary.sphere360.stitch.StitchService
import care.primary.sphere360.util.Thumbs
import care.primary.sphere360.util.padBottomWithNavBar
import care.primary.sphere360.util.padTopWithStatusBar
import care.primary.sphere360.util.toast
import care.primary.sphere360.viewer.ViewerActivity

/**
 * Galerie des sphères capturées et des assemblages en cours ou en échec.
 *
 * Deux niveaux : la racine, qui mêle les dossiers et les sphères non classées, et l'intérieur d'un
 * dossier. Un dossier correspond à une visite — un logement, un local — et c'est aussi la frontière
 * des portails : le viewer ne charge que les sphères du dossier courant, si bien qu'une visite de
 * dix pièces ne se retrouve pas encombrée de toutes les captures jamais faites.
 */
open class GalleryActivity : Activity() {

    private sealed class Item {
        class FolderItem(val folder: Folder, val count: Int) : Item()
        class SphereItem(val sphere: Sphere) : Item()
        class SessionItem(val meta: CaptureSessionMeta) : Item()
    }

    private lateinit var store: TourStore
    private lateinit var prefs: Prefs
    private lateinit var toolbar: Toolbar
    private lateinit var grid: GridView
    private lateinit var emptyView: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyBody: TextView
    private lateinit var btnDemo: Button
    private lateinit var fab: ImageButton
    private val adapter = GalleryAdapter()
    private var items: List<Item> = emptyList()

    /** Dossier ouvert, chaîne vide pour la racine. */
    private var folderId = ""

    private val storeListener = { refresh() }
    private val jobsListener = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = App.store
        prefs = Prefs(this)
        setContentView(R.layout.activity_gallery)
        toolbar = findViewById(R.id.toolbar)
        toolbar.padTopWithStatusBar()
        toolbar.inflateMenu(R.menu.gallery)
        toolbar.setOnMenuItemClickListener { onMenu(it.itemId) }
        toolbar.setNavigationOnClickListener { openFolder("") }
        grid = findViewById(R.id.grid)
        grid.adapter = adapter
        grid.padBottomWithNavBar()
        emptyView = findViewById(R.id.empty_view)
        emptyTitle = findViewById(R.id.empty_title)
        emptyBody = findViewById(R.id.empty_body)
        btnDemo = findViewById(R.id.btn_demo)
        fab = findViewById(R.id.fab)
        fab.setOnClickListener { startCapture() }
        btnDemo.setOnClickListener { installDemo() }
        grid.setOnItemClickListener { _, _, position, _ -> onItemClick(items[position]) }
        grid.setOnItemLongClickListener { _, view, position, _ -> onItemLongClick(view, items[position]); true }
        cleanupStaleSessions()
        // On rouvre le dossier quitté : l'assemblage se termine en arrière-plan, souvent après un
        // passage par l'écran d'accueil du téléphone.
        val last = prefs.lastFolderId
        folderId = if (last.isNotEmpty() && store.folder(last) != null) last else ""
    }

    override fun onResume() {
        super.onResume()
        store.addListener(storeListener)
        StitchJobs.addListener(jobsListener)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        store.removeListener(storeListener)
        StitchJobs.removeListener(jobsListener)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (folderId.isNotEmpty()) openFolder("") else super.onBackPressed()
    }

    protected open fun onMenu(id: Int): Boolean {
        when (id) {
            R.id.menu_new_folder -> { createFolder(); return true }
            R.id.menu_demo -> { installDemo(); return true }
            R.id.menu_about -> {
                AlertDialog.Builder(this).setTitle(R.string.about_title).setMessage(R.string.about_body)
                    .setPositiveButton(R.string.action_ok, null).show()
                return true
            }
        }
        return false
    }

    /** Sessions laissées en état CAPTURING (app tuée pendant la capture) : on les supprime. */
    private fun cleanupStaleSessions() {
        for (s in store.listSessions()) {
            if (s.state == SessionState.CAPTURING) store.deleteSession(s.id)
        }
    }

    // ---- Navigation ----

    private fun openFolder(id: String) {
        folderId = id
        prefs.lastFolderId = id
        grid.setSelection(0)
        refresh()
    }

    protected fun refresh() {
        // Un dossier supprimé depuis un autre écran ne doit pas laisser la galerie sur du vide.
        if (folderId.isNotEmpty() && store.folder(folderId) == null) {
            folderId = ""
            prefs.lastFolderId = ""
        }
        val list = ArrayList<Item>()
        if (folderId.isEmpty()) {
            store.folders().forEach { list.add(Item.FolderItem(it, store.countIn(it.id))) }
        }
        store.listSessions()
            .filter { it.state != SessionState.CAPTURING && it.folderId == folderId }
            .forEach { list.add(Item.SessionItem(it)) }
        store.spheresIn(folderId).forEach { list.add(Item.SphereItem(it)) }
        items = list
        adapter.notifyDataSetChanged()

        val folder = store.folder(folderId)
        toolbar.title = folder?.name ?: getString(R.string.gallery_title)
        toolbar.navigationIcon = if (folder != null) getDrawable(R.drawable.ic_arrow_back) else null
        toolbar.menu.findItem(R.id.menu_new_folder)?.isVisible = folder == null

        val empty = list.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            emptyTitle.setText(if (folder != null) R.string.folder_empty_title else R.string.gallery_empty_title)
            emptyBody.setText(if (folder != null) R.string.folder_empty_body else R.string.gallery_empty_body)
            btnDemo.visibility = if (folder != null) View.GONE else View.VISIBLE
        }
        onItemsRefreshed(empty)
    }

    /** Point d'extension (V2 : carte de démo). */
    protected open fun onItemsRefreshed(empty: Boolean) {}

    // ---- Actions ----

    private fun installDemo() {
        Bg.io {
            try {
                DemoTourInstaller.install(this, store)
                Bg.onMain { toast(getString(R.string.demo_installed)) }
            } catch (e: Exception) {
                Bg.onMain { toast(e.message ?: "erreur") }
            }
        }
    }

    private fun startCapture() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        launchCapture()
    }

    /** La capture atterrit dans le dossier ouvert : c'est le sens d'un dossier par lieu. */
    private fun launchCapture() {
        startActivity(Intent(this, CaptureActivity::class.java).putExtra(CaptureActivity.EXTRA_FOLDER, folderId))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMS) return
        val cameraIdx = permissions.indexOf(Manifest.permission.CAMERA)
        val cameraOk = cameraIdx < 0 || (grantResults.isNotEmpty() && grantResults[cameraIdx] == PackageManager.PERMISSION_GRANTED)
        if (cameraOk && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCapture()
        } else {
            toast(getString(R.string.capture_permission_denied))
        }
    }

    private fun onItemClick(item: Item) {
        when (item) {
            is Item.FolderItem -> openFolder(item.folder.id)
            is Item.SphereItem -> ViewerActivity.start(this, item.sphere.id)
            is Item.SessionItem -> if (!StitchJobs.isActive(item.meta.id)) showFailureDetails(item.meta)
        }
    }

    /** Détail complet de l'échec : le message tronqué de la vignette suffit rarement à comprendre. */
    private fun showFailureDetails(meta: CaptureSessionMeta) {
        val body = StringBuilder()
        body.append(meta.error ?: getString(R.string.status_interrupted)).append("\n\n")
        body.append(getString(R.string.failure_details, meta.shots.size, meta.targetCount, meta.attempts))
        AlertDialog.Builder(this)
            .setTitle(R.string.status_failed)
            .setMessage(body.toString())
            .setPositiveButton(R.string.action_retry) { _, _ -> retrySession(meta) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun onItemLongClick(anchor: View, item: Item) {
        when (item) {
            is Item.FolderItem -> {
                val menu = PopupMenu(this, anchor)
                menu.menu.add(0, 1, 0, R.string.action_rename)
                menu.menu.add(0, 2, 1, R.string.action_delete)
                menu.setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 -> renameFolder(item.folder)
                        2 -> confirmDeleteFolder(item.folder, item.count)
                    }
                    true
                }
                menu.show()
            }
            is Item.SphereItem -> {
                val sphere = item.sphere
                val menu = PopupMenu(this, anchor)
                menu.menu.add(0, 1, 0, R.string.action_rename)
                menu.menu.add(0, 4, 1, R.string.folder_move)
                if (store.hasSessionImages(sphere.sessionId)) {
                    menu.menu.add(0, 3, 2, R.string.action_retouch)
                }
                menu.menu.add(0, 2, 3, R.string.action_delete)
                menu.setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 -> renameSphere(sphere)
                        2 -> confirmDeleteSphere(sphere)
                        3 -> RetouchActivity.start(this, sphere.id)
                        4 -> chooseFolder(sphere)
                    }
                    true
                }
                menu.show()
            }
            is Item.SessionItem -> confirmDeleteSession(item.meta)
        }
    }

    // ---- Dossiers ----

    private fun createFolder() {
        promptName(R.string.folder_new_title, R.string.folder_hint,
            store.nextFolderName { getString(R.string.folder_default_name, it) }) { name ->
            openFolder(store.createFolder(name).id)
        }
    }

    private fun renameFolder(folder: Folder) {
        promptName(R.string.folder_rename_title, R.string.folder_hint, folder.name) { name ->
            store.renameFolder(folder.id, name)
        }
    }

    private fun confirmDeleteFolder(folder: Folder, count: Int) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.folder_delete_title, folder.name))
            .setMessage(if (count == 0) getString(R.string.folder_delete_body_empty)
            else getString(R.string.folder_delete_body, count))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                store.spheresIn(folder.id).forEach { Thumbs.invalidate("thumb-" + it.id) }
                store.deleteFolder(folder.id)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Choix du dossier de destination d'une sphère.
     *
     * Le déplacement casse les portails, dans les deux sens : une visite est fermée sur son
     * dossier, un lien vers une sphère partie ailleurs ne mènerait nulle part. On le dit avant, en
     * chiffrant ce qui sera perdu, plutôt que de le découvrir en ouvrant la visite.
     */
    private fun chooseFolder(sphere: Sphere) {
        val folders = store.folders()
        val ids = ArrayList<String>()
        val labels = ArrayList<CharSequence>()
        ids.add("")
        labels.add(getString(R.string.folder_root))
        folders.forEach { ids.add(it.id); labels.add(it.name) }
        ids.add(NEW_FOLDER)
        labels.add(getString(R.string.folder_new))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.folder_move_title, sphere.name))
            .setSingleChoiceItems(labels.toTypedArray(), ids.indexOf(sphere.folderId)) { dialog, which ->
                dialog.dismiss()
                val target = ids[which]
                if (target == NEW_FOLDER) {
                    promptName(R.string.folder_new_title, R.string.folder_hint,
                        store.nextFolderName { getString(R.string.folder_default_name, it) }) { name ->
                        moveSphere(sphere, store.createFolder(name).id)
                    }
                } else if (target != sphere.folderId) {
                    moveSphere(sphere, target)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun moveSphere(sphere: Sphere, target: String) {
        val lost = store.portalsAffectedByMove(sphere.id)
        val done = {
            store.moveSphere(sphere.id, target)
            val name = store.folder(target)?.name
            toast(if (name != null) getString(R.string.folder_moved, name) else getString(R.string.folder_moved_root))
        }
        if (lost == 0) { done(); return }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.folder_move_title, sphere.name))
            .setMessage(getString(R.string.folder_move_warning, lost))
            .setPositiveButton(R.string.action_ok) { _, _ -> done() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun promptName(title: Int, hint: Int, initial: String, onName: (String) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null)
        val edit = view.findViewById<EditText>(R.id.edit_name)
        edit.setHint(hint)
        edit.setText(initial)
        edit.setSelection(initial.length)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---- Sphères ----

    private fun renameSphere(sphere: Sphere) {
        promptName(R.string.rename_title, R.string.rename_hint, sphere.name) { name ->
            sphere.name = name
            store.update(sphere)
        }
    }

    private fun confirmDeleteSphere(sphere: Sphere) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_sphere_title, sphere.name))
            .setMessage(R.string.delete_sphere_body)
            .setPositiveButton(R.string.action_delete) { _, _ -> Thumbs.invalidate("thumb-" + sphere.id); store.delete(sphere.id) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDeleteSession(meta: CaptureSessionMeta) {
        if (StitchJobs.isActive(meta.id)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_session_title)
            .setMessage(R.string.delete_session_body)
            .setPositiveButton(R.string.action_delete) { _, _ -> store.deleteSession(meta.id) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun retrySession(meta: CaptureSessionMeta) {
        if (StitchJobs.isActive(meta.id)) return
        StitchService.enqueue(this, meta.id, StitchMode.SENSORS)
        toast(getString(R.string.capture_started_stitch))
    }

    // ---- Adapter ----

    private inner class GalleryAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (items[position] is Item.FolderItem) 1 else 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            val layout = if (item is Item.FolderItem) R.layout.item_folder else R.layout.item_sphere
            val v = convertView ?: LayoutInflater.from(this@GalleryActivity)
                .inflate(layout, parent, false).also { it.clipToOutline = true }

            if (item is Item.FolderItem) {
                v.findViewById<TextView>(R.id.name).text = item.folder.name
                v.findViewById<TextView>(R.id.meta).text =
                    if (item.count == 0) getString(R.string.folder_empty)
                    else getString(R.string.folder_count, item.count)
                return v
            }

            val thumb = v.findViewById<ImageView>(R.id.thumb)
            val progress = v.findViewById<ProgressBar>(R.id.progress)
            val errorIcon = v.findViewById<ImageView>(R.id.error_icon)
            val badge = v.findViewById<TextView>(R.id.badge)
            val name = v.findViewById<TextView>(R.id.name)
            val meta = v.findViewById<TextView>(R.id.meta)
            val actions = v.findViewById<View>(R.id.actions)
            val btnRetry = v.findViewById<Button>(R.id.btn_retry)
            val btnDelete = v.findViewById<Button>(R.id.btn_delete)

            when (item) {
                is Item.SphereItem -> {
                    val s = item.sphere
                    name.text = s.name
                    val when_ = DateUtils.getRelativeTimeSpanString(this@GalleryActivity, s.createdAt, true)
                    val parts = mutableListOf(when_.toString())
                    if (s.shots > 0) parts.add(getString(R.string.status_shots, s.shots))
                    if (s.method == "sensors") parts.add(getString(R.string.method_sensors_short))
                    if (s.coverage < 0.90) parts.add(getString(R.string.meta_coverage, (s.coverage * 100).toInt()))
                    if (s.portals.isNotEmpty()) parts.add(getString(R.string.meta_portals, s.portals.size))
                    meta.text = parts.joinToString(" · ")
                    Thumbs.load(thumb, store.thumbFile(s.id), "thumb-" + s.id)
                    progress.visibility = View.GONE
                    errorIcon.visibility = View.GONE
                    badge.visibility = if (s.usedShots in 1 until s.shots) View.VISIBLE else View.GONE
                    if (badge.visibility == View.VISIBLE) badge.text = getString(R.string.stitch_warning_dropped, s.shots - s.usedShots)
                    badge.setBackgroundResource(R.drawable.bg_badge_progress)
                    actions.visibility = View.GONE
                }
                is Item.SessionItem -> {
                    val m = item.meta
                    name.text = m.name
                    thumb.tag = null
                    thumb.setImageDrawable(null)
                    val job = StitchJobs.state(m.id)
                    val failed = m.state == SessionState.FAILED
                    when {
                        job != null -> {
                            val label = when (job.stage) {
                                StitchJobs.Stage.QUEUED -> getString(R.string.status_queued)
                                StitchJobs.Stage.LOADING -> getString(R.string.stitch_stage_loading)
                                StitchJobs.Stage.ALIGN -> getString(R.string.stitch_stage_align)
                                StitchJobs.Stage.COMPOSE -> getString(R.string.stitch_stage_compose)
                                StitchJobs.Stage.FINALIZE -> getString(R.string.stitch_stage_finalize)
                            }
                            meta.text = getString(R.string.status_stitching_pct, label, job.percent)
                            badge.text = getString(R.string.status_stitching)
                            badge.setBackgroundResource(R.drawable.bg_badge_progress)
                            progress.visibility = View.VISIBLE
                            errorIcon.visibility = View.GONE
                            actions.visibility = View.GONE
                        }
                        failed -> {
                            meta.text = (m.error ?: getString(R.string.status_failed)) + "\n" + getString(R.string.status_shots, m.shots.size)
                            badge.text = getString(R.string.status_failed)
                            badge.setBackgroundResource(R.drawable.bg_badge_error)
                            progress.visibility = View.GONE
                            errorIcon.visibility = View.VISIBLE
                            actions.visibility = View.VISIBLE
                            btnRetry.text = getString(R.string.action_retry)
                        }
                        else -> { // interrompu
                            meta.text = getString(R.string.status_interrupted) + " · " + getString(R.string.status_shots, m.shots.size)
                            badge.text = getString(R.string.status_interrupted)
                            badge.setBackgroundResource(R.drawable.bg_badge_error)
                            progress.visibility = View.GONE
                            errorIcon.visibility = View.VISIBLE
                            actions.visibility = View.VISIBLE
                        }
                    }
                    badge.visibility = View.VISIBLE
                    btnRetry.setOnClickListener { retrySession(m) }
                    btnDelete.setOnClickListener { confirmDeleteSession(m) }
                }
                // Traité plus haut, avant la recherche des vues d'une carte de sphère ; la branche
                // reste nécessaire à l'exhaustivité du when.
                is Item.FolderItem -> {}
            }
            return v
        }
    }

    companion object {
        private const val REQ_PERMS = 21
        private const val NEW_FOLDER = " new"
    }
}
