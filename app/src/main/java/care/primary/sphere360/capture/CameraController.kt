package care.primary.sphere360.capture

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import care.primary.sphere360.util.Bg
import kotlin.math.abs
import kotlin.math.atan

/**
 * Enveloppe Camera2 : prévisualisation sur TextureView, verrouillage AE/AWB/AF,
 * capture JPEG vers un ImageReader. Toutes les callbacks caméra tournent sur un thread dédié.
 */
class CameraController(private val activity: Activity, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onCameraConfigured(info: CameraInfo)
        fun onCameraError(message: String)
        fun onStillCaptured(jpeg: ByteArray)
        fun onStillFailed()
    }

    class CameraInfo(
        val cameraId: String,
        val sensorOrientation: Int,
        val previewSize: Size,
        val jpegSize: Size,
        val hfovPortraitDeg: Double,
        val vfovPortraitDeg: Double
    ) {
        private val swap get() = sensorOrientation % 180 == 90
        val previewPortraitWidth: Int get() = if (swap) previewSize.height else previewSize.width
        val previewPortraitHeight: Int get() = if (swap) previewSize.width else previewSize.height
        val jpegPortraitWidth: Int get() = if (swap) jpegSize.height else jpegSize.width
        val jpegPortraitHeight: Int get() = if (swap) jpegSize.width else jpegSize.height
    }

    private val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("Camera2").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var afContinuousSupported = true
    @Volatile private var closed = true
    @Volatile private var locked = false

    var info: CameraInfo? = null
        private set

    // ---- Sélection ----

    fun selectCamera(): CameraInfo? {
        return try {
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val previewSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: continue
                val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: continue
                val preview = pickPreview(previewSizes) ?: continue
                val jpeg = pickJpeg(jpegSizes, preview) ?: continue
                val orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val modes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                afContinuousSupported = modes?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
                val (hL, vL) = landscapeFov(c, jpeg)
                val swap = orientation % 180 == 90
                return CameraInfo(id, orientation, preview, jpeg, if (swap) vL else hL, if (swap) hL else vL)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun aspect(s: Size) = s.width.toDouble() / s.height
    private fun area(s: Size) = s.width.toLong() * s.height

    private fun pickPreview(sizes: Array<Size>): Size? {
        val fourThirds = sizes.filter { abs(aspect(it) - 4.0 / 3.0) < 0.02 && it.width <= 1600 && it.width >= 640 }
        if (fourThirds.isNotEmpty()) return fourThirds.maxByOrNull { area(it) }
        return sizes.filter { it.width <= 1600 && it.width >= 640 }.minByOrNull { abs(aspect(it) - 4.0 / 3.0) }
            ?: sizes.minByOrNull { area(it) }
    }

    private fun pickJpeg(sizes: Array<Size>, preview: Size): Size? {
        val same = sizes.filter { abs(aspect(it) - aspect(preview)) < 0.02 }
        val pool = if (same.isNotEmpty()) same else sizes.toList()
        val big = pool.filter { area(it) >= 1_700_000L }
        return big.minByOrNull { area(it) } ?: pool.maxByOrNull { area(it) }
    }

    /** Champ de vue (paysage) horizontal/vertical en degrés pour la taille de sortie donnée. */
    private fun landscapeFov(c: CameraCharacteristics, out: Size): Pair<Double, Double> {
        val fallback = 66.0 to 50.0
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return fallback
        val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: return fallback
        val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return fallback
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return fallback
        if (focals.isEmpty() || focals[0] <= 0f || pixelArray.width <= 0) return fallback
        val mmPerPx = physical.width.toDouble() / pixelArray.width
        var wMm = active.width() * mmPerPx
        var hMm = active.height() * mmPerPx
        val outAspect = aspect(out)
        if (wMm / hMm > outAspect) wMm = hMm * outAspect else hMm = wMm / outAspect
        val f = focals[0].toDouble()
        val h = Math.toDegrees(2 * atan(wMm / (2 * f)))
        val v = Math.toDegrees(2 * atan(hMm / (2 * f)))
        if (h.isNaN() || v.isNaN() || h < 30 || h > 120 || v < 20 || v > 110) return fallback
        return h to v
    }

    // ---- Ouverture / session ----

    @SuppressLint("MissingPermission")
    fun open(cameraInfo: CameraInfo, texture: SurfaceTexture) {
        close()
        info = cameraInfo
        closed = false
        locked = false
        texture.setDefaultBufferSize(cameraInfo.previewSize.width, cameraInfo.previewSize.height)
        previewSurface = Surface(texture)
        reader = ImageReader.newInstance(cameraInfo.jpegSize.width, cameraInfo.jpegSize.height, ImageFormat.JPEG, 3).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    callbacks.onStillCaptured(bytes)
                } finally {
                    img.close()
                }
            }, handler)
        }
        try {
            manager.openCamera(cameraInfo.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) { camera.close(); return }
                    device = camera
                    createSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); device = null
                    if (!closed) fail("caméra déconnectée")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); device = null
                    if (!closed) fail("erreur caméra $error")
                }
            }, handler)
        } catch (e: Exception) {
            fail(e.message ?: "openCamera")
        }
    }

    private fun createSession() {
        val d = device ?: return
        val ps = previewSurface ?: return
        val rs = reader?.surface ?: return
        try {
            @Suppress("DEPRECATION")
            d.createCaptureSession(listOf(ps, rs), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed) { s.close(); return }
                    session = s
                    startPreview()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { fail("configuration de la caméra impossible") }
            }, handler)
        } catch (e: Exception) {
            fail(e.message ?: "createCaptureSession")
        }
    }

    private fun startPreview() {
        val d = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            if (afContinuousSupported) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            previewBuilder = b
            s.setRepeatingRequest(b.build(), null, handler)
            val i = info
            if (i != null) Bg.onMain { callbacks.onCameraConfigured(i) }
        } catch (e: Exception) {
            fail(e.message ?: "preview")
        }
    }

    /**
     * Verrouille exposition, balance des blancs et mise au point pour toute la sphère :
     * indispensable pour des raccords homogènes entre les photos.
     */
    fun lockExposureAndFocus() {
        handler.post {
            val s = session ?: return@post
            val b = previewBuilder ?: return@post
            if (closed) return@post
            try {
                if (afContinuousSupported) {
                    b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                    s.capture(b.build(), null, handler)
                    b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                }
                b.set(CaptureRequest.CONTROL_AE_LOCK, true)
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                s.setRepeatingRequest(b.build(), null, handler)
                locked = true
            } catch (e: Exception) {
                // non bloquant : on continue sans verrouillage
            }
        }
    }

    /** Déclenche une photo JPEG (résultat via onStillCaptured, sur le thread caméra). */
    fun captureStill() {
        handler.post {
            val d = device ?: return@post
            val s = session ?: return@post
            val r = reader ?: return@post
            if (closed) return@post
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(r.surface)
                b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                if (afContinuousSupported) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AE_LOCK, locked)
                b.set(CaptureRequest.CONTROL_AWB_LOCK, locked)
                b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
                b.set(CaptureRequest.JPEG_QUALITY, 93.toByte())
                s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        callbacks.onStillFailed()
                    }
                }, handler)
            } catch (e: Exception) {
                callbacks.onStillFailed()
            }
        }
    }

    private fun jpegOrientation(): Int {
        val i = info ?: return 0
        val rotation = if (Build.VERSION.SDK_INT >= 30) activity.display?.rotation ?: Surface.ROTATION_0
        else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.rotation
        val deviceDeg = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        // Caméra arrière : rotation à appliquer au JPEG pour qu'il soit droit à l'écran.
        return (i.sensorOrientation - deviceDeg + 360) % 360
    }

    private fun fail(message: String) {
        if (closed) return
        Bg.onMain { callbacks.onCameraError(message) }
    }

    fun close() {
        closed = true
        locked = false
        handler.post {
            try { session?.close() } catch (e: Exception) {}
            try { device?.close() } catch (e: Exception) {}
            try { reader?.close() } catch (e: Exception) {}
            try { previewSurface?.release() } catch (e: Exception) {}
            session = null; device = null; reader = null; previewSurface = null; previewBuilder = null
        }
    }

    fun release() {
        close()
        handler.post { thread.quitSafely() }
    }
}
