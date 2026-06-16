plugins {
    alias(libs.plugins.walt.passes.kotlin.library)
    alias(libs.plugins.walt.passes.quality)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // ExportableArtifact, ArtifactKind — the shared contract this module implements.
    api(project(":passes-export-core"))
    // Pass, ScannableCard, PassInstant, SignatureStatus — all on the public export surface.
    api(project(":passes-core"))
    // PdfDocument — on the public export surface.
    api(project(":passes-pdf-core"))

    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
