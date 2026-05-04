plugins {
    alias(libs.plugins.walt.passes.android.library)
    alias(libs.plugins.walt.passes.quality)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "is.walt.passes.storage"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":passes-core"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // SQLCipher: encrypted SQLite at rest. ADR 0002 D1/D2.
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    // JVM-side tests: schema-DDL via xerial sqlite-jdbc; Robolectric for the StateFlow +
    // delete contract (no SQLCipher native libs needed — the repo is constructed with an
    // in-memory PassStore fake that exercises the contract without touching JNI).
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.xerial.sqlite.jdbc)
    testImplementation(libs.androidx.test.junit)

    androidTestImplementation(libs.androidx.test.junit)
}
