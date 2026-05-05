package `is`.walt.passes.storage

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.os.Build
import androidx.annotation.VisibleForTesting
import `is`.walt.passes.storage.BackupRulesContract.REQUIRED_EXCLUDES
import `is`.walt.passes.storage.BackupRulesContract.RequiredExclude
import `is`.walt.passes.storage.BackupRulesContract.Section
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException

/**
 * Verifies that the trust claim "pass data is excluded from cloud backup" holds in the
 * consumer's merged manifest. Intended for the consumer's test suite (Robolectric or
 * on-device instrumentation) so that the claim is checkable in CI rather than relying
 * on manifest review.
 *
 * The library ships `walt_passes_backup_rules.xml` (API 23 - 30) and
 * `walt_passes_data_extraction_rules.xml` (API 31+). Two consumer postures are valid:
 *
 *  1. **Inherit.** The consumer lets the library's manifest contributions reach the
 *     merged manifest unchanged. The merged `ApplicationInfo` then references the
 *     library's resource directly.
 *  2. **Mirror.** The consumer overrides the library's contributions with `tools:replace`
 *     on `android:fullBackupContent` and/or `android:dataExtractionRules`, pointing at a
 *     consumer-owned XML resource that **mirrors** the required `<exclude>` entries.
 *     Walt-android does this so it can manage backup posture for the whole app from a
 *     single resource.
 *
 * Both postures satisfy the trust claim. The assertion validates by **content**, not
 * resource identity: it opens whichever XML resource the merged manifest points at and
 * confirms every entry in [BackupRulesContract.REQUIRED_EXCLUDES] is present in every
 * backup-relevant section. Consumers may add additional excludes; only the pass-related
 * entries are required.
 *
 * If the consumer has set `android:allowBackup="false"` app-wide, the trust claim is
 * trivially satisfied and the assertion returns [Outcome.Applied] without parsing rules.
 *
 * Reflection on `ApplicationInfo.fullBackupContent` and
 * `ApplicationInfo.dataExtractionRulesRes` is intentional: those greylisted hidden-API
 * fields are the most reliable read of the merged manifest. They may be moved to the
 * blocklist in a future Android release; if that happens, [Outcome.FieldUnavailable] is
 * the surface for triage and a manifest-XML fallback is the expected mitigation.
 */
public object BackupRulesAssertion {

    /** A section that is missing one or more required excludes. */
    public data class MissingSection(
        public val resourceId: Int,
        public val section: Section,
        public val missing: List<RequiredExclude>,
    )

    public sealed interface Outcome {
        /**
         * Trust claim holds: every required exclude is present in every relevant
         * section, or the consumer disabled backup app-wide.
         */
        public data object Applied : Outcome

        /**
         * One or more sections of the consumer's rules resource(s) are missing required
         * excludes. The list contains every gap discovered.
         */
        public data class MissingExcludes(public val problems: List<MissingSection>) : Outcome

        /**
         * Manifest declares no backup rules resource at all (and `allowBackup` is true).
         * The default Auto Backup policy would include the passes database.
         */
        public data object NoBackupRulesConfigured : Outcome

        /** A rules resource referenced by the manifest could not be opened or parsed. */
        public data class RulesResourceUnreadable(
            public val resourceId: Int,
            public val reason: String,
        ) : Outcome

        /**
         * `PackageManager` could not find the running package. Should not happen in
         * normal app or instrumentation contexts; surfaced for defensive completeness.
         */
        public data object PackageInfoUnavailable : Outcome

        /**
         * A required `ApplicationInfo` field is not reachable via reflection on the
         * current platform. Indicates an incompatible OS-side change; surface for triage
         * rather than masquerading as a content failure.
         */
        public data object FieldUnavailable : Outcome
    }

    @JvmStatic
    public fun assertBackupRulesApplied(context: Context): Outcome {
        val appInfo = loadApplicationInfo(context) ?: return Outcome.PackageInfoUnavailable
        return assertBackupRulesApplied(appInfo, context.resources)
    }

    /**
     * Test seam. Lets unit tests construct a fresh [ApplicationInfo] (e.g., via Parcel
     * round-trip) and pass it directly, avoiding mutation of the Robolectric-shared
     * `ApplicationInfo` returned by `Context.applicationInfo`.
     */
    @VisibleForTesting
    internal fun assertBackupRulesApplied(appInfo: ApplicationInfo, resources: Resources): Outcome =
        if (appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0) {
            Outcome.Applied
        } else {
            evaluateRules(appInfo, resources)
        }

    private fun evaluateRules(appInfo: ApplicationInfo, resources: Resources): Outcome {
        val resIds = readResourceIds(appInfo)
        return when {
            resIds == null -> Outcome.FieldUnavailable
            resIds.fullBackup == 0 && resIds.dxr == 0 -> Outcome.NoBackupRulesConfigured
            else -> reduce(
                fullBackup = validateResource(
                    resources = resources,
                    resId = resIds.fullBackup,
                    sections = listOf(Section.FullBackupContent),
                ),
                dxr = validateResource(
                    resources = resources,
                    resId = resIds.dxr,
                    sections = listOf(Section.CloudBackup, Section.DeviceTransfer),
                ),
            )
        }
    }

    private fun reduce(fullBackup: ValidationResult, dxr: ValidationResult): Outcome {
        // If both arms are unreadable, only the first is reported. The Outcome shape
        // (single resourceId + reason) does not model simultaneous failures; a consumer
        // fixing the first surfaces the second on the next run.
        val unreadable = fullBackup as? ValidationResult.Unreadable
            ?: dxr as? ValidationResult.Unreadable
        if (unreadable != null) return unreadable.outcome
        val problems = (fullBackup as ValidationResult.Ok).problems +
            (dxr as ValidationResult.Ok).problems
        return if (problems.isEmpty()) Outcome.Applied else Outcome.MissingExcludes(problems)
    }

    private data class ResourceIds(val fullBackup: Int, val dxr: Int)

    private fun readResourceIds(appInfo: ApplicationInfo): ResourceIds? {
        val full = readIntField(appInfo, "fullBackupContent")
        val dxr = readDxrResId(appInfo)
        return if (full != null && dxr != null) ResourceIds(fullBackup = full, dxr = dxr) else null
    }

    private fun readDxrResId(appInfo: ApplicationInfo): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            readIntField(appInfo, "dataExtractionRulesRes")
        } else {
            0
        }

    private sealed interface ValidationResult {
        data class Ok(val problems: List<MissingSection>) : ValidationResult
        data class Unreadable(val outcome: Outcome.RulesResourceUnreadable) : ValidationResult
    }

    private fun validateResource(
        resources: Resources,
        resId: Int,
        sections: List<Section>,
    ): ValidationResult {
        if (resId == 0) return ValidationResult.Ok(emptyList())
        return when (val attempt = parseResource(resources, resId)) {
            is ParseAttempt.Failed ->
                ValidationResult.Unreadable(Outcome.RulesResourceUnreadable(resId, attempt.reason))
            is ParseAttempt.Success -> {
                val problems = sections.mapNotNull { section ->
                    val excludes = attempt.sections[section].orEmpty()
                    val missing = REQUIRED_EXCLUDES.filterNot { it in excludes }
                    if (missing.isEmpty()) null else MissingSection(resId, section, missing)
                }
                ValidationResult.Ok(problems)
            }
        }
    }

    private sealed interface ParseAttempt {
        data class Success(val sections: Map<Section, Set<RequiredExclude>>) : ParseAttempt
        data class Failed(val reason: String) : ParseAttempt
    }

    private fun parseResource(resources: Resources, resId: Int): ParseAttempt {
        val parser: XmlResourceParser = try {
            resources.getXml(resId)
        } catch (e: Resources.NotFoundException) {
            return ParseAttempt.Failed(e.message ?: "resource not found")
        }
        return try {
            ParseAttempt.Success(BackupRulesXmlParser.parseRulesXml(parser))
        } catch (e: BackupRulesXmlParser.ParseException) {
            ParseAttempt.Failed(e.message ?: "malformed rules document")
        } catch (e: XmlPullParserException) {
            ParseAttempt.Failed(e.message ?: "xml parse error")
        } catch (e: IOException) {
            ParseAttempt.Failed(e.message ?: "io error")
        } finally {
            parser.close()
        }
    }

    private fun loadApplicationInfo(context: Context): ApplicationInfo? = try {
        context.packageManager.getApplicationInfo(context.packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun readIntField(target: Any, name: String): Int? = try {
        val field = target.javaClass.getDeclaredField(name).also { it.isAccessible = true }
        field.getInt(target)
    } catch (_: NoSuchFieldException) {
        null
    } catch (_: IllegalAccessException) {
        null
    }
}
