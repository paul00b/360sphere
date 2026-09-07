package care.primary.sphere360.demo

import android.content.Context
import care.primary.sphere360.data.Portal
import care.primary.sphere360.data.Sphere
import care.primary.sphere360.data.TourStore
import org.json.JSONObject

/**
 * Installe la visite de démonstration (3 pièces synthétiques reliées par des portails) depuis
 * assets/demo/demo.json. Idempotent : une démo déjà présente est remplacée.
 */
object DemoTourInstaller {
    private const val DIR = "demo"

    fun isInstalled(store: TourStore): Boolean = store.all().any { it.demo }

    fun install(context: Context, store: TourStore) {
        val json = JSONObject(context.assets.open("$DIR/demo.json").bufferedReader().use { it.readText() })
        val arr = json.getJSONArray("spheres")
        // nettoyage d'une installation précédente
        store.all().filter { it.demo }.forEach { store.delete(it.id) }
        val spheres = ArrayList<Sphere>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val dir = store.sphereDir(id).apply { mkdirs() }
            context.assets.open("$DIR/${o.getString("file")}").use { input -> dir.resolve("equirect.jpg").outputStream().use { input.copyTo(it) } }
            context.assets.open("$DIR/${o.getString("thumb")}").use { input -> dir.resolve("thumb.jpg").outputStream().use { input.copyTo(it) } }
            val portals = mutableListOf<Portal>()
            val pa = o.getJSONArray("portals")
            for (j in 0 until pa.length()) {
                val p = pa.getJSONObject(j)
                portals.add(Portal(p.getString("id"), p.getString("to"), p.getDouble("yaw"), p.getDouble("pitch"), p.getString("label")))
            }
            spheres.add(Sphere(id, o.getString("name"), System.currentTimeMillis() - (arr.length() - i), o.getInt("width"), o.getInt("height"),
                o.optDouble("defaultYaw", 0.0), o.optDouble("defaultPitch", 0.0), portals, demo = true))
        }
        spheres.forEach { store.add(it) }
    }
}
