import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Convention for Android library modules (passes-storage, passes-ui).
 *
 * Mirrors walt-android's AndroidLibraryConventionPlugin with two passes-specific tweaks:
 * - minSdk 28 instead of 26. StrongBox-backed Keystore (passes-storage) and
 *   ImageDecoder.setOnHeaderDecodedListener (passes-ui) both land at API 28; aligning
 *   the whole repo at minSdk 28 keeps the trust-claim story consistent across modules.
 * - explicitApi + -Xjvm-default=all enforced via Kotlin compiler options.
 *
 * AGP 9 has built-in Kotlin support, so org.jetbrains.kotlin.android is not applied.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")

            extensions.configure<LibraryExtension> {
                compileSdk = 36

                defaultConfig {
                    minSdk = 28
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }

                buildFeatures {
                    aidl = false
                    shaders = false
                }

                testOptions {
                    unitTests {
                        isIncludeAndroidResources = true
                        isReturnDefaultValues = true
                    }
                }
            }

            extensions.configure<KotlinAndroidProjectExtension> {
                explicitApi = ExplicitApiMode.Strict
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                    freeCompilerArgs.addAll("-Xjvm-default=all")
                }
            }
        }
    }
}
