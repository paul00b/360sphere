package care.primary.sphere360.desktop

import java.io.File

/** Chargement explicite des natives OpenCV (mêmes modules que l'APK, ordre des dépendances). */
object Natives {
    fun load(dir: File) {
        System.setProperty("org.bytedeco.javacpp.loadlibraries", "false")
        val order = listOf(
            "libopenblas.so", "libgfortran.so", "libquadmath.so",
            "libopencv_core.so", "libopencv_imgproc.so", "libopencv_flann.so",
            "libopencv_features2d.so", "libopencv_calib3d.so", "libopencv_imgcodecs.so",
            "libopencv_stitching.so",
            "libjnijavacpp.so", "libjniopencv_core.so", "libjniopencv_imgproc.so",
            "libjniopencv_imgcodecs.so", "libjniopencv_features2d.so", "libjniopencv_stitching.so"
        )
        val files = dir.listFiles()?.toList() ?: emptyList()
        for (base in order) {
            val f = files.firstOrNull { it.name == base }
                ?: files.filter { it.name.startsWith("$base.") }.minByOrNull { it.name.length }
            val required = base.startsWith("libopencv") || base.startsWith("libjni")
            if (f == null) { if (required) error("native manquante : $base dans $dir"); continue }
            try { System.load(f.absolutePath) } catch (e: UnsatisfiedLinkError) { if (required) throw e }
        }
    }
}
