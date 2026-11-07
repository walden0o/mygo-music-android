# ============================================================================
# MyGo Music — release ProGuard / R8 rules.
# Wired into the release build type via proguardFiles in app/build.gradle.kts.
# Dependency AARs already contribute consumer rules (quickjs proguard.txt,
# okhttp META-INF/proguard/okhttp3.pro, media3-exoplayer proguard.txt); the rules
# below complement them and protect the app's own reflective / JNI entry points.
# ============================================================================

# Keep generic signatures / inner-class info / annotations — required by
# kotlinx.serialization (generic serializer resolution) and JNI/reflective lookups.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# ----------------------------------------------------------------------------
# QuickJS (io.github.dokar3:quickjs-kt-android) — JNI.
# libquickjs.so registers/invokes natives by name and calls back into Kotlin
# binding classes. The AAR's consumer rules only keep a handful of classes;
# keep the whole package so shrinking/renaming can never break the JNI bridge.
# DO NOT downgrade quickjs-kt (1.0.10 carries the 16 KB page-alignment fix).
# ----------------------------------------------------------------------------
-keep class com.dokar.quickjs.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.dokar.quickjs.** {
    native <methods>;
}
-dontwarn com.dokar.quickjs.**

# ----------------------------------------------------------------------------
# kotlinx.serialization — keep @Serializable models, their Companion objects,
# the compiler-generated $$serializer classes and serializer() factories.
# (Official recommended rule set, scoped to this app's models.)
# ----------------------------------------------------------------------------
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Application models (Models.kt etc.) are @Serializable.
-keep,includedescriptorclasses class dev.walden.mygomusic.**$$serializer { *; }
-keepclassmembers class dev.walden.mygomusic.** {
    *** Companion;
}
-keepclasseswithmembers class dev.walden.mygomusic.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class dev.walden.mygomusic.** { *; }

# ----------------------------------------------------------------------------
# Media3 / ExoPlayer.
# media3-exoplayer & media3-decoder AARs ship consumer rules for the constructors
# accessed reflectively by DefaultRenderersFactory / DefaultExtractorsFactory.
# media3-session ships NO consumer rules, and the platform queue/notification /
# legacy MediaSessionCompat path touches classes reflectively, so keep Media3
# intact as a safety net (correctness over APK size for the core playback stack).
# MediaButtonReceiver is declared in the manifest (AGP also keeps it automatically).
# ----------------------------------------------------------------------------
-keep class androidx.media3.** { *; }
-keep class androidx.media3.session.MediaButtonReceiver { *; }
-dontwarn androidx.media3.**

# ----------------------------------------------------------------------------
# OkHttp / Okio — both ship consumer rules (okhttp3.pro / okio). These are the
# documented extra dontwarns for optional JVM-only security providers.
# ----------------------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn javax.annotation.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase