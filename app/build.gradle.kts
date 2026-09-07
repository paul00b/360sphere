plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "care.primary.sphere360"
    compileSdk = 34

    defaultConfig {
        applicationId = "care.primary.sphere360"
        minSdk = 26
        targetSdk = 34
        // versionCode / versionName sont définis dans AndroidManifest.xml (source unique
        // partagée avec tools/build-apk.sh).
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        getByName("debug") {
            // Même clé que les APK livrées : permet de mettre à jour sans désinstaller.
            storeFile = rootProject.file("tools/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        // Les .so élaguées sont fournies par tools/prepare-natives.sh dans src/main/jniLibs.
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module")
    }
}

dependencies {
    // Bindings Java d'OpenCV (module stitching inclus). Les natives Android sont
    // extraites/élaguées par tools/prepare-natives.sh, pas récupérées via Gradle,
    // pour éviter d'embarquer ~90 Mo de modules inutiles + OpenBLAS.
    implementation("org.bytedeco:javacpp:1.5.14")
    implementation("org.bytedeco:opencv:4.14.0-1.5.14") {
        exclude(group = "org.bytedeco", module = "openblas")
        exclude(group = "org.bytedeco", module = "numpy")
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Échec explicite si les natives n'ont pas été préparées.
val checkNatives by tasks.registering {
    doLast {
        val dir = file("src/main/jniLibs/arm64-v8a")
        val files = dir.listFiles()
        if (!dir.exists() || files == null || files.isEmpty()) {
            throw GradleException("Natives OpenCV manquantes dans app/src/main/jniLibs/arm64-v8a : lance ./tools/prepare-natives.sh (voir README).")
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(checkNatives)
}
