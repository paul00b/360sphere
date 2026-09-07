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
    private val foldersFile = File(root, "folders.json")

    private val spheres = LinkedHashMap<String, Sphere>()
    private val folders = LinkedHashMap<String, Folder>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    init { load(); loadFolders() }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() { Bg.onMain { listeners.forEach { it() } } }

    // ---- Sphères ----

    @Synchronized fun all(): List<Sphere> = spheres.values.sortedByDescending { it.createdAt }

    /** Sphères d'un dossier, la chaîne vide désignant la racine. */
    @Synchronized fun spheresIn(folderId: String): List<Sphere> =
        spheres.values.filter { it.folderId == folderId }.sortedByDescending { it.createdAt }

    @Synchronized fun countIn(folderId: String): Int = spheres.values.count { it.folderId == folderId }

    @Synchronized fun get(id: String): Sphere? = spheres[id]
    @Synchronized fun count(): Int = spheres.size

    @Synchronized fun add(sphere: Sphere) { spheres[sphere.id] = sphere; save(); notifyChanged() }
    @Synchronized fun update(sphere: Sphere) { spheres[sphere.id] = sphere; save(); notifyChanged() }

    /**
     * Supprime la sphère, ses fichiers, la session de capture conservée pour un éventuel
     * réassemblage, et tous les portails qui pointaient vers elle.
     */
    @Synchronized fun delete(id: String) {
        if (!deleteInternal(id)) return
        save(); notifyChanged()
    }

    private fun deleteInternal(id: String): Boolean {
        val removed = spheres.remove(id) ?: return false
        spheres.values.forEach { s -> s.portals.removeAll { it.toSphereId == id } }
        sphereDir(id).deleteRecursively()
        if (removed.sessionId.isNotEmpty()) sessionDir(removed.sessionId).deleteRecursively()
        return true
    }

    /**
     * Déplace une sphère vers un autre dossier.
     *
     * Les portails ne relient que des sphères d'un même dossier : ceux qui partent de la sphère
     * et ceux qui y mènent depuis son ancien dossier deviendraient des liens fantômes, menant à
     * une sphère absente de la visite. Ils sont donc supprimés avec le déplacement. C'est la
     * contrepartie assumée d'une visite fermée sur son dossier, et l'utilisateur en est averti
     * avant de valider.
     */
    @Synchronized fun moveSphere(id: String, folderId: String) {
        val sphere = spheres[id] ?: return
        if (sphere.folderId == folderId) return
        sphere.folderId = folderId
        sphere.portals.clear()
        spheres.values.forEach { other -> other.portals.removeAll { it.toSphereId == id } }
        save(); notifyChanged()
    }

    /** Nombre de portails qu'un déplacement de cette sphère supprimerait. */
    @Synchronized fun portalsAffectedByMove(id: String): Int {
        val sphere = spheres[id] ?: return 0
        return sphere.portals.size + spheres.values.sumOf { other -> other.portals.count { it.toSphereId == id } }
    }

    // ---- Dossiers (visites) ----

    @Synchronized fun folders(): List<Folder> = folders.values.sortedByDescending { it.createdAt }
    @Synchronized fun folder(id: String): Folder? = folders[id]

    @Synchronized fun createFolder(name: String): Folder {
        val f = Folder(newId(), name, System.currentTimeMillis())
        folders[f.id] = f
        saveFolders(); notifyChanged()
        return f
    }

    @Synchronized fun renameFolder(id: String, name: String) {
        val f = folders[id] ?: return
        f.name = name
        saveFolders(); notifyChanged()
    }

    /** Supprime le dossier et tout ce qu'il contient : sphères, fichiers, sessions conservées. */
    @Synchronized fun deleteFolder(id: String) {
        if (folders.remove(id) == null) return
        for (s in spheres.values.filter { it.folderId == id }.map { it.id }) deleteInternal(s)
        for (session in listSessions().filter { it.folderId == id }) deleteSession(session.id)
        saveFolders(); save(); notifyChanged()
    }

    @Synchronized fun nextFolderName(template: (Int) -> String): String {
        var n = folders.size + 1
        val names = folders.values.map { it.name }.toSet()
        while (names.contains(template(n))) n++
        return template(n)
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

    /**
     * Sessions à afficher dans la galerie : capturées, en cours d'assemblage ou en échec. Les
     * sessions déjà assemblées sont conservées sur disque (photos réduites) pour permettre un
     * réassemblage, mais n'apparaissent pas comme des entrées distinctes.
     */
    fun listSessions(): List<CaptureSessionMeta> =
        (sessionsDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { loadSession(it.name) }
            .filter { it.state != SessionState.DONE }
            .sortedByDescending { it.createdAt }

    /** La session existe-t-elle encore avec ses photos ? */
    fun hasSessionImages(id: String): Boolean {
        if (id.isEmpty()) return false
        val dir = sessionDir(id)
        return dir.isDirectory && (dir.listFiles()?.any { it.name.startsWith("shot_") } == true)
    }

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

    private fun loadFolders() {
        if (!foldersFile.exists()) return
        try {
            val arr = JSONArray(foldersFile.readText())
            for (i in 0 until arr.length()) {
                val f = Folder.fromJson(arr.getJSONObject(i))
                folders[f.id] = f
            }
        } catch (e: Exception) {
            folders.clear()
        }
        // Un dossier disparu (index réécrit, fichier corrompu) laisserait ses sphères invisibles :
        // elles reviennent à la racine plutôt que d'être perdues.
        val known = folders.keys
        var repaired = false
        for (s in spheres.values) {
            if (s.folderId.isNotEmpty() && s.folderId !in known) { s.folderId = ""; repaired = true }
        }
        if (repaired) save()
    }

    private fun saveFolders() {
        val arr = JSONArray()
        folders.values.forEach { arr.put(it.toJson()) }
        writeAtomic(foldersFile, arr.toString())
    }

    private fun writeAtomic(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
