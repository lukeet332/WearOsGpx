import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Shared signing — must match :wear exactly for Data Layer pairing.
// Local builds read keystore.properties; CI passes the key via env (KEYSTORE_FILE etc.).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val envKeystore: String? = System.getenv("KEYSTORE_FILE")
val hasReleaseSigning = keystorePropsFile.exists() || envKeystore != null

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
        // CI sets VERSION_CODE (e.g. the run number); ORS key may also come from env in CI.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "1.0"
        val orsKey = System.getenv("ORS_API_KEY") ?: localProps.getProperty("ORS_API_KEY", "")
        buildConfigField("String", "ORS_API_KEY", "\"$orsKey\"")
        // Strava API app credentials (put STRAVA_CLIENT_ID / STRAVA_CLIENT_SECRET in
        // local.properties; register the app at strava.com/settings/api).
        val stravaId = System.getenv("STRAVA_CLIENT_ID") ?: localProps.getProperty("STRAVA_CLIENT_ID", "")
        val stravaSecret = System.getenv("STRAVA_CLIENT_SECRET") ?: localProps.getProperty("STRAVA_CLIENT_SECRET", "")
        buildConfigField("String", "STRAVA_CLIENT_ID", "\"$stravaId\"")
        buildConfigField("String", "STRAVA_CLIENT_SECRET", "\"$stravaSecret\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                if (keystorePropsFile.exists()) {
                    storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                } else {
                    storeFile = file(envKeystore!!)
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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
    testImplementation(libs.junit)

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
