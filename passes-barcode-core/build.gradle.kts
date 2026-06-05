plugins {
    alias(libs.plugins.walt.passes.kotlin.library)
    alias(libs.plugins.walt.passes.quality)
}

dependencies {
    // Pure decode-result types (BarcodeDecodeResult, ScannableFormat) live in passes-core and
    // are part of this module's public surface, so expose them transitively via `api`.
    api(project(":passes-core"))

    // decodeLuminance takes a ZXing LuminanceSource on its public surface, so com.google.zxing:core
    // is `api`, not implementation. It is Apache-2.0 and 100% JVM — zero native attack surface.
    api(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
