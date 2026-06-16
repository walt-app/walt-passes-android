plugins {
    alias(libs.plugins.walt.passes.android.library)
    alias(libs.plugins.walt.passes.quality)
}

android {
    namespace = "is.walt.passes.image.decode"
}

dependencies {
    // No project deps: the rejection type R is the caller's, so this module names no
    // policy of its own. It depends only on android.graphics (the framework) for the
    // ImageDecoder lever. Both consumers (passes-barcode, passes-ui) sit above it.

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.truth)
}
