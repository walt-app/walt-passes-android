package `is`.walt.passes.storage

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import `is`.walt.passes.storage.BackupRulesContract.REQUIRED_EXCLUDES
import `is`.walt.passes.storage.BackupRulesContract.RequiredExclude
import `is`.walt.passes.storage.BackupRulesContract.Section
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException

private const val MANIFEST_FILE_NAME = "AndroidManifest.xml"
private const val APPLICATION_ELEMENT = "application"
private const val MANIFEST_ELEMENT = "manifest"
private const val PACKAGE_ATTRIBUTE = "package"

// AssetManager assigns a cookie per loaded ApkAssets. The running app's main APK
// usually lands at cookie 1 or 2 (framework loads first); split APKs and dynamically
// loaded resource providers may push it higher. 32 is a generous upper bound that
// covers every shipping Android configuration without making the search expensive
// (each iteration is a JNI call that returns immediately for unknown cookies).
private const val MAX_MANIFEST_COOKIE_PROBE = 32

/**
 * Manifest-XML fallback path. The running [AssetManager] already has the app's own
 * `AndroidManifest.xml` loaded; the only unknown is which cookie it sits behind (varies
 * by Android version and presence of split APKs / resource loaders). Iterate cookies
 * 1..[MAX_MANIFEST_COOKIE_PROBE] and pick the first parser whose
 * `<manifest package="...">` matches [packageName] — that's the merged manifest the
 * trust claim cares about. Reads `android:fullBackupContent` /
 * `android:dataExtractionRules` off the `<application>` element using only public
 * AssetManager API ([AssetManager.openXmlResourceParser], since API 1).
 *
 * Returns null when:
 *  - No cookie in the probed range yields a parser whose package matches.
 *  - Loading or parsing the manifest throws IO / XML errors.
 *  - The merged manifest has no `<application>` tag (defensive; should not happen).
 */
private fun readManifestResourceIds(
    assets: AssetManager,
    packageName: String,
): BackupRulesAssertion.ResourceIds? {
    val parser = openOwnManifestParser(assets, packageName) ?: return null
    return try {
        parseApplicationAttributes(parser)
    } catch (_: XmlPullParserException) {
        null
    } catch (_: IOException) {
        null
    } finally {
        parser.close()
    }
}

private fun openOwnManifestParser(
    assets: AssetManager,
    packageName: String,
): XmlResourceParser? {
    for (cookie in 1..MAX_MANIFEST_COOKIE_PROBE) {
        val candidate = openManifestAt(assets, cookie) ?: continue
        if (advanceToOwnManifestStartTag(candidate, packageName)) {
            return candidate
        }
        candidate.close()
    }
    return null
}

private fun openManifestAt(assets: AssetManager, cookie: Int): XmlResourceParser? = try {
    assets.openXmlResourceParser(cookie, MANIFEST_FILE_NAME)
} catch (_: IOException) {
    null
} catch (_: RuntimeException) {
    // Native side raises IllegalArgumentException / IndexOutOfBoundsException for
    // cookies that don't map to a loaded ApkAssets. Treat as "not this one" and
    // keep probing.
    null
}

/**
 * Advances [parser] to the `<manifest>` start tag and returns true iff its `package`
 * attribute matches [expectedPackage]. Leaves the parser positioned at that start tag
 * so the caller can keep walking into `<application>`. A single return path keeps the
 * detekt ReturnCount budget small without sacrificing the IO/XML error short-circuit.
 */
private fun advanceToOwnManifestStartTag(
    parser: XmlResourceParser,
    expectedPackage: String,
): Boolean = try {
    var matched = false
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.name == MANIFEST_ELEMENT) {
            matched = parser.getAttributeValue(null, PACKAGE_ATTRIBUTE) == expectedPackage
            break
        }
        event = parser.next()
    }
    matched
} catch (_: XmlPullParserException) {
    false
} catch (_: IOException) {
    false
}

/**
 * Walks the manifest's binary AXML to the first `<application>` start tag and reads
 * the resource ids of the two attributes the trust claim cares about. Returns null if
 * the document has no `<application>` element at all (a malformed manifest).
 */
private fun parseApplicationAttributes(
    parser: XmlResourceParser,
): BackupRulesAssertion.ResourceIds? {
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.name == APPLICATION_ELEMENT) {
            return readApplicationAttributes(parser)
        }
        event = parser.next()
    }
    return null
}

private fun readApplicationAttributes(parser: XmlResourceParser): BackupRulesAssertion.ResourceIds {
    var fullBackup = 0
    for (i in 0 until parser.attributeCount) {
        if (parser.getAttributeNameResource(i) == android.R.attr.fullBackupContent) {
            fullBackup = parser.getAttributeResourceValue(i, 0)
            break
        }
    }
    val dxr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        readDataExtractionRulesAttribute(parser)
    } else {
        0
    }
    return BackupRulesAssertion.ResourceIds(fullBackup = fullBackup, dxr = dxr)
}

@RequiresApi(Build.VERSION_CODES.S)
private fun readDataExtractionRulesAttribute(parser: XmlResourceParser): Int {
    for (i in 0 until parser.attributeCount) {
        if (parser.getAttributeNameResource(i) == android.R.attr.dataExtractionRules) {
            return parser.getAttributeResourceValue(i, 0)
        }
    }
    return 0
}

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
 * The resource-id read is two-tier:
 *
 *  1. **Reflection on `ApplicationInfo.fullBackupContent` and
 *     `ApplicationInfo.dataExtractionRulesRes`** — the most direct read of the merged
 *     manifest, but those fields are greylisted hidden-API. On platforms where either
 *     field has been moved to the blocklist (observed on API 31+ for our target SDK,
 *     where `dataExtractionRulesRes` collapses to `NoSuchFieldException`), reflection
 *     returns no value.
 *  2. **Manifest-XML fallback** — when reflection fails, the assertion opens the running
 *     app's already-loaded `AndroidManifest.xml` through the running `AssetManager`
 *     (`AssetManager.openXmlResourceParser`, public since API 1) and reads
 *     `android:fullBackupContent` / `android:dataExtractionRules` off the `<application>`
 *     element. Slower than reflection, but uses only stable public API.
 *
 * [Outcome.FieldUnavailable] is reserved for the case where both tiers fail (reflection
 * blocked AND the manifest read errors out, e.g. AXML malformed or no cookie in the
 * probed range matches the running package). It is the triage surface for genuinely
 * incompatible OS-side changes.
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
     * `ApplicationInfo` returned by `Context.applicationInfo`. The two reader parameters
     * let JVM tests drive each tier of the read order independently — useful because
     * Robolectric cannot easily simulate hidden-API blocklist failures on
     * `ApplicationInfo` reflection.
     */
    @VisibleForTesting
    internal fun assertBackupRulesApplied(
        appInfo: ApplicationInfo,
        resources: Resources,
        reflectionReader: (ApplicationInfo) -> ResourceIds? = ::readResourceIdsViaReflection,
        manifestFallbackReader: (ApplicationInfo, Resources) -> ResourceIds? =
            ::readResourceIdsFromManifestXml,
    ): Outcome =
        if (appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0) {
            Outcome.Applied
        } else {
            evaluateRules(appInfo, resources, reflectionReader, manifestFallbackReader)
        }

    private fun evaluateRules(
        appInfo: ApplicationInfo,
        resources: Resources,
        reflectionReader: (ApplicationInfo) -> ResourceIds?,
        manifestFallbackReader: (ApplicationInfo, Resources) -> ResourceIds?,
    ): Outcome {
        val resIds = reflectionReader(appInfo) ?: manifestFallbackReader(appInfo, resources)
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

    @VisibleForTesting
    internal data class ResourceIds(val fullBackup: Int, val dxr: Int)

    private fun readResourceIdsViaReflection(appInfo: ApplicationInfo): ResourceIds? {
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

    /**
     * Manifest-XML fallback path. The running `AssetManager` already has the app's own
     * `AndroidManifest.xml` loaded; the only unknown is which cookie it sits behind
     * (varies by Android version and presence of split APKs / resource loaders).
     * Iterate cookies 1..N and pick the first parser whose `<manifest package="...">`
     * matches the running package — that's the merged manifest the trust claim cares
     * about. Reads `android:fullBackupContent` / `android:dataExtractionRules` off the
     * `<application>` element using only public AssetManager API (`openXmlResourceParser`,
     * since API 1).
     *
     * Returns null when:
     *  - No cookie in the probed range yields a parser whose package matches.
     *  - Loading or parsing the manifest throws IO / XML errors.
     *  - The merged manifest has no `<application>` tag (defensive; should not happen).
     */
    private fun readResourceIdsFromManifestXml(
        appInfo: ApplicationInfo,
        resources: Resources,
    ): ResourceIds? = readManifestResourceIds(resources.assets, appInfo.packageName)

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
