plugins {
    alias(libs.plugins.walt.passes.android.library)
    alias(libs.plugins.walt.passes.android.compose)
    alias(libs.plugins.walt.passes.quality)
}

android {
    namespace = "is.walt.passes.ui"

    testOptions {
        // Managed-device matrix for ScannableCardFaceTintInstrumentedTest (wpass-80y.5),
        // mirroring passes-barcode / passes-storage. The face-tint pin has to read painted
        // pixels, which Robolectric cannot do, so it only protects the seam if a real device
        // runs it. Same API span as the sibling modules: 28 is the repo minSdk, 31/34/36
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
    api(project(":passes-core"))
    // PKPASS-only module. Document-rendering surfaces live in :passes-document-ui (which
    // takes the passes-pdf dep). passes-ui-core hosts the BiDi (FSI/PDI) helper and
    // the ArgbColor value class that both UI modules consume; isolating those there
    // keeps passes-ui and passes-document-ui from depending on each other.
    api(project(":passes-ui-core"))
    api(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // The bounded ImageDecoder mechanism (containment-hardened compressed-bytes -> Bitmap),
    // shared with passes-barcode and the wpass-i9x image-document service. This module keeps
    // its own decode policy (ImageRenderBounds caps, the ImageDecodeRejection taxonomy, and
    // the in-process display posture that lets an OOM propagate) and delegates only the
    // mechanism. Internal seam — no passes-image-decode type appears on this module's surface.
    implementation(project(":passes-image-decode"))
    // androidx.activity.compose pulls in BackHandler, which the trust-claim-bearing
    // PassImportConfirm uses so system back-press routes through the same dismiss
    // telemetry as the Cancel button (no silent escape hatch).
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    api(composeBom)

    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(composeBom)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.test.junit)
    // Declared, not inherited. AndroidLibraryConventionPlugin names
    // androidx.test.runner.AndroidJUnitRunner for every module, but without this the class
    // arrives only as a transitive of ui-test-junit4 — at 1.5.0, behind the catalog's 1.7.0.
    // A BOM bump that reshuffles those transitives would take out all four API legs with a
    // runner-not-found that looks like a face-tint failure.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
}
