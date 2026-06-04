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

    // Shared isolated-worker plumbing (bind/teardown session). The decode service is bound
    // through this facade rather than a barcode-private copy; PDF render is the other
    // consumer (wpass-zrt.6). Internal seam only — no isolation type appears on this
    // module's public surface — so implementation, not api.
    implementation(project(":passes-isolation"))

    // Pure-JVM symbol decode (wpass-zrt.4). com.google.zxing:core is Apache-2.0 and carries
    // ZERO native attack surface — it runs only inside the isolated sandbox and never on the
    // public surface, so implementation, not api. ML Kit was rejected (telemetry + Play
    // Services dep); zxing-cpp stays a sandboxed contingency only.
    implementation(libs.zxing.core)

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
