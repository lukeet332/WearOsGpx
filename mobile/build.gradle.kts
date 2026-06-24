import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Shared signing — must match :wear exactly for Data Layer pairing.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// OpenRouteService API key for road-snapping (put ORS_API_KEY=... in local.properties).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.wearosgpx.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wearosgpx"   // SAME as :wear — mandatory for pairing
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "ORS_API_KEY", "\"${localProps.getProperty("ORS_API_KEY", "")}\"")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // False positive for Compose/no-Fragment apps using registerForActivityResult.
    lint { disable += "InvalidFragmentVersionForActivityResult" }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.ktx)            // ComponentActivity + permission contract
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    // Compose (phone Material3)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Data Layer listener (Phase 4)
    implementation(libs.play.services.wearable)

    // The closer: Health Connect (Phase 4)
    implementation(libs.health.connect.client)

    // Route creator map (OpenStreetMap)
    implementation(libs.osmdroid.android)
}
