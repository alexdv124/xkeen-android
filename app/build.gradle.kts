import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Release signing material. CI exports these as env vars (decoded from GitHub Secrets);
// locally they come from keystore.properties, which is gitignored next to the .jks itself.
// When neither source has a usable keystore the release APK is simply left unsigned, so a
// fresh clone still builds without any secrets.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val releaseStorePath: String? = System.getenv("KEYSTORE_FILE") ?: keystoreProps.getProperty("storeFile")
val releaseStorePassword: String? = System.getenv("KEYSTORE_PASSWORD") ?: keystoreProps.getProperty("storePassword")
val releaseKeyAlias: String? = System.getenv("KEY_ALIAS") ?: keystoreProps.getProperty("keyAlias")
val releaseKeyPassword: String? = System.getenv("KEY_PASSWORD") ?: keystoreProps.getProperty("keyPassword")
val releaseKeystore = releaseStorePath?.let { rootProject.file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.xkeen.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xkeen.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.4.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Set explicitly rather than relying on AGP defaults. v1 (JAR signing) is
                // dead weight at minSdk 26 — v2 landed in Android 7.0. v3 is what makes key
                // rotation possible later, so it is worth having from the very first release.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Deliberately off for the first signed release. Every APK shipped so far was
            // an unminified debug build, so R8 has never actually run on this code; turning
            // it on in the same change as signing would ship two untested things at once.
            // Enable it separately, with a run on a real device to confirm nothing broke.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity & Navigation
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.54")
    ksp("com.google.dagger:hilt-compiler:2.54")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // SSH
    implementation("com.github.mwiede:jsch:0.2.21")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")

    // Test
    testImplementation("junit:junit:4.13.2")
}
