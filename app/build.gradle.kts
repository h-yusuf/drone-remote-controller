plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.zora.drone"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.zora.drone"
        minSdk = 29
        targetSdk = 35
        // CI sets these from the tag (v0.2 -> "0.2") and run number, so every release installs over the last.
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("VERSION_NAME")?.removePrefix("v") ?: "0.1"
    }
    // Release key lives outside the repo: ~/.android/zora-release.jks locally, a secret in CI.
    // Without KEYSTORE_FILE, release falls back to the debug key.
    val keystore = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "zora"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug") }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    testImplementation("junit:junit:4.13.2")
}
