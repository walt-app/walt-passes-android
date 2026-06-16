plugins {
    alias(libs.plugins.walt.passes.android.library)
    alias(libs.plugins.walt.passes.quality)
}

android {
    namespace = "is.walt.passes.image"

    // Inject the module dir so ManifestPermissionsTest can resolve src/main/AndroidManifest.xml
    // regardless of the JVM cwd (mirrors passes-pdf / passes-barcode).
    testOptions {
        unitTests.all {
            it.systemProperty("walt.passes.image.moduleDir", projectDir.absolutePath)
        }

        // Managed-device matrix for the on-device decode/isolation suite, mirroring
        // passes-barcode. API 28 is the ImageDecoder floor the bounded decode rests on; 31/34/36
        // track S, the LTS image, and head. The CI connected-tests job runs the matching
        // apiNNgoogleDebugAndroidTest tasks.
        managedDevices {
            localDevices {
                create("api28google") {
                    device = "Pixel 2"
                    apiLevel = 28
                    systemImageSource = "google"
                }
                create("api31google") {
                    device = "Pixel 2"
                    apiLevel = 31
                    systemImageSource = "google"
                }
                create("api34google") {
                    device = "Pixel 2"
                    apiLevel = 34
                    systemImageSource = "google"
                }
                create("api36google") {
                    device = "Pixel 2"
                    apiLevel = 36
                    systemImageSource = "google"
                }
            }
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    // Shared isolated-worker plumbing (bind/teardown session). The decode service is bound
    // through this facade rather than an image-private copy; PDF render and barcode decode are
    // the other consumers. Internal seam only — no isolation type appears on this module's
    // public surface — so implementation, not api.
    implementation(project(":passes-isolation"))

    // The bounded ImageDecoder mechanism (containment-hardened compressed-bytes -> Bitmap) is
    // shared with passes-barcode and passes-ui. This module keeps its own decode policy
    // (ImageDecodeConfig caps, format allowlist, the ImageDecodeRejectedKind taxonomy) and
    // delegates only the mechanism. Internal seam — no passes-image-decode type appears on this
    // module's public surface.
    implementation(project(":passes-image-decode"))

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
