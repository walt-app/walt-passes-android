plugins {
    alias(libs.plugins.walt.passes.android.library)
    alias(libs.plugins.walt.passes.quality)
}

android {
    namespace = "is.walt.passes.pdf"

    // Inject the module directory as a system property so manifest-pinning unit tests
    // can resolve `src/main/AndroidManifest.xml` regardless of the JVM cwd. Gradle
    // happens to run unit tests from the module root, but IDE-runners and future
    // build-tool migrations are not contractually required to. Pinning the path here
    // turns a silent FileNotFound under a different cwd into a deterministic lookup.
    testOptions {
        unitTests.all {
            it.systemProperty("walt.passes.pdf.moduleDir", projectDir.absolutePath)
        }
    }
}

dependencies {
    api(project(":passes-pdf-core"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
