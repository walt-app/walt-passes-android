plugins {
    alias(libs.plugins.walt.passes.android.library)
    alias(libs.plugins.walt.passes.quality)
}

android {
    namespace = "is.walt.passes.isolation"

    // Same managed-device span as passes-ui; CI runs the api34googleDebugAndroidTest task.
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
    // The bind-session API hands a typed client back to callers via a suspend function, so
    // coroutines is part of this module's public surface.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
