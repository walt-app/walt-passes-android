plugins {
    alias(libs.plugins.walt.passes.android.library)
    alias(libs.plugins.walt.passes.quality)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "is.walt.passes.storage"

    // Gradle managed devices over a third-party emulator action: ReactiveCircus's
    // android-emulator-runner is a community-published action and would require an
    // allowlist exception (the same constraint that excludes android-actions/setup-android,
    // see .github/workflows/ci.yml). AGP managed devices live entirely inside Gradle and
    // need no third-party action.
    //
    // System image choice: google_apis (selected via systemImageSource = "google"). NOT
    // google_apis_playstore. Play Store images forbid `bmgr backupnow` and the userdebug
    // shell access AutoBackupBmgrTest depends on; AOSP images would also work but
    // google_apis is the conventional default and matches what the bead text calls for.
    //
    // The four arms cover the API floor (28 = minSdk, where StrongBox detection landed),
    // the SECURITY_LEVEL_STRONGBOX KeyInfo enum introduction (API 31 = S), the current
    // long-term LTS image (API 34), and head (API 36 = compileSdk).
    testOptions {
        managedDevices {
            localDevices {
                create("api28google") {
                    device = "Pixel 2"
                    apiLevel = 28
                    systemImageSource = "google"
                }
                create("api31google") {
                    device = "Pixel 2"
                    apiLevel = 31
                    systemImageSource = "google"
                }
                create("api34google") {
                    device = "Pixel 2"
                    apiLevel = 34
                    systemImageSource = "google"
                }
                create("api36google") {
                    device = "Pixel 2"
                    apiLevel = 36
                    systemImageSource = "google"
                }
            }
        }
    }
}

dependencies {
    api(project(":passes-core"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.xerial.sqlite.jdbc)
    testImplementation(libs.androidx.test.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
