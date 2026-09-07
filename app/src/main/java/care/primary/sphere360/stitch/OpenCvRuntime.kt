package care.primary.sphere360.stitch

import android.util.Log

/**
 * Chargement des bibliothèques natives OpenCV (build JavaCPP).
 *
 * On désactive le chargeur automatique de JavaCPP (qui voudrait charger OpenBLAS et
 * l'intégralité des modules) et on charge explicitement le sous-ensemble embarqué dans l'APK,
 * élagué par tools/prepare-natives.sh.
 */
object OpenCvRuntime {
    private const val TAG = "OpenCvRuntime"

    private val libraries = listOf(
        "jnijavacpp",
        "opencv_core", "opencv_imgproc", "opencv_imgcodecs", "opencv_flann",
        "opencv_features2d", "opencv_calib3d", "opencv_stitching",
        "jniopencv_core", "jniopencv_imgproc", "jniopencv_imgcodecs",
        "jniopencv_features2d", "jniopencv_stitching"
    )

    @Volatile private var loaded = false

    /**
     * Déclare les bibliothèques natives déjà chargées : utilisé par le banc de test desktop
     * (tools/desktop), qui laisse JavaCPP extraire et charger les natives Linux depuis les jars.
     */
    @Synchronized
    fun markPreloaded() { loaded = true }

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        System.setProperty("org.bytedeco.javacpp.loadlibraries", "false")
        // Pas de plafond mémoire artificiel côté JavaCPP : le stitching alloue plusieurs centaines de Mo natifs.
        System.setProperty("org.bytedeco.javacpp.maxbytes", "0")
        System.setProperty("org.bytedeco.javacpp.maxphysicalbytes", "0")
        for (lib in libraries) {
            try {
                System.loadLibrary(lib)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Échec du chargement de $lib", e)
                throw e
            }
        }
        loaded = true
        Log.i(TAG, "OpenCV chargé (${libraries.size} bibliothèques)")
    }
}
