package care.primary.sphere360.desktop

import care.primary.sphere360.data.CaptureSessionMeta
import care.primary.sphere360.stitch.OpenCvRuntime
import care.primary.sphere360.stitch.SphereStitcher
import care.primary.sphere360.stitch.StitchException
import care.primary.sphere360.stitch.StitchMode
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Banc de test desktop : exécute le vrai SphereStitcher sur une session de capture synthétique
 * (voir tools/desktop/make_test_capture.py), avec les natives OpenCV Linux fournies par JavaCPP.
 *
 * Les natives sont chargées explicitement, dans l'ordre des dépendances, exactement comme
 * OpenCvRuntime le fait sur Android (le chargeur automatique de JavaCPP voudrait aussi charger
 * highgui, qui réclame GTK).
 *
 * Usage : StitchHarness <dossier_session> <dossier_sortie> <dossier_natives> [--gc] [--relaxed]
 *   --gc      martèle System.gc() pendant l'assemblage : révèle les objets natifs libérés trop tôt
 *   --refine tente le recalage par points d'intérêt avant de retomber sur les capteurs
 */
object StitchHarness {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            System.err.println("usage: StitchHarness <session> <out> <natives> [--gc] [--refine]")
            return
        }
        val sessionDir = File(args[0])
        val outDir = File(args[1]).apply { mkdirs() }
        val nativesDir = File(args[2])
        val useGc = args.contains("--gc")
        val mode = if (args.contains("--refine")) StitchMode.REFINE else StitchMode.SENSORS

        Natives.load(nativesDir)
        OpenCvRuntime.markPreloaded()

        val meta = CaptureSessionMeta.fromJson(JSONObject(File(sessionDir, "meta.json").readText()))
        println("session=${sessionDir.name} shots=${meta.shots.size} hfov=${meta.camera.hfovDeg} vfov=${meta.camera.vfovDeg} mode=$mode gc=$useGc")

        val stop = AtomicBoolean(false)
        if (useGc) {
            Thread {
                while (!stop.get()) {
                    System.gc()
                    System.runFinalization()
                    Thread.sleep(100)
                }
            }.apply { isDaemon = true; start() }
        }

        val t0 = System.currentTimeMillis()
        var code = 0
        try {
            val result = SphereStitcher { stage, pct -> println("  [%3d%%] %s".format(pct, stage)) }
                .stitch(sessionDir, meta, File(outDir, "equirect.jpg"), File(outDir, "thumb.jpg"), mode)
            println("OK ${result.width}x${result.height} méthode=${result.method} used=%d/%d couverture=%.3f en %d ms".format(
                result.usedShots, result.totalShots, result.coverage, System.currentTimeMillis() - t0))
        } catch (e: StitchException) {
            println("ÉCHEC ${e.kind} : ${e.message} (${System.currentTimeMillis() - t0} ms)")
            code = 2
        } catch (t: Throwable) {
            println("PLANTAGE ${t.javaClass.name} : ${t.message} (${System.currentTimeMillis() - t0} ms)")
            t.printStackTrace()
            code = 3
        }
        stop.set(true)
        System.exit(code)
    }

}
