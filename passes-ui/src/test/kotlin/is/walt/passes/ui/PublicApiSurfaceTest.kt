package `is`.walt.passes.ui

import `is`.walt.passes.core.ParseFailureKind
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassColors
import `is`.walt.passes.core.PassFields
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.ui.theme.ArgbColor
import `is`.walt.passes.ui.theme.CategoryAccentColors
import `is`.walt.passes.ui.theme.ExpiredBadgeStyle
import `is`.walt.passes.ui.theme.PassesSemantics
import `is`.walt.passes.ui.theme.SecuritySheetStyle
import `is`.walt.passes.ui.theme.SignatureBadgeColors
import `is`.walt.passes.ui.theme.UnverifiedArtifactStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Locks the public API surface of `passes-ui`. Mirrors `passes-core` and
 * `passes-storage`: every sealed arm and every enum is reached via an exhaustive
 * `when` so adding or removing an arm forces a compile-time conversation.
 *
 * Composable behavior (Compose runtime, ZXing rendering, ImageDecoder bounds
 * enforcement) is exercised by the implementation bead's instrumentation tests;
 * this file stays JVM-only.
 */
class PublicApiSurfaceTest {

    @Test
    fun securityIntentArmsAreReachableViaWhen() {
        val source = SourceField(
            fieldKey = "support_url",
            fieldLabel = "Support",
            organizationName = "Acme",
        )
        val intents: List<SecurityIntent> = listOf(
            B3UrlIntent(url = "https://example.com", sourceField = source),
            PhoneIntent(phoneNumber = "+15551234567", sourceField = source),
            EmailIntent(emailAddress = "support@example.com", sourceField = source),
        )
        val labels = intents.map { intent ->
            when (intent) {
                is B3UrlIntent -> "url:${intent.url}"
                is PhoneIntent -> "phone:${intent.phoneNumber}"
                is EmailIntent -> "email:${intent.emailAddress}"
            }
        }
        assertThat(labels).containsExactly(
            "url:https://example.com",
            "phone:+15551234567",
            "email:support@example.com",
        ).inOrder()
    }

    @Test
    fun expiredOverlayStateArmsAreReachableViaWhen() {
        val states: List<ExpiredOverlayState> = listOf(
            ExpiredOverlayState.None,
            ExpiredOverlayState.Voided,
            ExpiredOverlayState.Expired(PassInstant(123L)),
        )
        val labels = states.map { state ->
            when (state) {
                ExpiredOverlayState.None -> "none"
                ExpiredOverlayState.Voided -> "voided"
                is ExpiredOverlayState.Expired -> "expired:${state.expiredAt.epochMillis}"
            }
        }
        assertThat(labels).containsExactly("none", "voided", "expired:123").inOrder()
    }

    @Test
    fun expiredOverlayFromPrefersVoidedOverDate() {
        val pass = passFixture(
            voided = true,
            expirationDate = PassInstant(0L),
        )
        assertThat(ExpiredOverlayState.from(pass, nowEpochMillis = 1_000L))
            .isEqualTo(ExpiredOverlayState.Voided)
    }

    @Test
    fun expiredOverlayFromTreatsEqualEpochAsExpired() {
        val pass = passFixture(expirationDate = PassInstant(1_000L))
        val state = ExpiredOverlayState.from(pass, nowEpochMillis = 1_000L)
        assertThat(state).isInstanceOf(ExpiredOverlayState.Expired::class.java)
    }

    @Test
    fun expiredOverlayFromYieldsNoneForFuturePass() {
        val pass = passFixture(expirationDate = PassInstant(2_000L))
        assertThat(ExpiredOverlayState.from(pass, nowEpochMillis = 1_000L))
            .isEqualTo(ExpiredOverlayState.None)
    }

    @Test
    fun expiredOverlayFromYieldsNoneWhenNoExpirationAndNotVoided() {
        val pass = passFixture(expirationDate = null, voided = false)
        assertThat(ExpiredOverlayState.from(pass, nowEpochMillis = 1_000L))
            .isEqualTo(ExpiredOverlayState.None)
    }

    @Test
    fun signatureBandCoversFourDocumentedBands() {
        assertThat(SignatureBand.entries.map { it.name }).containsExactly(
            "Untrusted",
            "SelfSigned",
            "AppleVerified",
            "Incomplete",
        ).inOrder()
    }

    @Test
    fun securityIntentKindCoversThreeFamilies() {
        assertThat(SecurityIntentKind.entries.map { it.name }).containsExactly(
            "Url",
            "Phone",
            "Email",
        ).inOrder()
    }

    @Test
    fun imageDecodeRejectionCoversFiveBuckets() {
        assertThat(ImageDecodeRejection.entries.map { it.name }).containsExactly(
            "ExceedsWidth",
            "ExceedsHeight",
            "ExceedsArea",
            "Malformed",
            "Other",
        ).inOrder()
    }

    @Test
    fun imageRenderBoundsRejectsNonPositiveDimensions() {
        try {
            ImageRenderBounds(maxWidthPx = 0, maxHeightPx = 100, maxAreaPx = 1_000L)
            error("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected.message).contains("maxWidthPx")
        }
    }

    @Test
    fun imageRenderBoundsDefaultIs1920SquareWith4Megapixels() {
        val defaults = ImageRenderBounds.Default
        assertThat(defaults.maxWidthPx).isEqualTo(1920)
        assertThat(defaults.maxHeightPx).isEqualTo(1920)
        assertThat(defaults.maxAreaPx).isEqualTo(4_000_000L)
    }

    @Test
    fun argbColorIsAValueClassWrappingAnInt() {
        val color = ArgbColor(0xFFEE2200.toInt())
        assertThat(color.argb).isEqualTo(0xFFEE2200.toInt())
    }

    /**
     * Pins the `UiTelemetryGuard` PII discipline. Every event method reachable here
     * with enums-and-primitives-only arguments. Adding a free-form `String`,
     * `ByteArray`, `Pass`, or `PassField` parameter to any method below would fail
     * to compile against this lock without a deliberate edit.
     */
    @Test
    fun uiTelemetryGuardEventsAreEnumsAndPrimitivesOnly() {
        val recorded = mutableListOf<String>()
        val guard = object : UiTelemetryGuard {
            override fun onPassRendered(type: PassType, signatureBand: SignatureBand) {
                recorded += "rendered:${type.name}:${signatureBand.name}"
            }

            override fun onPassBackOpened(type: PassType) {
                recorded += "back:${type.name}"
            }

            override fun onSecuritySheetShown(intentKind: SecurityIntentKind, type: PassType) {
                recorded += "shown:${intentKind.name}:${type.name}"
            }

            override fun onSecuritySheetConfirmed(intentKind: SecurityIntentKind, type: PassType) {
                recorded += "confirm:${intentKind.name}:${type.name}"
            }

            override fun onSecuritySheetDismissed(intentKind: SecurityIntentKind, type: PassType) {
                recorded += "dismiss:${intentKind.name}:${type.name}"
            }

            override fun onImageDecodeRejected(reason: ImageDecodeRejection) {
                recorded += "decode:${reason.name}"
            }

            override fun onImportConfirmShown(type: PassType, signatureBand: SignatureBand) {
                recorded += "import-shown:${type.name}:${signatureBand.name}"
            }

            override fun onImportConfirmed(type: PassType, signatureBand: SignatureBand) {
                recorded += "import-confirm:${type.name}:${signatureBand.name}"
            }

            override fun onImportDismissed(type: PassType, signatureBand: SignatureBand) {
                recorded += "import-dismiss:${type.name}:${signatureBand.name}"
            }

            override fun onImportRejected(kind: ParseFailureKind) {
                recorded += "import-rejected:${kind.name}"
            }
        }
        guard.onPassRendered(PassType.BoardingPass, SignatureBand.AppleVerified)
        guard.onPassBackOpened(PassType.EventTicket)
        guard.onSecuritySheetShown(SecurityIntentKind.Url, PassType.Generic)
        guard.onSecuritySheetConfirmed(SecurityIntentKind.Phone, PassType.StoreCard)
        guard.onSecuritySheetDismissed(SecurityIntentKind.Email, PassType.Coupon)
        guard.onImageDecodeRejected(ImageDecodeRejection.ExceedsArea)
        guard.onImportConfirmShown(PassType.Generic, SignatureBand.SelfSigned)
        guard.onImportConfirmed(PassType.Generic, SignatureBand.SelfSigned)
        guard.onImportDismissed(PassType.BoardingPass, SignatureBand.Untrusted)
        guard.onImportRejected(ParseFailureKind.Tampered)

        assertThat(recorded).containsExactly(
            "rendered:BoardingPass:AppleVerified",
            "back:EventTicket",
            "shown:Url:Generic",
            "confirm:Phone:StoreCard",
            "dismiss:Email:Coupon",
            "decode:ExceedsArea",
            "import-shown:Generic:SelfSigned",
            "import-confirm:Generic:SelfSigned",
            "import-dismiss:BoardingPass:Untrusted",
            "import-rejected:Tampered",
        ).inOrder()
    }

    @Test
    fun noopGuardImplementsTheFullSurface() {
        val guard: UiTelemetryGuard = NoopUiTelemetryGuard
        guard.onPassRendered(PassType.BoardingPass, SignatureBand.Untrusted)
        guard.onPassBackOpened(PassType.BoardingPass)
        guard.onSecuritySheetShown(SecurityIntentKind.Url, PassType.BoardingPass)
        guard.onSecuritySheetConfirmed(SecurityIntentKind.Url, PassType.BoardingPass)
        guard.onSecuritySheetDismissed(SecurityIntentKind.Url, PassType.BoardingPass)
        guard.onImageDecodeRejected(ImageDecodeRejection.Other)
        guard.onImportConfirmShown(PassType.BoardingPass, SignatureBand.AppleVerified)
        guard.onImportConfirmed(PassType.BoardingPass, SignatureBand.AppleVerified)
        guard.onImportDismissed(PassType.BoardingPass, SignatureBand.Incomplete)
        guard.onImportRejected(ParseFailureKind.Malformed)
    }

    @Test
    fun passesSemanticsDataClassExposesAllSlotFamilies() {
        val argb = ArgbColor(0xFF000000.toInt())
        val semantics = PassesSemantics(
            signatureBadge = SignatureBadgeColors(
                unsignedBackground = argb,
                unsignedForeground = argb,
                selfSignedBackground = argb,
                selfSignedForeground = argb,
                appleVerifiedBackground = argb,
                appleVerifiedForeground = argb,
                certChainIncompleteBackground = argb,
                certChainIncompleteForeground = argb,
            ),
            expiredBadge = ExpiredBadgeStyle(
                pillBackground = argb,
                pillForeground = argb,
                scrimAlpha = 96,
            ),
            securitySheet = SecuritySheetStyle(
                sheetBackground = argb,
                emphasisBackground = argb,
                emphasisForeground = argb,
                bodyForeground = argb,
                confirmContainer = argb,
                confirmForeground = argb,
                cancelForeground = argb,
            ),
            categoryAccent = CategoryAccentColors(
                boardingPass = argb,
                eventTicket = argb,
                coupon = argb,
                storeCard = argb,
                generic = argb,
            ),
            unverifiedArtifact = UnverifiedArtifactStyle(
                accent = argb,
                captionBackground = argb,
                captionForeground = argb,
            ),
        )
        // Reading every nested field forces them to remain in the public-API shape;
        // a rename or removal breaks the test. Document tokens (caption / tile / lane
        // / document badge) live on `passes-pdf-ui::DocumentSemantics` and are
        // covered by `DocumentPublicApiSurfaceTest` over there (wpass-r4z).
        assertThat(semantics.signatureBadge.appleVerifiedBackground).isEqualTo(argb)
        assertThat(semantics.expiredBadge.scrimAlpha).isEqualTo(96)
        assertThat(semantics.securitySheet.confirmContainer).isEqualTo(argb)
        assertThat(semantics.categoryAccent.boardingPass).isEqualTo(argb)
        assertThat(semantics.unverifiedArtifact.accent).isEqualTo(argb)
        // captionIconTint defaults to captionForeground when not explicitly supplied —
        // a consumer that does not opt into a separate accent gets a consistent
        // monochrome caption. Locking the default here keeps that contract testable.
        assertThat(semantics.unverifiedArtifact.captionIconTint)
            .isEqualTo(semantics.unverifiedArtifact.captionForeground)
    }

    @Test
    fun unverifiedArtifactStylePlaceholderIsAvailableForTestsAndPreviews() {
        val placeholder = UnverifiedArtifactStyle.Placeholder
        // The placeholder is a neutral grayscale set, NOT a brand value — its sole
        // purpose is to make `PassesSemantics()` default-constructible. Hosts must
        // override. The test merely asserts the constant exists and parses; concrete
        // values are not part of the contract.
        assertThat(placeholder.accent).isNotNull()
        assertThat(placeholder.captionBackground).isNotNull()
        assertThat(placeholder.captionForeground).isNotNull()
        assertThat(placeholder.captionIconTint).isEqualTo(placeholder.captionForeground)
    }

    /**
     * Bytecode scan that fails closed if any compiled `is.walt.passes.ui.*` class
     * carries a string constant that would let a contributor wire a Share / Export
     * action or a PDF-MIME callsite. ADR 0005 D8 (no share-out) and D4 (no PDF
     * extraction surface) are the policies; this test is the structural lock.
     *
     * The needles are scanned as raw UTF-8 byte sequences inside the .class files,
     * which surfaces both Kotlin string literals and Java reflection fragments. A
     * legitimate use (none today) would have to deliberately update the allow-list
     * in the test, making the security-policy edit auditable.
     */
    @Test
    fun passesUiCompiledClassesContainNoForbiddenStrings() {
        val classFiles = classFilesUnder("is/walt/passes/ui")
        assertThat(classFiles).isNotEmpty()

        val forbidden = listOf(
            "android.intent.action.SEND" to "Intent.ACTION_SEND",
            "android.intent.action.SEND_MULTIPLE" to "Intent.ACTION_SEND_MULTIPLE",
            "application/pdf" to "PDF MIME literal",
        )
        for (file in classFiles) {
            // The test class itself embeds the needle byte sequences in order to
            // search for them. Skip its own .class files (and any inner-class
            // companions Kotlin emits beside it) to avoid a self-trip.
            if (file.name.startsWith("PublicApiSurfaceTest")) continue
            val bytes = file.readBytes()
            for ((needle, label) in forbidden) {
                val needleBytes = needle.toByteArray(Charsets.UTF_8)
                val index = indexOf(bytes, needleBytes)
                if (index >= 0) {
                    error(
                        "Forbidden $label string '$needle' found at offset $index in " +
                            "${file.absolutePath}. ADR 0005 D8 forbids share-out; ADR " +
                            "0005 D4 forbids PDF MIME / metadata surfaces in passes-ui. " +
                            "If this addition is intentional, raise it as a security-" +
                            "policy change.",
                    )
                }
            }
        }
    }

    // The equivalent scan for passes-pdf-ui (the Document* surfaces and the
    // DocumentSemantics theme) lives in passes-pdf-ui::DocumentPublicApiSurfaceTest
    // (wpass-r4z). The renderer service module (passes-pdf) is no longer on this
    // test's classpath after the dep restructure; the scan-for-passes-pdf logic
    // would always early-out and provided no real coverage anyway since
    // passes-pdf's own PublicApiSurfaceTest already locks its surface.

    /**
     * Walk every classpath root that exposes [packagePath] and collect the .class
     * files. Using [ClassLoader.getResources] (plural) instead of getResource gets
     * us both the main-classes and test-classes roots in a Gradle test JVM, so the
     * scan covers production code rather than just the first match.
     */
    private fun classFilesUnder(packagePath: String): List<File> {
        val urls = javaClass.classLoader!!.getResources(packagePath).toList()
        return urls.flatMap { url ->
            val file = runCatching { File(url.toURI()) }.getOrNull() ?: return@flatMap emptyList()
            if (!file.isDirectory) return@flatMap emptyList()
            file.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()
        }
    }

    /**
     * Naive byte-substring search. The needles are short (≤ 30 bytes) and the haystacks
     * are class files in the low-tens-of-kilobytes; an exact-substring KMP would be
     * faster but invisible at this scale. Returning the first match's offset gives the
     * failure message a useful jump-to point.
     */
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val limit = haystack.size - needle.size
        var found = -1
        outer@ for (i in 0..limit) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            found = i
            break
        }
        return found
    }

    @Test
    fun scannableFormatArmsAreReachableViaWhen() {
        // ScannableCardView.minRenderSizeDp() carries a per-symbology min-size table.
        // Adding a new arm to ScannableFormat in passes-core would silently fall through
        // the existing 1D branch without this exhaustive when forcing a UI-side decision.
        val sizes = ScannableFormat.entries.map { format ->
            when (format) {
                ScannableFormat.Qr -> "qr"
                ScannableFormat.Code128 -> "code128"
                ScannableFormat.Ean13 -> "ean13"
                ScannableFormat.UpcA -> "upca"
                ScannableFormat.Code39 -> "code39"
            }
        }
        assertThat(sizes.toSet()).containsExactlyElementsIn(
            ScannableFormat.entries.map { it.name.lowercase() },
        )
    }

    private fun passFixture(
        expirationDate: PassInstant? = null,
        voided: Boolean = false,
    ): Pass = Pass(
        type = PassType.BoardingPass,
        serialNumber = "0",
        description = "fixture",
        organizationName = "Acme",
        expirationDate = expirationDate,
        voided = voided,
        colors = PassColors(foreground = null, background = null, label = null),
        frontFields = PassFields(),
        backFields = emptyList(),
        barcode = null,
        images = emptyMap(),
        locales = emptyMap(),
    )
}
