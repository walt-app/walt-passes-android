plugins {
    alias(libs.plugins.walt.passes.kotlin.library)
    alias(libs.plugins.walt.passes.quality)
}

dependencies {
    // ScannableFormat is the symbology the BarcodedImageDocument arm carries (wpass-8lu). Both
    // modules are pure-JVM/-core, so this adds no Android edge; it mirrors passes-barcode-core
    // depending on passes-core for the same enum.
    api(project(":passes-core"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
