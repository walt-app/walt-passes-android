import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

/** Convention for code quality tools (detekt + ktlint). */
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
                val baselineFile = file("detekt-baseline.xml")
                if (baselineFile.exists()) {
                    baseline = baselineFile
                }
            }

            tasks.withType<Detekt>().configureEach {
                jvmTarget = JvmTarget.JVM_17.target
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
