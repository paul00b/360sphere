package care.primary.sphere360.stitch

import org.json.JSONArray
import org.json.JSONObject

/**
 * Douceur des raccords entre photos.
 *
 * Le composeur sépare chaque photo en deux échelles : les fonds sont moyennés sur tout le
 * recouvrement, les détails viennent d'une seule photo. L'échelle qui sépare les deux décide du
 * compromis. Plus elle est large, plus les écarts d'exposition se diluent, mais plus un
 * dédoublement de structure moyenne devient visible ; plus elle est fine, plus les contours
 * restent uniques, mais une marche d'exposition peut apparaître à la jointure.
 *
 * Les trois réglages proposés encadrent la valeur mesurée comme le meilleur compromis sur le banc
 * de test, sans prétendre qu'elle convient à toutes les pièces : une pièce très contrastée en
 * lumière gagne à des raccords doux, une pièce chargée en détail à des raccords francs.
 */
enum class BlendScale(val blurFraction: Double, val detailCrossfade: Float) {
    /** Dilue largement les écarts d'exposition, au prix d'un dédoublement possible. */
    SOFT(1.0 / 24, 0.22f),

    /** Le compromis retenu par défaut. */
    BALANCED(1.0 / 48, 0.14f),

    /** Contours nets et uniques, marche d'exposition possible sur les jointures. */
    CRISP(1.0 / 96, 0.07f);

    companion object {
        fun of(name: String?): BlendScale =
            try { if (name.isNullOrEmpty()) BALANCED else valueOf(name) } catch (e: Exception) { BALANCED }
    }
}

/**
 * Réglages d'un assemblage. Toutes les valeurs par défaut reproduisent exactement l'assemblage
 * automatique lancé à la fin d'une capture : un réassemblage sans réglage donne le même résultat.
 *
 * @param excluded noms de fichiers des photos à écarter. Une photo floue ou prise en marchant
 *   abîme la zone qu'elle couvre ; la retirer laisse ses voisines la remplacer, avec un
 *   recouvrement moindre mais un résultat propre.
 */
class StitchOptions(
    val mode: StitchMode = StitchMode.SENSORS,
    val targetWidth: Int = SphereStitcher.TARGET_WIDTH,
    val blend: BlendScale = BlendScale.BALANCED,
    val excluded: Set<String> = emptySet()
) {
    val isDefault: Boolean
        get() = mode == StitchMode.SENSORS && targetWidth == SphereStitcher.TARGET_WIDTH &&
            blend == BlendScale.BALANCED && excluded.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.name)
        .put("width", targetWidth)
        .put("blend", blend.name)
        .put("excluded", JSONArray().also { a -> excluded.forEach { a.put(it) } })

    companion object {
        /** Largeurs proposées : au-delà de 8192, les WebView mobiles ne suivent plus en mémoire GPU. */
        val WIDTHS = intArrayOf(4096, 6144, 8192)

        fun fromJson(o: JSONObject?): StitchOptions {
            if (o == null) return StitchOptions()
            val mode = try { StitchMode.valueOf(o.optString("mode", "SENSORS")) } catch (e: Exception) { StitchMode.SENSORS }
            val excluded = LinkedHashSet<String>()
            val arr = o.optJSONArray("excluded")
            if (arr != null) for (i in 0 until arr.length()) excluded.add(arr.getString(i))
            val width = o.optInt("width", SphereStitcher.TARGET_WIDTH)
            return StitchOptions(
                mode,
                if (width in 1024..8192) width else SphereStitcher.TARGET_WIDTH,
                BlendScale.of(o.optString("blend", "")),
                excluded
            )
        }

        fun fromJson(text: String?): StitchOptions {
            if (text.isNullOrEmpty()) return StitchOptions()
            return try { fromJson(JSONObject(text)) } catch (e: Exception) { StitchOptions() }
        }
    }
}
