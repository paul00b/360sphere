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
import care.primary.sphere360.data.SessionState
import care.primary.sphere360.data.Sphere
import care.primary.sphere360.data.TourStore
import care.primary.sphere360.stitch.StitchJobs
import care.primary.sphere360.stitch.StitchService
import care.primary.sphere360.util.Thumbs
import care.primary.sphere360.util.padBottomWithNavBar
import care.primary.sphere360.util.padTopWithStatusBar
import care.primary.sphere360.util.toast
import care.primary.sphere360.viewer.ViewerActivity

/** Galerie des sphères capturées et des assemblages en cours ou en échec. */
open class GalleryActivity : Activity() {

    private sealed class Item {
        class SphereItem(val sphere: Sphere) : Item()
        class SessionItem(val meta: CaptureSessionMeta) : Item()
    }

    private lateinit var store: TourStore
    private lateinit var grid: GridView
    private lateinit var emptyView: View
    private lateinit var fab: ImageButton
    private val adapter = GalleryAdapter()
    private var items: List<Item> = emptyList()
    private val storeListener = { refresh() }
    private val jobsListener = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = App.store
        setContentView(R.layout.activity_gallery)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.padTopWithStatusBar()
        toolbar.inflateMenu(R.menu.gallery)
        toolbar.setOnMenuItemClickListener { onMenu(it.itemId) }
        grid = findViewById(R.id.grid)
        grid.adapter = adapter
        grid.padBottomWithNavBar()
        emptyView = findViewById(R.id.empty_view)
        fab = findViewById(R.id.fab)
        fab.setOnClickListener { startCapture() }
        grid.setOnItemClickListener { _, _, position, _ -> onItemClick(items[position]) }
        grid.setOnItemLongClickListener { _, view, position, _ -> onItemLongClick(view, items[position]); true }
        cleanupStaleSessions()
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

    protected open fun onMenu(id: Int): Boolean {
        when (id) {
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

    protected fun refresh() {
        val list = ArrayList<Item>()
        store.listSessions().filter { it.state != SessionState.CAPTURING }.forEach { list.add(Item.SessionItem(it)) }
        store.all().forEach { list.add(Item.SphereItem(it)) }
        items = list
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        onItemsRefreshed(list.isEmpty())
    }

    /** Point d'extension (V2 : carte de démo). */
    protected open fun onItemsRefreshed(empty: Boolean) {}

    // ---- Actions ----

    private fun startCapture() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        startActivity(Intent(this, CaptureActivity::class.java))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMS) return
        val cameraIdx = permissions.indexOf(Manifest.permission.CAMERA)
        val cameraOk = cameraIdx < 0 || (grantResults.isNotEmpty() && grantResults[cameraIdx] == PackageManager.PERMISSION_GRANTED)
        if (cameraOk && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, CaptureActivity::class.java))
        } else {
            toast(getString(R.string.capture_permission_denied))
        }
    }

    private fun onItemClick(item: Item) {
        when (item) {
            is Item.SphereItem -> ViewerActivity.start(this, item.sphere.id)
            is Item.SessionItem -> if (item.meta.state == SessionState.FAILED || !StitchJobs.isActive(item.meta.id)) retrySession(item.meta)
        }
    }

    private fun onItemLongClick(anchor: View, item: Item) {
        when (item) {
            is Item.SphereItem -> {
                val menu = PopupMenu(this, anchor)
                menu.menu.add(0, 1, 0, R.string.action_rename)
                menu.menu.add(0, 2, 1, R.string.action_delete)
                menu.setOnMenuItemClickListener {
                    when (it.itemId) { 1 -> renameSphere(item.sphere); 2 -> confirmDeleteSphere(item.sphere) }
                    true
                }
                menu.show()
            }
            is Item.SessionItem -> confirmDeleteSession(item.meta)
        }
    }

    private fun renameSphere(sphere: Sphere) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null)
        val edit = view.findViewById<EditText>(R.id.edit_name)
        edit.setText(sphere.name)
        edit.setSelection(sphere.name.length)
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(view)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotEmpty()) { sphere.name = name; store.update(sphere) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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
        val relaxed = meta.attempts >= 1
        StitchService.enqueue(this, meta.id, relaxed)
        toast(getString(R.string.capture_started_stitch))
    }

    // ---- Adapter ----

    private inner class GalleryAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@GalleryActivity).inflate(R.layout.item_sphere, parent, false).also { it.clipToOutline = true }
            val thumb = v.findViewById<ImageView>(R.id.thumb)
            val progress = v.findViewById<ProgressBar>(R.id.progress)
            val errorIcon = v.findViewById<ImageView>(R.id.error_icon)
            val badge = v.findViewById<TextView>(R.id.badge)
            val name = v.findViewById<TextView>(R.id.name)
            val meta = v.findViewById<TextView>(R.id.meta)
            val actions = v.findViewById<View>(R.id.actions)
            val btnRetry = v.findViewById<Button>(R.id.btn_retry)
            val btnDelete = v.findViewById<Button>(R.id.btn_delete)

            when (val item = items[position]) {
                is Item.SphereItem -> {
                    val s = item.sphere
                    name.text = s.name
                    val when_ = DateUtils.getRelativeTimeSpanString(this@GalleryActivity, s.createdAt, true)
                    val parts = mutableListOf(when_.toString())
                    if (s.shots > 0) parts.add(getString(R.string.status_shots, s.shots))
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
                    val interrupted = !failed && job == null
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
            }
            return v
        }
    }

    companion object {
        private const val REQ_PERMS = 21
    }
}
