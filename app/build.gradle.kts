plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Adres, z którego aplikacja pobiera dane map (GitHub Release "dane").
// Workflow ustawia go automatycznie na podstawie nazwy repozytorium.
val dataUrl: String = (project.findProperty("dataUrl") as String?) ?: "https://github.com/manuals24you-bit/Nave/releases/download/dane"

android {
    namespace = "pl.drogowskaz.nav"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.drogowskaz.nav"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "0.1.${versionCode}"
        buildConfigField("String", "DATA_URL", "\"$dataUrl\"")
    }

    signingConfigs {
        create("shared") {
            // Stały klucz w repozytorium -> kolejne wersje instalują się jako aktualizacja.
            storeFile = file("drogowskaz.keystore")
            storePassword = "drogowskaz"
            keyAlias = "drogowskaz"
            keyPassword = "drogowskaz"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("shared") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
    buildFeatures { buildConfig = true; viewBinding = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:11.13.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.car.app:app:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
