import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType

/**
 * Convention for Android modules using Jetpack Compose (passes-ui).
 *
 * Diverges from walt-android's ComposeConventionPlugin by NOT auto-adding Compose BOM
 * or core Compose libraries. passes-ui exposes its Compose surface to walt-android via
 * `api(...)` rather than `implementation(...)`, so dependency strength is module-specific
 * and stays in the module's build.gradle.kts.
 *
 * Must be applied after walt.passes.android.library.
 *
 * Usage:
 * ```
 * plugins {
 *     alias(libs.plugins.walt.passes.android.library)
 *     alias(libs.plugins.walt.passes.android.compose)
 * }
 * ```
 */
class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

            pluginManager.apply(libs.findPlugin("kotlin-compose").get().get().pluginId)

            val androidExtension = extensions.findByType(LibraryExtension::class.java)
                ?: error(
                    "is.walt.passes.android.compose must be applied after " +
                        "is.walt.passes.android.library (Android Library plugin not found)."
                )

            androidExtension.apply {
                buildFeatures {
                    compose = true
                }
            }
        }
    }
}
