package `is`.walt.passes.storage

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.os.Build
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException

/**
 * Verifies that the trust claim "pass data is excluded from cloud backup" holds in the
 * consumer's merged manifest. Intended for the consumer's instrumentation test suite so
 * that the claim is checkable in CI rather than relying on manifest review.
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
 * confirms every entry in [REQUIRED_EXCLUDES] is present in every backup-relevant
 * section of that resource. A consumer is free to add their own additional excludes;
 * only the pass-related entries are required.
 *
 * If the consumer has set `android:allowBackup="false"` app-wide, the trust claim is
 * trivially satisfied (no surface for pass data to leak through) and the assertion
 * returns [Outcome.Applied] without parsing rules.
 *
 * Reflection on `ApplicationInfo.fullBackupContent` and `ApplicationInfo.dataExtractionRulesRes`
 * is intentional: the alternative is parsing the merged manifest XML by hand, which is
 * more brittle than greylisted hidden-API fields whose semantics have not changed since
 * the feature shipped.
 */
public object BackupRulesAssertion {

    /** Backup rule sections the assertion validates. */
    public enum class Section { FullBackupContent, CloudBackup, DeviceTransfer }

    /** A single `<exclude domain="..." path="..."/>` entry. */
    public data class RequiredExclude(public val domain: String, public val path: String)

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

    /** Entries the library requires every consumer rules resource to carry. */
    public val REQUIRED_EXCLUDES: List<RequiredExclude> = listOf(
        RequiredExclude(domain = "database", path = "walt_passes.db"),
        RequiredExclude(domain = "database", path = "walt_passes.db-journal"),
        RequiredExclude(domain = "database", path = "walt_passes.db-wal"),
        RequiredExclude(domain = "database", path = "walt_passes.db-shm"),
        RequiredExclude(domain = "sharedpref", path = "is.walt.passes.storage.key_envelope.xml"),
    )

    @JvmStatic
    public fun assertBackupRulesApplied(context: Context): Outcome {
        val appInfo = loadApplicationInfo(context) ?: return Outcome.PackageInfoUnavailable

        if (appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0) {
            return Outcome.Applied
        }

        val fullBackupResId = readIntField(appInfo, "fullBackupContent")
            ?: return Outcome.FieldUnavailable
        val dxrResId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            readIntField(appInfo, "dataExtractionRulesRes") ?: return Outcome.FieldUnavailable
        } else {
            0
        }

        if (fullBackupResId == 0 && dxrResId == 0) {
            return Outcome.NoBackupRulesConfigured
        }

        val problems = mutableListOf<MissingSection>()
        validateResource(
            context = context,
            resId = fullBackupResId,
            sections = listOf(Section.FullBackupContent),
            problems = problems,
        )?.let { return it }
        validateResource(
            context = context,
            resId = dxrResId,
            sections = listOf(Section.CloudBackup, Section.DeviceTransfer),
            problems = problems,
        )?.let { return it }

        return if (problems.isEmpty()) Outcome.Applied else Outcome.MissingExcludes(problems.toList())
    }

    private fun loadApplicationInfo(context: Context): ApplicationInfo? = try {
        context.packageManager.getApplicationInfo(context.packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Parses [resId] (if non-zero) and appends a [MissingSection] for every entry in
     * [sections] that is missing a required exclude. Returns a short-circuit
     * [Outcome.RulesResourceUnreadable] if the resource cannot be opened or parsed.
     */
    private fun validateResource(
        context: Context,
        resId: Int,
        sections: List<Section>,
        problems: MutableList<MissingSection>,
    ): Outcome? {
        if (resId != 0) {
            val parsed = parseResource(context, resId)
                ?: return Outcome.RulesResourceUnreadable(resId, "parse failed")
            for (section in sections) {
                val excludes = parsed[section].orEmpty()
                val missing = REQUIRED_EXCLUDES.filterNot { it in excludes }
                if (missing.isNotEmpty()) {
                    problems += MissingSection(resId, section, missing)
                }
            }
        }
        return null
    }

    private fun parseResource(context: Context, resId: Int): Map<Section, Set<RequiredExclude>>? {
        val parser: XmlResourceParser = try {
            context.resources.getXml(resId)
        } catch (_: Resources.NotFoundException) {
            return null
        }
        return try {
            parseRulesXml(parser)
        } catch (_: XmlPullParserException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: IllegalStateException) {
            null
        } finally {
            parser.close()
        }
    }

    /**
     * Walks a backup-rules XML document and returns the `<exclude>` entries grouped by
     * section. Accepts both legacy `<full-backup-content>` documents (single implicit
     * section) and modern `<data-extraction-rules>` documents (with `<cloud-backup>` and
     * `<device-transfer>` children). `<include>` entries and unknown elements are
     * ignored; only the `<exclude>` discipline is load-bearing for the trust claim.
     */
    @JvmStatic
    internal fun parseRulesXml(parser: XmlPullParser): Map<Section, Set<RequiredExclude>> {
        val state = ParseState()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> handleStartTag(parser, state)
                XmlPullParser.END_TAG -> handleEndTag(parser, state)
            }
            event = parser.next()
        }
        return state.sections
    }

    private fun handleStartTag(parser: XmlPullParser, state: ParseState) {
        val name = parser.name
        when {
            !state.rootSeen -> openRoot(name, state)
            !state.rootIsFullBackup && name == "cloud-backup" -> state.enter(Section.CloudBackup)
            !state.rootIsFullBackup && name == "device-transfer" -> state.enter(Section.DeviceTransfer)
            name == "exclude" -> recordExclude(parser, state)
        }
    }

    private fun handleEndTag(parser: XmlPullParser, state: ParseState) {
        if (state.rootIsFullBackup) return
        val name = parser.name
        if (name == "cloud-backup" || name == "device-transfer") {
            state.leave()
        }
    }

    private fun openRoot(name: String, state: ParseState) {
        state.rootSeen = true
        when (name) {
            "full-backup-content" -> {
                state.rootIsFullBackup = true
                state.enter(Section.FullBackupContent)
            }
            "data-extraction-rules" -> Unit
            else -> error("unexpected root element: $name")
        }
    }

    private fun recordExclude(parser: XmlPullParser, state: ParseState) {
        val sect = state.currentSection
        val domain = parser.getAttributeValue(null, "domain")
        val path = parser.getAttributeValue(null, "path")
        if (sect != null && domain != null && path != null) {
            state.add(sect, RequiredExclude(domain, path))
        }
    }

    private class ParseState {
        val sections: MutableMap<Section, MutableSet<RequiredExclude>> = mutableMapOf()
        var currentSection: Section? = null
        var rootIsFullBackup: Boolean = false
        var rootSeen: Boolean = false

        fun enter(section: Section) {
            currentSection = section
            sections.getOrPut(section) { mutableSetOf() }
        }

        fun leave() {
            currentSection = null
        }

        fun add(section: Section, exclude: RequiredExclude) {
            sections.getValue(section).add(exclude)
        }
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
