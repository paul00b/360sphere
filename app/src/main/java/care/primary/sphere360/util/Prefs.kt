package care.primary.sphere360.util

import android.content.Context

/** Préférences persistées de l'app : quelques réglages, pas de données métier. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("sphere360", Context.MODE_PRIVATE)

    /** Identifiant Camera2 de l'objectif choisi pour la capture, vide pour « celui par défaut ». */
    var lensId: String
        get() = sp.getString(KEY_LENS, "") ?: ""
        set(value) { sp.edit().putString(KEY_LENS, value).apply() }

    /** Dernier dossier ouvert dans la galerie, vide pour la racine. */
    var lastFolderId: String
        get() = sp.getString(KEY_FOLDER, "") ?: ""
        set(value) { sp.edit().putString(KEY_FOLDER, value).apply() }

    private companion object {
        const val KEY_LENS = "lens_id"
        const val KEY_FOLDER = "last_folder"
    }
}
