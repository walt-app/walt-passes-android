plugins {
    alias(libs.plugins.walt.passes.android.library)
    alias(libs.plugins.walt.passes.android.compose)
    alias(libs.plugins.walt.passes.quality)
}

android {
    namespace = "is.walt.passes.document.ui"

    // Same managed-device span as passes-ui; CI runs the api34googleDebugAndroidTest task.
    testOptions {
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

// Document-rendering UI surfaces. Carved out of passes-ui (wpass-r4z) so that the
// PKPASS-only passes-ui no longer transitively pulls in the Android-only
// PdfRendererBinder / SharedMemory machinery for every consumer that just renders
// passes. CLAUDE.md's "passes-storage / passes-pdf / passes-ui are independent"
// rule extends naturally to passes-document-ui as a fourth peer; the edges it has
// to its peers are the deliberate `api(passes-pdf)` / `api(passes-image)` here, which
// give a consumer of this module the binder types to construct.
dependencies {
    api(project(":passes-document-core"))
    // api (not implementation) because PdfRendererBinder is a parameter type on
    // DocumentView; consumers must construct one and the binder type has to be on
    // their compile classpath.
    api(project(":passes-pdf"))
    // The reserved passes-document-ui -> passes-image edge (CLAUDE.md): the image-document
    // display arm of DocumentView takes an `ImageDecodeBinder` the consumer constructs,
    // exactly as the PDF arm takes a `PdfRendererBinder`, so api (not implementation).
    api(project(":passes-image"))
    api(project(":passes-ui-core"))
    api(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    val composeBom = platform(libs.androidx.compose.bom)
    api(composeBom)

    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(composeBom)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.test.junit)
    // Declared so the runner version is the catalog's, not a ui-test-junit4 transitive.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
}
