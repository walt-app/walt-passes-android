plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "is.walt.passes.storage"
    compileSdk = 36

    defaultConfig {
        // StrongBox-backed Keystore lands at API 28; aligning with passes-ui (also minSdk 28)
        // keeps the trust-claim story consistent across the three modules.
        minSdk = 28

        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjvm-default=all")
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
