package care.primary.sphere360.data

import android.content.Context
import care.primary.sphere360.util.Bg
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Persistance locale (JSON dans filesDir) des sphères, portails et sessions de capture.
 * Pas de base de données : quelques dizaines d'entrées au plus.
 */
class TourStore(context: Context) {
    private val root: File = context.filesDir
    val spheresDir = File(root, "spheres").apply { mkdirs() }
    val sessionsDir = File(root, "sessions").apply { mkdirs() }
    private val indexFile = File(root, "spheres.json")

    private val spheres = LinkedHashMap<String, Sphere>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    init { load() }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() { Bg.onMain { listeners.forEach { it() } } }

    // ---- Sphères ----

    @Synchronized fun all(): List<Sphere> = spheres.values.sortedByDescending { it.createdAt }
    @Synchronized fun get(id: String): Sphere? = spheres[id]
    @Synchronized fun count(): Int = spheres.size

    @Synchronized fun add(sphere: Sphere) { spheres[sphere.id] = sphere; save(); notifyChanged() }
    @Synchronized fun update(sphere: Sphere) { spheres[sphere.id] = sphere; save(); notifyChanged() }

    /** Supprime la sphère, ses fichiers, et tous les portails qui pointaient vers elle. */
    @Synchronized fun delete(id: String) {
        spheres.remove(id) ?: return
        spheres.values.forEach { s -> s.portals.removeAll { it.toSphereId == id } }
        sphereDir(id).deleteRecursively()
        save(); notifyChanged()
    }

    fun sphereDir(id: String): File = File(spheresDir, id)
    fun equirectFile(id: String): File = File(sphereDir(id), "equirect.jpg")
    fun thumbFile(id: String): File = File(sphereDir(id), "thumb.jpg")

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 12)

    @Synchronized fun nextName(template: (Int) -> String): String {
        var n = spheres.size + 1
        val names = spheres.values.map { it.name }.toSet()
        while (names.contains(template(n))) n++
        return template(n)
    }

    // ---- Sessions de capture ----

    fun sessionDir(id: String): File = File(sessionsDir, id)
    private fun sessionMetaFile(id: String) = File(sessionDir(id), "meta.json")

    fun saveSession(meta: CaptureSessionMeta) {
        sessionDir(meta.id).mkdirs()
        writeAtomic(sessionMetaFile(meta.id), meta.toJson().toString())
        notifyChanged()
    }

    fun loadSession(id: String): CaptureSessionMeta? {
        val f = sessionMetaFile(id)
        if (!f.exists()) return null
        return try { CaptureSessionMeta.fromJson(JSONObject(f.readText())) } catch (e: Exception) { null }
    }

    /** Sessions encore présentes (capturées, en cours d'assemblage ou en échec). */
    fun listSessions(): List<CaptureSessionMeta> =
        (sessionsDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { loadSession(it.name) }
            .filter { it.state != SessionState.DONE }
            .sortedByDescending { it.createdAt }

    fun deleteSession(id: String) {
        sessionDir(id).deleteRecursively()
        notifyChanged()
    }

    // ---- IO ----

    private fun load() {
        if (!indexFile.exists()) return
        try {
            val arr = JSONArray(indexFile.readText())
            for (i in 0 until arr.length()) {
                val s = Sphere.fromJson(arr.getJSONObject(i))
                spheres[s.id] = s
            }
        } catch (e: Exception) {
            // index corrompu : on repart de zéro plutôt que de planter au démarrage
            spheres.clear()
        }
    }

    private fun save() {
        val arr = JSONArray()
        spheres.values.forEach { arr.put(it.toJson()) }
        writeAtomic(indexFile, arr.toString())
    }

    private fun writeAtomic(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
