import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

/**
 * Convention for code quality tools (detekt + ktlint), mirroring walt-android.
 *
 * Usage:
 * ```
 * plugins {
 *     alias(libs.plugins.walt.passes.quality)
 * }
 * ```
 */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("io.gitlab.arturbosch.detekt")
                apply("org.jlleitschuh.gradle.ktlint")
            }

            extensions.configure<DetektExtension> {
                config.setFrom(files("$rootDir/detekt.yml"))
                buildUponDefaultConfig = true
                allRules = false
                // Per-module baseline pins existing findings so detekt only fails on
                // *new* violations introduced going forward. File is checked in.
                val baselineFile = file("detekt-baseline.xml")
                if (baselineFile.exists()) {
                    baseline = baselineFile
                }
            }

            tasks.withType<Detekt>().configureEach {
                jvmTarget = "17"
            }

            val libs = extensions.getByType(
                VersionCatalogsExtension::class.java,
            ).named("libs")
            dependencies {
                add("detektPlugins", libs.findLibrary("detekt-formatting").get())
            }

            extensions.configure<KtlintExtension> {
                android.set(true)
                ignoreFailures.set(false)
                reporters {
                    reporter(ReporterType.PLAIN)
                    reporter(ReporterType.CHECKSTYLE)
                }
            }
        }
    }
}
