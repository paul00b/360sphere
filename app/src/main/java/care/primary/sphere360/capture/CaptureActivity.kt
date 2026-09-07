package care.primary.sphere360.capture

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.MediaActionSound
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import care.primary.sphere360.App
import care.primary.sphere360.R
import care.primary.sphere360.data.CameraMeta
import care.primary.sphere360.data.CaptureSessionMeta
import care.primary.sphere360.data.SessionState
import care.primary.sphere360.data.ShotMeta
import care.primary.sphere360.data.TourStore
import care.primary.sphere360.stitch.StitchService
import care.primary.sphere360.util.Bg
import care.primary.sphere360.util.dpi
import care.primary.sphere360.util.onSystemInsets
import care.primary.sphere360.util.toast
import java.io.File

/**
 * Capture guidée façon Photo Sphere : l'utilisateur pivote sur place, amène le réticule sur
 * chaque cible et la photo se déclenche automatiquement quand l'alignement est bon et le
 * téléphone immobile.
 */
class CaptureActivity : Activity(), CameraController.Callbacks, TextureView.SurfaceTextureListener {

    companion object {
        private const val CAPTURE_ANGLE_DEG = 3.0
        private const val MAX_ANGULAR_SPEED = 0.35 // rad/s
        private const val ARM_DELAY_MS = 180L
        private const val MIN_INTERVAL_MS = 450L
        private const val WATCHDOG_MS = 3500L
        private const val REQ_CAMERA = 11
    }

    private lateinit var store: TourStore
    private lateinit var root: FrameLayout
    private lateinit var preview: TextureView
    private lateinit var overlay: GuidanceOverlay
    private lateinit var topBar: View
    private lateinit var progressText: TextView
    private lateinit var btnFinish: Button
    private lateinit var btnClose: ImageButton
    private lateinit var hint: TextView
    private lateinit var intro: View
    private lateinit var btnStart: Button

    private lateinit var camera: CameraController
    private var cameraInfo: CameraController.CameraInfo? = null
    private lateinit var tracker: OrientationTracker

    private var plan: CapturePlan? = null
    private var dirs: Array<Vec3> = emptyArray()
    private var captured = BooleanArray(0)
    private var capturedCount = 0
    private var yaw0 = 0.0

    private var running = false
    private var finishing = false
    private var capturing = false
    private var pendingShot: ShotMeta? = null
    private var armedSince = 0L
    private var lastCaptureAt = 0L
    private var currentHint = 0
    private var watchdog: Runnable? = null

    private var session: CaptureSessionMeta? = null
    private var shutter: MediaActionSound? = null
    private var topInset = 0
    private var bottomInset = 0
    private var lastLayoutKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = App.store
        setContentView(R.layout.activity_capture)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        edgeToEdge()

        root = findViewById(R.id.root)
        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        topBar = findViewById(R.id.top_bar)
        progressText = findViewById(R.id.progress_text)
        btnFinish = findViewById(R.id.btn_finish)
        btnClose = findViewById(R.id.btn_close)
        hint = findViewById(R.id.hint)
        intro = findViewById(R.id.intro)
        btnStart = findViewById(R.id.btn_start)

        tracker = OrientationTracker(this) { onOrientation() }
        if (!tracker.available) {
            toast(getString(R.string.capture_no_sensor))
            finish()
            return
        }

        camera = CameraController(this, this)
        val info = camera.selectCamera()
        if (info == null) {
            toast(getString(R.string.capture_camera_error, "aucune caméra arrière"))
            finish()
            return
        }
        cameraInfo = info
        val p = CaptureGrid.build(info.hfovPortraitDeg, info.vfovPortraitDeg)
        plan = p
        captured = BooleanArray(p.size)
        overlay.plan = p
        overlay.captured = captured
        overlay.rotation = tracker.rotation

        val id = store.newId()
        session = CaptureSessionMeta(
            id, System.currentTimeMillis(),
            store.nextName { getString(R.string.sphere_default_name, it) },
            CameraMeta(info.hfovPortraitDeg, info.vfovPortraitDeg, info.jpegPortraitWidth, info.jpegPortraitHeight, info.sensorOrientation),
            mutableListOf(), SessionState.CAPTURING, targetCount = p.size
        )
        store.sessionDir(id).mkdirs()

        val topPad = topBar.paddingTop
        root.onSystemInsets { top, bottom ->
            topInset = top; bottomInset = bottom
            topBar.setPadding(topBar.paddingLeft, topPad + top, topBar.paddingRight, topBar.paddingBottom)
            overlay.bottomInset = bottom
            layoutPreview()
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPreview() }

        preview.surfaceTextureListener = this
        btnStart.setOnClickListener { startGuidance() }
        btnClose.setOnClickListener { confirmCancel() }
        btnFinish.setOnClickListener { finishEarly() }
        btnFinish.visibility = View.INVISIBLE
        updateProgress()
        setHint(R.string.capture_hint_aim)
        hint.visibility = View.INVISIBLE

        if (!hasCameraPermission()) requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    @Suppress("DEPRECATION")
    private fun edgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (preview.isAvailable) openCamera()
            } else {
                toast(getString(R.string.capture_permission_denied))
                cancelAndExit()
            }
        }
    }

    // ---- Cycle de vie ----

    override fun onResume() {
        super.onResume()
        if (!::tracker.isInitialized) return
        tracker.start()
        if (hasCameraPermission() && preview.isAvailable) openCamera()
    }

    override fun onPause() {
        super.onPause()
        if (::tracker.isInitialized) tracker.stop()
        if (::camera.isInitialized) camera.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::camera.isInitialized) camera.release()
        shutter?.release()
        val s = session
        // Session jamais démarrée ou sans photo : on nettoie.
        if (s != null && !finishing && s.shots.isEmpty()) store.deleteSession(s.id)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { confirmCancel() }

    // ---- TextureView ----

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (hasCameraPermission()) openCamera()
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { if (::camera.isInitialized) camera.close(); return true }

    private fun openCamera() {
        val info = cameraInfo ?: return
        val st = preview.surfaceTexture ?: return
        camera.open(info, st)
    }

    /** Place la prévisualisation (ratio 3:4) sous la barre du haut, au-dessus de la mini-carte. */
    private fun layoutPreview() {
        val info = cameraInfo ?: return
        val rw = root.width; val rh = root.height
        if (rw == 0 || rh == 0) return
        val key = "$rw,$rh,$topInset,$bottomInset"
        if (key == lastLayoutKey) return
        lastLayoutKey = key

        val topBarH = topInset + dpi(64)
        val minimapZone = overlay.minimapHeight() + dpi(16) + bottomInset + dpi(24)
        val available = (rh - topBarH - minimapZone).coerceAtLeast(rh / 3f)
        val aspect = info.previewPortraitWidth.toFloat() / info.previewPortraitHeight
        var w = rw.toFloat(); var h = w / aspect
        if (h > available) { h = available; w = h * aspect }
        val left = (rw - w) / 2
        val top = topBarH + (available - h) / 2

        val lp = preview.layoutParams as FrameLayout.LayoutParams
        lp.width = w.toInt(); lp.height = h.toInt(); lp.leftMargin = left.toInt(); lp.topMargin = top.toInt()
        preview.layoutParams = lp
        overlay.previewRect.set(left, top, left + w, top + h)
        overlay.setFov(info.hfovPortraitDeg, info.vfovPortraitDeg)

        val hlp = hint.layoutParams as FrameLayout.LayoutParams
        hlp.bottomMargin = (minimapZone + dpi(4)).toInt()
        hint.layoutParams = hlp
    }

    // ---- CameraController.Callbacks ----

    override fun onCameraConfigured(info: CameraController.CameraInfo) {
        if (running) Bg.main.postDelayed({ camera.lockExposureAndFocus() }, 700)
    }

    override fun onCameraError(message: String) {
        toast(getString(R.string.capture_camera_error, message))
        cancelAndExit()
    }

    override fun onStillCaptured(jpeg: ByteArray) {
        val shot = pendingShot ?: return
        val s = session ?: return
        try {
            File(store.sessionDir(s.id), shot.file).writeBytes(jpeg)
        } catch (e: Exception) {
            Bg.onMain { onStillFailed() }
            return
        }
        Bg.onMain { onShotSaved(shot) }
    }

    override fun onStillFailed() {
        Bg.onMain {
            capturing = false
            pendingShot = null
            overlay.capturing = false
            cancelWatchdog()
        }
    }

    // ---- Guidage ----

    private fun startGuidance() {
        if (!tracker.hasRotation) {
            // les capteurs n'ont pas encore émis : on réessaie très vite
            Bg.main.postDelayed({ startGuidance() }, 120)
            return
        }
        val p = plan ?: return
        intro.visibility = View.GONE
        hint.visibility = View.VISIBLE
        btnFinish.visibility = View.VISIBLE
        yaw0 = SphereMath.yawOf(SphereMath.cameraForward(tracker.rotation))
        dirs = Array(p.size) { i ->
            val t = p.targets[i]
            SphereMath.dirFromYawPitch(t.yawDeg * SphereMath.DEG + yaw0, t.pitchDeg * SphereMath.DEG)
        }
        overlay.yaw0 = yaw0
        overlay.dirs = dirs
        overlay.active = true
        running = true
        shutter = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
        Bg.main.postDelayed({ camera.lockExposureAndFocus() }, 700)
    }

    private fun onOrientation() {
        if (!running || finishing) { overlay.invalidate(); return }
        val r = tracker.rotation
        val f = SphereMath.cameraForward(r)
        var best = -1
        var bestAngle = Double.MAX_VALUE
        for (i in dirs.indices) {
            if (captured[i]) continue
            val a = SphereMath.angleBetween(f, dirs[i])
            if (a < bestAngle) { bestAngle = a; best = i }
        }
        overlay.nextIndex = best
        val now = SystemClock.elapsedRealtime()
        val aligned = best >= 0 && bestAngle < CAPTURE_ANGLE_DEG * SphereMath.DEG
        val still = tracker.angularSpeed < MAX_ANGULAR_SPEED
        overlay.armed = aligned

        if (best >= 0) {
            if (aligned && still && !capturing && now - lastCaptureAt > MIN_INTERVAL_MS) {
                if (armedSince == 0L) armedSince = now
                else if (now - armedSince > ARM_DELAY_MS) capture(best)
            } else if (!aligned) {
                armedSince = 0L
            }
            when {
                capturing -> setHint(R.string.capture_hint_hold)
                aligned -> setHint(R.string.capture_hint_hold)
                SphereMath.projectToCamera(r, dirs[best]) != null && bestAngle < 35 * SphereMath.DEG -> setHint(R.string.capture_hint_aim)
                else -> setHint(R.string.capture_hint_turn)
            }
        }
        overlay.invalidate()
    }

    private fun capture(index: Int) {
        val s = session ?: return
        val r = tracker.rotation
        val f = SphereMath.cameraForward(r)
        val shot = ShotMeta(
            "shot_%03d.jpg".format(s.shots.size),
            Math.toDegrees(SphereMath.normalizeAngle(SphereMath.yawOf(f) - yaw0)),
            Math.toDegrees(SphereMath.pitchOf(f)),
            Math.toDegrees(SphereMath.rollOf(r)),
            index
        )
        capturing = true
        armedSince = 0L
        pendingShot = shot
        overlay.capturing = true
        camera.captureStill()
        val w = Runnable {
            if (capturing && pendingShot === shot) {
                capturing = false; pendingShot = null; overlay.capturing = false
            }
        }
        watchdog = w
        Bg.main.postDelayed(w, WATCHDOG_MS)
    }

    private fun cancelWatchdog() {
        watchdog?.let { Bg.main.removeCallbacks(it) }
        watchdog = null
    }

    private fun onShotSaved(shot: ShotMeta) {
        val s = session ?: return
        val p = plan ?: return
        cancelWatchdog()
        capturing = false
        pendingShot = null
        overlay.capturing = false
        lastCaptureAt = SystemClock.elapsedRealtime()
        if (finishing) return
        if (shot.targetIndex in captured.indices && !captured[shot.targetIndex]) {
            captured[shot.targetIndex] = true
            capturedCount++
        }
        s.shots.add(shot)
        store.saveSession(s)
        shutter?.play(MediaActionSound.SHUTTER_CLICK)
        overlay.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        updateProgress()
        if (capturedCount >= p.size) completeCapture()
    }

    private fun updateProgress() {
        val p = plan ?: return
        progressText.text = getString(R.string.capture_progress, capturedCount, p.size)
    }

    private fun setHint(res: Int) {
        if (currentHint == res) return
        currentHint = res
        hint.setText(res)
    }

    // ---- Fin / annulation ----

    private fun completeCapture() {
        val s = session ?: return
        if (finishing) return
        finishing = true
        running = false
        overlay.active = false
        overlay.nextIndex = -1
        overlay.invalidate()
        setHint(R.string.capture_hint_done)
        s.state = SessionState.CAPTURED
        store.saveSession(s)
        StitchService.enqueue(this, s.id, false)
        toast(getString(R.string.capture_started_stitch))
        Bg.main.postDelayed({ finish() }, 700)
    }

    private fun finishEarly() {
        val p = plan ?: return
        if (!running) return
        if (capturedCount < p.minShotsToFinish) {
            toast(getString(R.string.capture_need_more, p.minShotsToFinish))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_finish_early_title)
            .setMessage(getString(R.string.capture_finish_early_body, p.size - capturedCount))
            .setPositiveButton(R.string.capture_finish_now) { _, _ -> completeCapture() }
            .setNegativeButton(R.string.action_continue, null)
            .show()
    }

    private fun confirmCancel() {
        if (finishing) return
        if (capturedCount == 0) { cancelAndExit(); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_cancel_title)
            .setMessage(getString(R.string.capture_cancel_body, capturedCount))
            .setPositiveButton(R.string.capture_cancel_confirm) { _, _ -> cancelAndExit() }
            .setNegativeButton(R.string.action_continue, null)
            .show()
    }

    private fun cancelAndExit() {
        running = false
        finishing = true
        session?.let { store.deleteSession(it.id) }
        finish()
    }
}
