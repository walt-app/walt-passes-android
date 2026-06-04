plugins {
    alias(libs.plugins.walt.passes.android.library)
    alias(libs.plugins.walt.passes.quality)
}

android {
    namespace = "is.walt.passes.barcode"

    // Inject the module dir so ManifestPermissionsTest can resolve src/main/AndroidManifest.xml
    // regardless of the JVM cwd (mirrors passes-pdf).
    testOptions {
        unitTests.all {
            it.systemProperty("walt.passes.barcode.moduleDir", projectDir.absolutePath)
        }
    }
}

dependencies {
    // Pure decode-result types (BarcodeDecodeResult, ScannableFormat) live in passes-core and
    // are part of this module's public surface, so expose them transitively via `api`.
    api(project(":passes-core"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
