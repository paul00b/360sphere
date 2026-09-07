package care.primary.sphere360.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Threads utilitaires : main handler + pool IO léger. */
object Bg {
    val main: Handler = Handler(Looper.getMainLooper())
    val io: ExecutorService = Executors.newFixedThreadPool(3)

    fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun io(block: () -> Unit) {
        io.execute(block)
    }
}
