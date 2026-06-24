import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Shared signing — :wear and :mobile must use the SAME key to pair over the Data Layer.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.wearosgpx"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wearosgpx"   // MUST match :mobile for Data Layer pairing
        minSdk = 33                        // Wear OS 4
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
    buildFeatures { compose = true }

    // False positive for Compose/no-Fragment apps using registerForActivityResult.
    lint { disable += "InvalidFragmentVersionForActivityResult" }
}

// Emit Room schema JSON (enables migration history; required by exportSchema = true)
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.service)        // foreground tracking Service base class
    implementation(libs.activity.compose)
    implementation(libs.coroutines.play.services)
    implementation(libs.concurrent.futures.ktx)
    implementation(libs.guava)
    implementation(libs.kotlinx.serialization.json)

    // Compose via BOM (versions resolved by the platform)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Wear (ambient / always-on support)
    implementation(libs.androidx.wear)

    // Wear Compose
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.tooling.preview)
    implementation(libs.horologist.compose.layout)

    // The engine (Phase 2)
    implementation(libs.health.services.client)

    // Local track log (Phase 1)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Data Layer handoff (Phase 4)
    implementation(libs.play.services.wearable)
}
