import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing comes from a keystore that lives on jerry, never in the repo:
// pass -PmarqueeKeystore=/keys/keystore.properties (storeFile, storePassword, keyAlias, keyPassword).
val keyProps = Properties().apply {
    (findProperty("marqueeKeystore") as String?)?.let { p -> file(p).takeIf { it.exists() }?.inputStream()?.use { load(it) } }
}

android {
    namespace = "com.basdhaweio.marquee"
    compileSdk = 35
    buildToolsVersion = "35.0.0"   // the only build-tools baked into the android-build image

    defaultConfig {
        applicationId = "com.basdhaweio.marquee"
        minSdk = 26
        targetSdk = 35
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "1.0.0"
        buildConfigField("String", "DEFAULT_SERVER", "\"https://jerry-eq.tail242977.ts.net/hub\"")
        buildConfigField("String", "DEV_TOKEN", "\"\"")
    }

    signingConfigs {
        if (keyProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Debug builds are for the emulator on jerry: they talk to a throwaway Marquee started with
        // DEV_NO_AUTH=1 on 127.0.0.1:8891 (10.0.2.2 from inside the emulator) and skip Plex sign-in.
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "DEFAULT_SERVER", "\"http://10.0.2.2:8891/hub\"")
            buildConfigField("String", "DEV_TOKEN", "\"dev\"")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
