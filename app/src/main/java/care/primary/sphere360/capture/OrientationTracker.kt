package care.primary.sphere360.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Suit l'orientation de l'appareil (matrice de rotation appareil → monde) et sa vitesse angulaire.
 * Utilise GAME_ROTATION_VECTOR (gyro + accéléro, sans magnétomètre : pas de sauts dus au
 * métal/électronique en intérieur), sinon ROTATION_VECTOR.
 */
class OrientationTracker(context: Context, private val onUpdate: () -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** Matrice 3x3 (ligne par ligne) appareil → monde, mise à jour en place. */
    val rotation = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    var hasRotation = false
        private set
    /** Vitesse angulaire lissée (rad/s). */
    var angularSpeed = 0.0
        private set

    val available: Boolean get() = rotationSensor != null

    private var lastForward: Vec3? = null
    private var lastTimestamp = 0L

    fun start() {
        rotationSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sm.unregisterListener(this)
        hasRotation = false
        lastForward = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                // Certains appareils fournissent 5 valeurs : getRotationMatrixFromVector en accepte 3 ou 4.
                val v = if (e.values.size >= 4) e.values.copyOf(4) else e.values.copyOf(3)
                SensorManager.getRotationMatrixFromVector(rotation, v)
                hasRotation = true
                if (gyro == null) estimateSpeedFromRotation(e.timestamp)
                onUpdate()
            }
            Sensor.TYPE_GYROSCOPE -> {
                val w = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
                angularSpeed = angularSpeed * 0.6 + w * 0.4
            }
        }
    }

    private fun estimateSpeedFromRotation(timestamp: Long) {
        val f = SphereMath.cameraForward(rotation)
        val prev = lastForward
        if (prev != null && lastTimestamp != 0L) {
            val dt = (timestamp - lastTimestamp) / 1e9
            if (dt > 1e-3) {
                val w = SphereMath.angleBetween(prev, f) / dt
                angularSpeed = angularSpeed * 0.6 + w * 0.4
            }
        }
        lastForward = f
        lastTimestamp = timestamp
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
