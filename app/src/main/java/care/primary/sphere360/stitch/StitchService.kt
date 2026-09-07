package care.primary.sphere360.stitch

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import care.primary.sphere360.App
import care.primary.sphere360.R
import care.primary.sphere360.data.SessionState
import care.primary.sphere360.data.Sphere
import care.primary.sphere360.gallery.GalleryActivity
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Service de premier plan qui assemble les sessions de capture en file d'attente,
 * avec notification de progression. L'assemblage survit à la fermeture de l'activité.
 */
class StitchService : Service() {

    companion object {
        private const val TAG = "StitchService"
        const val ACTION_STITCH = "care.primary.sphere360.STITCH"
        const val EXTRA_SESSION = "session"
        const val EXTRA_MODE = "mode"
        private const val NOTIF_PROGRESS = 100
        private const val NOTIF_DONE_BASE = 200

        fun enqueue(context: Context, sessionId: String, mode: StitchMode) {
            val i = Intent(context, StitchService::class.java)
                .setAction(ACTION_STITCH)
                .putExtra(EXTRA_SESSION, sessionId)
                .putExtra(EXTRA_MODE, mode.name)
            context.startForegroundService(i)
        }
    }

    private val queue = LinkedBlockingQueue<Pair<String, StitchMode>>()
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var doneCounter = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildProgressNotification(getString(R.string.status_queued), 0, true, null))
        val id = intent?.getStringExtra(EXTRA_SESSION)
        if (id != null) {
            val mode = try { StitchMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "") } catch (e: Exception) { StitchMode.SENSORS }
            StitchJobs.update(id, StitchJobs.Stage.QUEUED, 0)
            App.store.loadSession(id)?.let { it.state = SessionState.STITCHING; it.error = null; App.store.saveSession(it) }
            queue.add(id to mode)
            ensureWorker()
        } else if (queue.isEmpty() && worker == null) {
            stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_PROGRESS, n)
        }
    }

    @Synchronized
    private fun ensureWorker() {
        if (worker != null) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sphere360:stitch").apply { acquire(30 * 60 * 1000L) }
        worker = Thread({
            try {
                while (true) {
                    val item = queue.poll(3, TimeUnit.SECONDS) ?: break
                    process(item.first, item.second)
                }
            } finally {
                synchronized(this) { worker = null }
                try { wakeLock?.release() } catch (_: Exception) {}
                wakeLock = null
                stopSelfSafely()
            }
        }, "stitch-worker").apply { start() }
    }

    private fun stopSelfSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun process(sessionId: String, mode: StitchMode) {
        val store = App.store
        val meta = store.loadSession(sessionId) ?: run { StitchJobs.remove(sessionId); return }
        // Réassemblage d'une sphère existante : on remplace la sphère au lieu d'en créer une autre.
        val existing = store.all().firstOrNull { it.sessionId == sessionId }
        val name = meta.name
        updateProgress(name, StitchJobs.Stage.LOADING, 0)
        try {
            val sphereId = existing?.id ?: store.newId()
            val dir = store.sphereDir(sphereId).apply { mkdirs() }
            val result = SphereStitcher { stage, pct ->
                StitchJobs.update(sessionId, stage, pct)
                updateProgress(name, stage, pct)
            }.stitch(store.sessionDir(sessionId), meta, store.equirectFile(sphereId), store.thumbFile(sphereId), mode)

            val sphere = Sphere(
                sphereId, existing?.name ?: name, existing?.createdAt ?: System.currentTimeMillis(),
                result.width, result.height, result.defaultYaw, existing?.defaultPitch ?: 0.0,
                existing?.portals ?: mutableListOf(), false, result.totalShots, result.usedShots,
                if (result.method == StitchMethod.FEATURES) "features" else "sensors", result.coverage,
                sessionId
            )
            if (existing != null) sphere.defaultYaw = existing.defaultYaw
            store.add(sphere)
            meta.state = SessionState.DONE
            store.saveSession(meta)
            care.primary.sphere360.util.Thumbs.invalidate("thumb-$sphereId")
            notifyDone(sphere, result)
            Log.i(TAG, "Sphère $sphereId assemblée (${result.usedShots}/${result.totalShots} photos, ${result.method})")
        } catch (e: StitchException) {
            Log.w(TAG, "Assemblage échoué : ${e.kind} ${e.message}")
            meta.state = SessionState.FAILED
            meta.error = errorMessage(e)
            meta.attempts++
            store.saveSession(meta)
            notifyFailed(name, meta.error ?: "")
        } catch (t: Throwable) {
            Log.e(TAG, "Assemblage planté", t)
            meta.state = SessionState.FAILED
            meta.error = getString(R.string.stitch_error_generic, t.message ?: t.javaClass.simpleName)
            meta.attempts++
            store.saveSession(meta)
            notifyFailed(name, meta.error ?: "")
        } finally {
            StitchJobs.remove(sessionId)
        }
    }

    private fun errorMessage(e: StitchException): String = when (e.kind) {
        StitchException.Kind.NEED_MORE -> getString(R.string.stitch_error_need_more)
        StitchException.Kind.HOMOGRAPHY -> getString(R.string.stitch_error_homography)
        StitchException.Kind.ADJUST -> getString(R.string.stitch_error_adjust)
        StitchException.Kind.PARTIAL -> getString(R.string.stitch_error_partial, e.used, e.total)
        StitchException.Kind.MEMORY -> getString(R.string.stitch_error_memory)
        StitchException.Kind.GENERIC -> getString(R.string.stitch_error_generic, e.message ?: "?")
    }

    fun stageLabel(stage: StitchJobs.Stage): String = when (stage) {
        StitchJobs.Stage.QUEUED -> getString(R.string.status_queued)
        StitchJobs.Stage.LOADING -> getString(R.string.stitch_stage_loading)
        StitchJobs.Stage.ALIGN -> getString(R.string.stitch_stage_align)
        StitchJobs.Stage.COMPOSE -> getString(R.string.stitch_stage_compose)
        StitchJobs.Stage.FINALIZE -> getString(R.string.stitch_stage_finalize)
    }

    private fun updateProgress(name: String, stage: StitchJobs.Stage, pct: Int) {
        val n = buildProgressNotification(stageLabel(stage), pct, stage == StitchJobs.Stage.ALIGN, name)
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_PROGRESS, n)
    }

    private fun galleryIntent(): PendingIntent {
        val i = Intent(this, GalleryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildProgressNotification(text: String, pct: Int, indeterminate: Boolean, name: String?): Notification =
        Notification.Builder(this, App.CHANNEL_STITCH)
            .setSmallIcon(R.drawable.ic_notif_sphere)
            .setContentTitle(if (name != null) getString(R.string.stitch_notif_title, name) else getString(R.string.status_stitching))
            .setContentText(text)
            .setProgress(100, pct, indeterminate && pct < 60)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(galleryIntent())
            .build()

    private fun notifyDone(sphere: Sphere, result: StitchResult) {
        val parts = mutableListOf(getString(R.string.stitch_notif_done_body, sphere.name))
        if (result.method == StitchMethod.SENSORS) parts.add(getString(R.string.stitch_method_sensors))
        if (result.usedShots < result.totalShots) parts.add(getString(R.string.stitch_warning_dropped, result.totalShots - result.usedShots))
        val text = parts.joinToString(" · ")
        val n = Notification.Builder(this, App.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notif_sphere)
            .setContentTitle(getString(R.string.stitch_notif_done_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(galleryIntent())
            .build()
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_DONE_BASE + (doneCounter++ % 50), n)
    }

    private fun notifyFailed(name: String, error: String) {
        val n = Notification.Builder(this, App.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notif_sphere)
            .setContentTitle(getString(R.string.stitch_notif_failed_title) + " · " + name)
            .setContentText(error)
            .setStyle(Notification.BigTextStyle().bigText(error))
            .setAutoCancel(true)
            .setContentIntent(galleryIntent())
            .build()
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_DONE_BASE + (doneCounter++ % 50), n)
    }
}
