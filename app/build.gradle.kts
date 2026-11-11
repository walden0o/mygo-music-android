plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.walden.mygomusic"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.walden.mygomusic"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // OPTIONAL release signing (L16d). Skipped entirely when no keystore is configured,
        // so debug / CI builds never require secrets and `assembleRelease` still produces an
        // (unsigned) APK. Provide values via gradle properties (~/.gradle/gradle.properties)
        // or environment variables — NO keystore or secret is created or committed here:
        //   MYGO_KEYSTORE=/abs/path/to/release.keystore
        //   MYGO_KEYSTORE_PASSWORD=...   MYGO_KEY_ALIAS=...   MYGO_KEY_PASSWORD=...
        val keystorePath = (project.findProperty("MYGO_KEYSTORE") as String?)
            ?: System.getenv("MYGO_KEYSTORE")
        if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = (project.findProperty("MYGO_KEYSTORE_PASSWORD") as String?)
                    ?: System.getenv("MYGO_KEYSTORE_PASSWORD")
                keyAlias = (project.findProperty("MYGO_KEY_ALIAS") as String?)
                    ?: System.getenv("MYGO_KEY_ALIAS")
                keyPassword = (project.findProperty("MYGO_KEY_PASSWORD") as String?)
                    ?: System.getenv("MYGO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // L16d: shrink + obfuscate with the app rules (QuickJS JNI / kotlinx.serialization /
            // Media3 / OkHttp). VERIFIED: `./gradlew :app:assembleRelease` succeeds with both flags
            // on (minifyReleaseWithR8 + resource shrinking run; app-release-unsigned.apk produced).
            // R8 emits a benign "parsing kotlin metadata" warning (Kotlin 2.4.10 > this R8's released
            // support); the explicit keeps below protect the JNI/serialization entry points regardless.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Apply the optional release signing config only when a keystore was provided.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // Needed for BuildConfig.DEBUG guards (e.g. plugin console logging).
        buildConfig = true
    }
    lint {
        // The app is built on Media3's @UnstableApi session surface by design
        // (MediaSession / ForwardingPlayer have no stable equivalent). The two
        // declarations that expose unstable types in their signatures
        // (QueueForwardingPlayer, PlaybackService) are marked @UnstableApi;
        // the call-site opt-in check otherwise cascades into every UI file.
        disable += "UnsafeOptInUsageError"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.github.dokar3:quickjs-kt-android:1.0.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
