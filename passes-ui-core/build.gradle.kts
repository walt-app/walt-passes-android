plugins {
    alias(libs.plugins.walt.passes.android.library)
    alias(libs.plugins.walt.passes.quality)
}

android {
    namespace = "is.walt.passes.ui.core"
}

// passes-ui-core has no `@Composable` functions — it ships a value class, a top-level
// `isolated()` function, and an extension that constructs a Compose `Color` from an
// ARGB int. The Compose compiler plugin is therefore not applied; the dependency on
// `androidx.compose.ui.graphics` is a regular library use, not a Compose surface.

// Tiny shared substrate consumed by both passes-ui and passes-pdf-ui. Nothing in here
// is trust-claim-bearing on its own — the BiDi isolation helper and the ARGB color
// value class are primitives whose security relevance comes from their *use sites*,
// which live in the surface modules. Keeping them here is what lets passes-ui and
// passes-pdf-ui remain peers without one depending on the other for these helpers.
dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    api(composeBom)
    api(libs.androidx.compose.ui.graphics)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
