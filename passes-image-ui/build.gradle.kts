plugins {
    alias(libs.plugins.walt.passes.android.library)
    alias(libs.plugins.walt.passes.android.compose)
    alias(libs.plugins.walt.passes.quality)
}

android {
    namespace = "is.walt.passes.image.ui"
}

// Image-rendering UI surfaces. Carved out as an independent peer alongside passes-pdf-ui
// so the PKPASS-only passes-ui does not transitively pull in image-decode machinery. The
// module takes decoded ImageBitmap values from the consumer (host ViewModel decodes via
// BitmapFactory); it does NOT depend on passes-image, keeping the importer and the
// display layer as independent peers per the project module rules.
dependencies {
    api(project(":passes-image-core"))
    api(project(":passes-ui-core"))

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
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
}
