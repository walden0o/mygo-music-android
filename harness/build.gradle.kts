plugins {
    // Match the app's Kotlin version: quickjs-kt 1.0.10 metadata requires 2.4.x.
    kotlin("jvm") version "2.4.10"
    application
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // Keep the harness on the same engine version as the app (app/build.gradle.kts).
    implementation("io.github.dokar3:quickjs-kt-jvm:1.0.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

application {
    mainClass.set("MainKt")
}

tasks.withType<JavaExec> {
    jvmArgs("-Dfile.encoding=UTF-8")
}

// Single source of truth: the harness must exercise the SAME prelude the app
// ships. It used to carry a private copy that silently drifted out of sync,
// so "harness passes" said nothing about the real host.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("../app/src/main/assets/prelude.js"))
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

kotlin {
    jvmToolchain(17)
}
