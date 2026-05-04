import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Convention for pure Kotlin/JVM library modules (passes-core).
 *
 * Enforces explicit API mode and `-Xjvm-default=all` for the trust-claim surface.
 */
class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("org.jetbrains.kotlin.jvm")
                apply("java-library")
            }

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            extensions.configure<KotlinJvmProjectExtension> {
                explicitApi = ExplicitApiMode.Strict
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                    progressiveMode.set(true)
                    freeCompilerArgs.addAll("-Xjvm-default=all")
                }
            }
        }
    }
}
