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
    var sessionId: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("createdAt", createdAt)
        .put("width", width).put("height", height)
        .put("defaultYaw", defaultYaw).put("defaultPitch", defaultPitch)
        .put("demo", demo).put("shots", shots).put("usedShots", usedShots)
        .put("method", method).put("coverage", coverage).put("sessionId", sessionId)
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
                o.optString("method", ""), o.optDouble("coverage", 1.0), o.optString("sessionId", "")
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

/** Champ de vue (portrait) et taille des photos de la session. */
class CameraMeta(val hfovDeg: Double, val vfovDeg: Double, val width: Int, val height: Int, val jpegRotation: Int = 90) {
    fun toJson(): JSONObject = JSONObject().put("hfov", hfovDeg).put("vfov", vfovDeg).put("width", width).put("height", height)
        .put("jpegRotation", jpegRotation)

    companion object {
        fun fromJson(o: JSONObject) = CameraMeta(
            o.optDouble("hfov", 50.0), o.optDouble("vfov", 66.0), o.optInt("width", 0), o.optInt("height", 0), o.optInt("jpegRotation", 90)
        )
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
    var targetCount: Int = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("createdAt", createdAt).put("name", name)
        .put("camera", camera.toJson())
        .put("shots", JSONArray().also { a -> shots.forEach { a.put(it.toJson()) } })
        .put("state", state.name).put("error", error ?: JSONObject.NULL)
        .put("attempts", attempts).put("targetCount", targetCount)

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
                o.optInt("attempts", 0), o.optInt("targetCount", 0)
            )
        }
    }
}
