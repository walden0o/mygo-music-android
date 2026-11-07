plugins {
    kotlin("jvm") version "2.0.20"
    application
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("io.github.dokar3:quickjs-kt-jvm:1.0.0-alpha13")
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

kotlin {
    jvmToolchain(17)
}
