package `is`.walt.passes.storage

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Verifies that the consumer's merged `AndroidManifest.xml` actually carries the
 * library-shipped backup-exclusion rules from ADR 0002 D5. Intended for the consumer's
 * instrumentation test suite; the trust claim "pass data is excluded from cloud backup"
 * becomes checkable in CI rather than relying on manifest review.
 *
 * The assertion only checks that the consumer's `<application>` references rule resources
 * named `walt_passes_backup_rules` (API 23-30) and `walt_passes_data_extraction_rules`
 * (API 31+). It does NOT parse the rules XML. Combined with the resources we ship, this
 * narrows the consumer-side risk to "the consumer overrode our rules with a non-walt
 * resource of the same name," which is a deliberate act, not an oversight.
 *
 * The two ApplicationInfo fields we check (`fullBackupContent` and `dataExtractionRulesRes`)
 * are hidden public-API fields in recent Android SDKs (greylisted, accessible via
 * reflection). Using reflection here is intentional: the alternative is parsing the
 * merged manifest XML by hand, which is more brittle than a hidden-API field whose
 * semantics have not changed since the feature shipped.
 */
public object BackupRulesAssertion {
    public sealed interface Outcome {
        public data object Applied : Outcome
        public data class FullBackupContentMismatch(public val actualResId: Int) : Outcome
        public data class DataExtractionRulesMissing(public val actualResId: Int) : Outcome
        public data object PackageInfoUnavailable : Outcome
        public data object FieldUnavailable : Outcome
    }

    @JvmStatic
    public fun assertBackupRulesApplied(context: Context): Outcome {
        val pm = context.packageManager
        val appInfo: ApplicationInfo = try {
            pm.getApplicationInfo(context.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return Outcome.PackageInfoUnavailable
        }

        val expectedFullBackup = context.resources
            .getIdentifier(FULL_BACKUP_CONTENT_RES_NAME, "xml", context.packageName)
        val actualFullBackup = readIntField(appInfo, "fullBackupContent")
            ?: return Outcome.FieldUnavailable
        if (actualFullBackup != expectedFullBackup) {
            return Outcome.FullBackupContentMismatch(actualFullBackup)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val expectedDxr = context.resources
                .getIdentifier(DATA_EXTRACTION_RULES_RES_NAME, "xml", context.packageName)
            val actualDxr = readIntField(appInfo, "dataExtractionRulesRes")
                ?: return Outcome.FieldUnavailable
            if (actualDxr != expectedDxr) {
                return Outcome.DataExtractionRulesMissing(actualDxr)
            }
        }
        return Outcome.Applied
    }

    private fun readIntField(target: Any, name: String): Int? = try {
        val field = target.javaClass.getDeclaredField(name).also { it.isAccessible = true }
        field.getInt(target)
    } catch (e: NoSuchFieldException) {
        null
    } catch (e: IllegalAccessException) {
        null
    }

    internal const val FULL_BACKUP_CONTENT_RES_NAME: String = "walt_passes_backup_rules"
    internal const val DATA_EXTRACTION_RULES_RES_NAME: String = "walt_passes_data_extraction_rules"
}
