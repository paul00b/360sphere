package care.primary.sphere360.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Retour vibratoire de la capture. Il remplace le bruit d'obturateur : une capture guidée déclenche
 * une trentaine de prises en pivotant sur soi, et autant de déclics est fatigant à l'usage.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (t: Throwable) {
        null
    }

    private val available: Boolean = vibrator?.hasVibrator() == true

    /** Vibration brève confirmant qu'une photo est enregistrée. */
    fun shotTaken() {
        val v = vibrator ?: return
        if (!available) return
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (t: Throwable) {
            // certains appareils refusent l'effet prédéfini : on ne bloque pas la capture pour ça
        }
    }

    /** Vibration plus longue marquant la fin de la capture. */
    fun captureComplete() {
        val v = vibrator ?: return
        if (!available) return
        try {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 90, 90), -1))
        } catch (t: Throwable) {
        }
    }
}
