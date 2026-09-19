import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.you.tube"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.you.tube"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // ---------------------------------------------------------------------------
    // Release signing.
    //
    // Precedence:
    //   1. Environment variables (CI secrets): YT_KEYSTORE_PATH / YT_KEYSTORE_PASSWORD
    //      / YT_KEY_ALIAS / YT_KEY_PASSWORD
    //   2. Committed sideload keystore  signing/you-tube-release.p12
    //
    // The committed keystore keeps the APK signature stable across CI builds so
    // installed apps can be upgraded in place. Replace it with your own keystore
    // for serious distribution (see signing/README.md).
    // ---------------------------------------------------------------------------
    val releaseKeystore = rootProject.file("signing/you-tube-release.p12")
    val envKeystorePath = System.getenv("YT_KEYSTORE_PATH")

    signingConfigs {
        create("release") {
            storeType = "PKCS12"
            storePassword = System.getenv("YT_KEYSTORE_PASSWORD") ?: "you-tube-release"
            keyAlias = System.getenv("YT_KEY_ALIAS") ?: "you-tube"
            keyPassword = System.getenv("YT_KEY_PASSWORD") ?: "you-tube-release"
            storeFile = when {
                envKeystorePath != null -> file(envKeystorePath)
                releaseKeystore.exists() -> releaseKeystore
                else -> null
            }
        }
    }

    buildTypes {
        release {
            // Aggressive R8 full-mode minification + resource shrinking
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (envKeystorePath != null || releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time & friends for NewPipeExtractor on minSdk 24
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/DEPENDENCIES"
            )
        }
    }

    lint {
        // Media3's @UnstableApi opt-in is handled via annotations; do not fail CI on it.
        disable += "UnsafeOptInUsageError"
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Kotlin / coroutines / serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)

    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Jetpack Compose (BOM-pinned)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    // Media3 (ExoPlayer) playback stack
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    // Networking — one shared OkHttp instance for everything (HTTP/2 pooling)
    implementation(libs.okhttp)

    // Image pipeline
    implementation(libs.coil.compose)

    // Stream extraction (NewPipeExtractor — same coordinates the NewPipe app itself uses)
    implementation(libs.newpipe.extractor)
    implementation(libs.newpipe.nanojson)

    // Desugaring
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
