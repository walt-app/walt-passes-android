// JVM-only today. The implementation bead replaces this with the Android library plugin
// to bring in Jetpack Compose, Material3, ZXing, and Coil. The public-API types defined
// here are intentionally Android-agnostic: theming contracts use packed ARGB integers (the
// same shape `passes-core` uses for `ColorValue`) and intent payloads are pure data
// classes, so test doubles and the JVM CI host can exercise the contract without an
// Android device.
//
// The composable signatures themselves are documented in passes-ui/COMPOSABLE_SIGNATURES.md
// rather than declared as `@Composable fun ...` here, since `androidx.compose.runtime` is
// not on this skeleton's classpath.

plugins {
    alias(libs.plugins.kotlin.jvm)
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

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test {
    useJUnit()
}
