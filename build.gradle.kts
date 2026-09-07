// Build Android Studio / Gradle standard.
// Le dossier tools/ contient aussi une chaîne de build alternative (sans SDK manager) utilisée pour produire les APK livrées.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
