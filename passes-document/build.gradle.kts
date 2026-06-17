plugins {
    alias(libs.plugins.walt.passes.android.library)
    alias(libs.plugins.walt.passes.quality)
}

android {
    namespace = "is.walt.passes.document"
}

// Document-import orchestrator. The single seam that magic-byte-sniffs PDF vs image and
// branches to the right isolated backend — passes-pdf's renderer service for PDFs,
// passes-image's decode sandbox for images. It is the ONE place the two otherwise-independent
// peers meet (CLAUDE.md: passes-pdf and passes-image are independent peers with no edge
// between them); this module sits ABOVE both, the way passes-document-ui sits above passes-pdf.
//
// Trust posture: this module exists so the sniff-and-branch orchestration is a single audited
// surface in this repository rather than reassembled in walt-android, honouring the DECISIVE
// CONSTRAINT. It holds NO decode/render code of its own; both heavy backends stay isolated.
dependencies {
    // PdfDocument / ImageDocument / DocumentRejectedKind / isPdfHeader / PdfImportConfig all
    // appear on this module's public result + config surface, so api (not implementation).
    api(project(":passes-document-core"))
    // ImageDecodeRejectedKind is carried on DocumentImportResult.ImageRejected (public), so api.
    api(project(":passes-image"))
    api(libs.kotlinx.coroutines.core)

    // PdfImporter (the PDF backend) is invoked internally; its types do not escape on the
    // public surface, so implementation, not api.
    implementation(project(":passes-pdf"))
    // Shared memfd plumbing: the sniffed bytes are materialized once into a sealed in-RAM PFD
    // and handed to whichever backend wins, so a one-shot fd source is read exactly once
    // (no offset-corruption between the sniff read and the backend read). Internal seam.
    implementation(project(":passes-isolation"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
