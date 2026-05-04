// JVM-only today. The implementation bead replaces this with the Android library plugin
// to bring in SQLCipher and Android Keystore. The public-API types defined here are
// intentionally Android-agnostic so that test doubles and the JVM CI host can exercise them
// without an Android device.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
}

dependencies {
    api(project(":passes-core"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}
