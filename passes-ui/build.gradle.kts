plugins {
    alias(libs.plugins.walt.passes.android.library)
    alias(libs.plugins.walt.passes.android.compose)
    alias(libs.plugins.walt.passes.quality)
}

android {
    namespace = "is.walt.passes.ui"
}

dependencies {
    api(project(":passes-core"))

    // ARCHITECTURAL CAVEAT (CLAUDE.md "passes-storage, passes-pdf, and passes-ui are
    // independent of each other"): adding passes-pdf here crosses that rule. The
    // alternative — a separate passes-pdf-ui module — would split DocumentView from
    // PassFront across modules even though both are pass-style trust-claim surfaces
    // sharing the same theming and isolation utilities. We accept the dependency so
    // composables that present documents live next to composables that present passes;
    // see the PR description for the trade-off discussion. The dep is `api` because
    // PdfRendererBinder is a parameter type on DocumentView (callers must construct
    // one), so pulling it in transitively is the usable shape for consumers.
    api(project(":passes-pdf"))
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
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
}
