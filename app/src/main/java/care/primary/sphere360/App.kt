package care.primary.sphere360

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import care.primary.sphere360.data.TourStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        store = TourStore(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STITCH, getString(R.string.stitch_notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, getString(R.string.stitch_notif_done_title), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CHANNEL_STITCH = "stitch"
        const val CHANNEL_DONE = "stitch_done"
        lateinit var store: TourStore
            private set
    }
}
