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
import care.primary.sphere360.stitch.LensDistortion
import care.primary.sphere360.util.Bg
import kotlin.math.abs
import kotlin.math.atan

/**
 * Enveloppe Camera2 : énumération des objectifs arrière, prévisualisation sur TextureView,
 * verrouillage AE/AWB/AF, capture JPEG vers un ImageReader. Toutes les callbacks caméra tournent
 * sur un thread dédié.
 */
class CameraController(private val activity: Activity, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onCameraConfigured(info: CameraInfo)
        fun onCameraError(message: String)
        fun onStillCaptured(jpeg: ByteArray)
        fun onStillFailed()
    }

    /** Famille d'objectif, déduite du champ de vue horizontal en paysage. */
    enum class LensKind { TELE, STANDARD, WIDE, ULTRAWIDE }

    /**
     * Un objectif utilisable pour la capture, avec tout ce dont l'assemblage aura besoin.
     *
     * @param distortion coefficients de Brown-Conrady (ordre OpenCV) que l'assemblage devra
     *   appliquer, ou null quand les photos peuvent être traitées comme rectilinéaires
     * @param previewCorrection mode de correction de distorsion demandé à la prévisualisation,
     *   -1 pour laisser le pilote décider
     * @param stillCorrection idem pour les photos
     */
    class CameraInfo(
        val cameraId: String,
        val sensorOrientation: Int,
        val previewSize: Size,
        val jpegSize: Size,
        val hfovPortraitDeg: Double,
        val vfovPortraitDeg: Double,
        val hfovLandscapeDeg: Double,
        val kind: LensKind,
        val distortion: DoubleArray? = null,
        val previewCorrection: Int = -1,
        val stillCorrection: Int = -1,
        val afContinuous: Boolean = true
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
    @Volatile private var closed = true
    @Volatile private var locked = false

    var info: CameraInfo? = null
        private set

    companion object {
        /** En dessous de cette résolution, une caméra arrière n'est pas un objectif photo utile. */
        private const val MIN_JPEG_PIXELS = 1_500_000L

        /** Bornes de vraisemblance du champ de vue : du téléobjectif à l'ultra grand angle. */
        private const val MIN_HFOV = 25.0
        private const val MAX_HFOV = 160.0
        private const val MIN_VFOV = 18.0
        private const val MAX_VFOV = 150.0
    }

    // ---- Énumération et sélection ----

    /**
     * Tous les objectifs arrière exploitables, du plus long au plus large.
     *
     * Un téléphone récent expose plusieurs caméras arrière dans `cameraIdList` : le module
     * principal, le grand angle, parfois un téléobjectif. Les objectifs qui ne sont accessibles
     * que comme sous-caméras d'une caméra logique (`getPhysicalCameraIds`) ne sont pas retenus :
     * beaucoup de pilotes refusent de les ouvrir directement, et il faudrait alors passer par une
     * session multi-caméras dont le gain ne vaut pas la fragilité.
     */
    fun availableLenses(): List<CameraInfo> {
        val out = ArrayList<CameraInfo>()
        try {
            for (id in manager.cameraIdList) {
                val info = try { describe(id) } catch (e: Exception) { null }
                if (info != null) out.add(info)
            }
        } catch (e: Exception) {
            return out
        }
        return out.sortedBy { it.hfovLandscapeDeg }
    }

    /** L'objectif demandé s'il existe, sinon celui que le téléphone présente en premier. */
    fun selectCamera(preferredId: String? = null): CameraInfo? {
        val lenses = availableLenses()
        if (lenses.isEmpty()) return null
        if (preferredId != null) lenses.firstOrNull { it.cameraId == preferredId }?.let { return it }
        // Par défaut le module principal, c'est-à-dire le premier que le pilote déclare.
        val order = try { manager.cameraIdList.toList() } catch (e: Exception) { emptyList() }
        return lenses.minByOrNull { order.indexOf(it.cameraId).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            ?: lenses.first()
    }

    private fun describe(id: String): CameraInfo? {
        val c = manager.getCameraCharacteristics(id)
        if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return null
        // Un capteur de profondeur ou monochrome se présente aussi comme une caméra arrière. Sans
        // la capacité BACKWARD_COMPATIBLE il ne sait pas produire le JPEG couleur attendu, et son
        // champ de vue fantaisiste polluerait la liste des objectifs.
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        if (caps != null && !caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) return null

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return null
        val jpegSizes = map.getOutputSizes(ImageFormat.JPEG) ?: return null
        val preview = pickPreview(previewSizes) ?: return null
        val jpeg = pickJpeg(jpegSizes, preview) ?: return null
        if (area(jpeg) < MIN_JPEG_PIXELS) return null

        val orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        val af = afModes?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
        val (hL, vL) = landscapeFov(c, jpeg)
        val plan = distortionPlan(c, hL, vL)
        val swap = orientation % 180 == 90
        return CameraInfo(
            id, orientation, preview, jpeg,
            if (swap) vL else hL, if (swap) hL else vL, hL, kindOf(hL),
            plan.coefficients, plan.previewMode, plan.stillMode, af
        )
    }

    private fun kindOf(hfovLandscapeDeg: Double): LensKind = when {
        hfovLandscapeDeg < 55 -> LensKind.TELE
        hfovLandscapeDeg < 76 -> LensKind.STANDARD
        hfovLandscapeDeg < 95 -> LensKind.WIDE
        else -> LensKind.ULTRAWIDE
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

    /**
     * Champ de vue (paysage) horizontal/vertical en degrés pour la taille de sortie donnée.
     *
     * Deux sources, dans cet ordre : les intrinsèques mesurées que publie le pilote, qui sont la
     * référence quand elles existent, puis la géométrie du capteur (taille physique et focale),
     * qui suppose un objectif parfait mais est toujours disponible.
     */
    private fun landscapeFov(c: CameraCharacteristics, out: Size): Pair<Double, Double> {
        reportedFocals(c, out)?.let { f ->
            val h = Math.toDegrees(2 * atan(out.width / 2.0 / f[0]))
            val v = Math.toDegrees(2 * atan(out.height / 2.0 / f[1]))
            if (plausible(h, v)) return h to v
        }
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
        if (!plausible(h, v)) return fallback
        return h to v
    }

    private fun plausible(h: Double, v: Double) =
        !h.isNaN() && !v.isNaN() && h >= MIN_HFOV && h <= MAX_HFOV && v >= MIN_VFOV && v <= MAX_VFOV

    /**
     * Focales (fx, fy) en pixels de la sortie, d'après `LENS_INTRINSIC_CALIBRATION`.
     *
     * Ces intrinsèques sont exprimées dans le repère du tableau de pixels avant correction de
     * distorsion. La sortie JPEG en est un recadrage centré au rapport demandé, puis une mise à
     * l'échelle : le facteur est donc le même en x et en y, et il suffit de le calculer une fois.
     */
    private fun reportedFocals(c: CameraCharacteristics, out: Size): DoubleArray? {
        if (Build.VERSION.SDK_INT < 28) return null
        val k = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) ?: return null
        if (k.size < 4 || k[0] <= 0f || k[1] <= 0f) return null
        val array = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            ?: c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val aw = array.width().toDouble()
        val ah = array.height().toDouble()
        if (aw <= 0 || ah <= 0) return null
        var cw = aw
        var ch = ah
        val outAspect = aspect(out)
        if (aw / ah > outAspect) cw = ch * outAspect else ch = cw / outAspect
        val scale = out.width / cw
        return doubleArrayOf(k[0] * scale, k[1] * scale)
    }

    private class DistortionPlan(val coefficients: DoubleArray?, val previewMode: Int, val stillMode: Int)

    /**
     * Qui corrige la distorsion de l'objectif, le téléphone ou nous ?
     *
     * L'ordre de préférence laisse la main au pilote quand il sait le faire : sa correction est
     * définie exactement contre les intrinsèques qu'il publie, elle est appliquée avant la
     * compression et elle ne coûte rien à l'assemblage. On ne reprend le calcul à notre charge que
     * lorsqu'il déclare une distorsion sans proposer de la corriger, ce qui est le cas courant des
     * modules ultra grand angle. Un modèle non inversible, ou dont le coin du cadre sort du
     * domaine, est écarté : une projection rectilinéaire approchée vaut mieux qu'un modèle faux.
     */
    private fun distortionPlan(c: CameraCharacteristics, hfov: Double, vfov: Double): DistortionPlan {
        val none = DistortionPlan(null, -1, -1)
        if (Build.VERSION.SDK_INT < 28) return none
        val modes = c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
        val hasHigh = modes?.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY) == true
        val hasFast = modes?.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_FAST) == true
        if (hasHigh || hasFast) {
            val fast = if (hasFast) CameraMetadata.DISTORTION_CORRECTION_MODE_FAST
            else CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
            val still = if (hasHigh) CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
            else CameraMetadata.DISTORTION_CORRECTION_MODE_FAST
            return DistortionPlan(null, fast, still)
        }
        val model = LensDistortion.fromAndroid(c.get(CameraCharacteristics.LENS_DISTORTION))
        model.boundedTo(hfov, vfov) ?: return none
        val off = if (modes?.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_OFF) == true)
            CameraMetadata.DISTORTION_CORRECTION_MODE_OFF else -1
        return DistortionPlan(model.coefficients(), off, off)
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
        val i = info ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            if (i.afContinuous) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            setDistortionCorrection(b, i.previewCorrection)
            previewBuilder = b
            s.setRepeatingRequest(b.build(), null, handler)
            Bg.onMain { callbacks.onCameraConfigured(i) }
        } catch (e: Exception) {
            fail(e.message ?: "preview")
        }
    }

    private fun setDistortionCorrection(b: CaptureRequest.Builder, mode: Int) {
        if (mode < 0 || Build.VERSION.SDK_INT < 28) return
        try {
            b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, mode)
        } catch (e: Exception) {
            // Clé refusée par le pilote : on garde son comportement par défaut.
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
            val i = info ?: return@post
            if (closed) return@post
            try {
                if (i.afContinuous) {
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
            val i = info ?: return@post
            if (closed) return@post
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(r.surface)
                b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                if (i.afContinuous) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AE_LOCK, locked)
                b.set(CaptureRequest.CONTROL_AWB_LOCK, locked)
                b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
                b.set(CaptureRequest.JPEG_QUALITY, 93.toByte())
                setDistortionCorrection(b, i.stillCorrection)
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
