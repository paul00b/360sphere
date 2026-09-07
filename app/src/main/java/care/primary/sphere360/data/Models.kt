package care.primary.sphere360.data

import org.json.JSONArray
import org.json.JSONObject

/** Lien (hotspot) d'une sphère vers une autre. Angles en radians, convention Photo Sphere Viewer. */
class Portal(
    val id: String,
    var toSphereId: String,
    var yaw: Double,
    var pitch: Double,
    var label: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("to", toSphereId).put("yaw", yaw).put("pitch", pitch).put("label", label)

    companion object {
        fun fromJson(o: JSONObject) = Portal(
            o.getString("id"), o.getString("to"), o.getDouble("yaw"), o.getDouble("pitch"), o.optString("label", "")
        )
    }
}

/**
 * Un dossier de la galerie, c'est-à-dire une visite : un logement, un local, un chantier.
 *
 * Le dossier n'est pas qu'un rangement, c'est la frontière de la visite virtuelle : un portail ne
 * relie que deux sphères d'un même dossier, et le viewer ne charge que les sphères du dossier
 * courant. Une sphère sans dossier vit à la racine et ne peut pas être reliée à une autre.
 */
class Folder(val id: String, var name: String, val createdAt: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = Folder(o.getString("id"), o.getString("name"), o.optLong("createdAt", 0L))
    }
}

/** Une photo sphérique assemblée (équirectangulaire 2:1). */
class Sphere(
    val id: String,
    var name: String,
    val createdAt: Long,
    var width: Int,
    var height: Int,
    var defaultYaw: Double = 0.0,
    var defaultPitch: Double = 0.0,
    val portals: MutableList<Portal> = mutableListOf(),
    val demo: Boolean = false,
    var shots: Int = 0,
    var usedShots: Int = 0,
    /** "features" si le recalage par points d'intérêt a été retenu, "sensors" sinon. */
    var method: String = "",
    /** Part de la sphère réellement photographiée, en angle solide. */
    var coverage: Double = 1.0,
    /** Session de capture conservée (photos réduites) permettant de réassembler la sphère. */
    var sessionId: String = "",
    /** Dossier (visite) auquel la sphère appartient, vide pour la racine. */
    var folderId: String = "",
    /**
     * Correction d'assiette appliquée à l'affichage : tangage et roulis en radians, transmis tels
     * quels à `sphereCorrection` de Photo Sphere Viewer. Redresser une sphère penchée ne demande
     * donc aucun réassemblage : c'est une rotation du maillage, appliquée par le GPU au rendu.
     *
     * Le lacet n'y figure pas volontairement : faire pivoter la sphère autour de la verticale
     * revient à changer la direction de départ, ce que fait déjà la vue d'entrée.
     */
    var correctionPitch: Double = 0.0,
    var correctionRoll: Double = 0.0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("createdAt", createdAt)
        .put("width", width).put("height", height)
        .put("defaultYaw", defaultYaw).put("defaultPitch", defaultPitch)
        .put("demo", demo).put("shots", shots).put("usedShots", usedShots)
        .put("method", method).put("coverage", coverage).put("sessionId", sessionId)
        .put("folderId", folderId)
        .put("correctionPitch", correctionPitch).put("correctionRoll", correctionRoll)
        .put("portals", JSONArray().also { a -> portals.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Sphere {
            val portals = mutableListOf<Portal>()
            val arr = o.optJSONArray("portals")
            if (arr != null) for (i in 0 until arr.length()) portals.add(Portal.fromJson(arr.getJSONObject(i)))
            return Sphere(
                o.getString("id"), o.getString("name"), o.getLong("createdAt"),
                o.getInt("width"), o.getInt("height"),
                o.optDouble("defaultYaw", 0.0), o.optDouble("defaultPitch", 0.0),
                portals, o.optBoolean("demo", false), o.optInt("shots", 0), o.optInt("usedShots", 0),
                o.optString("method", ""), o.optDouble("coverage", 1.0), o.optString("sessionId", ""),
                o.optString("folderId", ""),
                o.optDouble("correctionPitch", 0.0), o.optDouble("correctionRoll", 0.0)
            )
        }
    }
}

/**
 * Une photo individuelle d'une session de capture, avec l'orientation du téléphone au déclenchement.
 *
 * `rotation` est la matrice 3x3 (ligne par ligne) appareil → monde fournie par les capteurs. C'est
 * elle qui permet de recomposer la sphère sans appariement de points ; yaw/pitch/roll n'en sont
 * qu'un résumé lisible, utilisé pour le masque d'appariement et les diagnostics.
 */
class ShotMeta(
    val file: String,
    val yawDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
    val targetIndex: Int,
    val rotation: FloatArray? = null
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("file", file).put("yaw", yawDeg).put("pitch", pitchDeg).put("roll", rollDeg).put("target", targetIndex)
        val r = rotation
        if (r != null && r.size == 9) {
            val a = JSONArray()
            r.forEach { a.put(it.toDouble()) }
            o.put("rot", a)
        }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): ShotMeta {
            val arr = o.optJSONArray("rot")
            val rot = if (arr != null && arr.length() == 9) FloatArray(9) { arr.getDouble(it).toFloat() } else null
            return ShotMeta(
                o.getString("file"), o.getDouble("yaw"), o.getDouble("pitch"), o.optDouble("roll", 0.0),
                o.optInt("target", -1), rot
            )
        }
    }
}

/**
 * Objectif et taille des photos d'une session : champ de vue (en portrait), résolution, rotation
 * JPEG, et éventuellement le modèle de distorsion de l'objectif.
 *
 * `distortion` contient les cinq coefficients de Brown-Conrady dans l'ordre d'OpenCV
 * (k1, k2, p1, p2, k3), ou null quand la photo peut être traitée comme rectilinéaire — soit parce
 * que le téléphone corrige lui-même la distorsion, soit parce qu'il ne la documente pas. C'est une
 * propriété de la session : les photos sont déjà prises, le modèle qui les décrit ne doit plus
 * changer, y compris lors d'un réassemblage des mois plus tard.
 */
class CameraMeta(
    val hfovDeg: Double,
    val vfovDeg: Double,
    val width: Int,
    val height: Int,
    val jpegRotation: Int = 90,
    val distortion: DoubleArray? = null,
    val lensLabel: String = ""
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("hfov", hfovDeg).put("vfov", vfovDeg).put("width", width).put("height", height)
            .put("jpegRotation", jpegRotation)
        if (lensLabel.isNotEmpty()) o.put("lens", lensLabel)
        val d = distortion
        if (d != null && d.size == 5) {
            val a = JSONArray()
            d.forEach { a.put(it) }
            o.put("distortion", a)
        }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): CameraMeta {
            val arr = o.optJSONArray("distortion")
            val d = if (arr != null && arr.length() == 5) DoubleArray(5) { arr.getDouble(it) } else null
            return CameraMeta(
                o.optDouble("hfov", 50.0), o.optDouble("vfov", 66.0), o.optInt("width", 0), o.optInt("height", 0),
                o.optInt("jpegRotation", 90), d, o.optString("lens", "")
            )
        }
    }
}

enum class SessionState { CAPTURING, CAPTURED, STITCHING, FAILED, DONE }

/** Session de capture : dossier de photos + méta, jusqu'à l'assemblage. */
class CaptureSessionMeta(
    val id: String,
    val createdAt: Long,
    var name: String,
    val camera: CameraMeta,
    val shots: MutableList<ShotMeta>,
    var state: SessionState,
    var error: String? = null,
    var attempts: Int = 0,
    var targetCount: Int = 0,
    /** Dossier dans lequel la sphère assemblée doit atterrir. */
    var folderId: String = "",
    /**
     * Réglages du dernier assemblage, au format JSON de StitchOptions.
     *
     * Conservés pour que l'écran de retouche reparte de ce qui a déjà été essayé : une retouche se
     * fait par petits pas, en comparant, et il faut donc savoir d'où l'on vient.
     */
    var options: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("createdAt", createdAt).put("name", name)
        .put("camera", camera.toJson())
        .put("shots", JSONArray().also { a -> shots.forEach { a.put(it.toJson()) } })
        .put("state", state.name).put("error", error ?: JSONObject.NULL)
        .put("attempts", attempts).put("targetCount", targetCount).put("folderId", folderId)
        .put("options", options)

    companion object {
        fun fromJson(o: JSONObject): CaptureSessionMeta {
            val shots = mutableListOf<ShotMeta>()
            val arr = o.optJSONArray("shots")
            if (arr != null) for (i in 0 until arr.length()) shots.add(ShotMeta.fromJson(arr.getJSONObject(i)))
            val state = try { SessionState.valueOf(o.optString("state", "CAPTURED")) } catch (e: Exception) { SessionState.CAPTURED }
            return CaptureSessionMeta(
                o.getString("id"), o.getLong("createdAt"), o.optString("name", ""),
                CameraMeta.fromJson(o.optJSONObject("camera") ?: JSONObject()),
                shots, state, if (o.isNull("error")) null else o.optString("error"),
                o.optInt("attempts", 0), o.optInt("targetCount", 0), o.optString("folderId", ""),
                o.optString("options", "")
            )
        }
    }
}
