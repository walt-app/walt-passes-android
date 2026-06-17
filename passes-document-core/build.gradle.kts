plugins {
    alias(libs.plugins.walt.passes.kotlin.library)
    alias(libs.plugins.walt.passes.quality)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
