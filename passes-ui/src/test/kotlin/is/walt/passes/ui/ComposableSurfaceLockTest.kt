package `is`.walt.passes.ui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.lang.reflect.Method

/**
 * Pins the parameter-shape discipline of the trust-claim-bearing composables (ADR
 * 0003 D5). These shape constraints ARE the trust contract: a future refactor that
 * adds `enabled: Boolean = false` to `ExpiredOverlay`, or `skipConfirmation: Boolean = false`
 * to a security sheet, would silently weaken the claim documented in TRUST_CLAIMS.md.
 *
 * Goes through Java reflection because the Compose compiler plugin breaks the
 * Kotlin-reflection round-trip for top-level `@Composable` functions
 * (`KClass.staticFunctions` returns empty for these). We therefore enumerate
 * `Method.parameterTypes` and filter out the two synthetic trailing parameters
 * Compose appends (`Composer` and `int $changed`, plus optional `int $changed1`
 * for high-arity functions).
 *
 * The test locks the *user-visible parameter count*, not parameter names. That gives
 * a reviewer-friendly fail on adding any new parameter — the contract is "exactly
 * these N parameters, in this order" — without depending on parameter-name
 * preservation across Kotlin / JVM compilation modes.
 */
class ComposableSurfaceLockTest {

    @Test
    fun expiredOverlayHasExactlyThreeUserVisibleParameters() {
        // (state, locale, modifier) — D5: no `enabled`, no `suppress`.
        assertUserVisibleParamCount("ExpiredOverlayKt", "ExpiredOverlay", expected = 3)
    }

    @Test
    fun b3UrlConfirmSheetHasExactlySixUserVisibleParameters() {
        // (intent, passType, telemetry, onConfirm, onDismiss, emphasisStyle). The
        // sixth parameter is the wpass-48v opt-in layout switch (B3UrlEmphasisStyle:
        // Container | DomainHero); both layouts display the verbatim target string
        // and fire identical telemetry. D5 still forbids a `skipConfirmation` parameter.
        assertUserVisibleParamCount("SecuritySheetsKt", "B3UrlConfirmSheet", expected = 6)
    }

    @Test
    fun phoneConfirmSheetHasExactlySixUserVisibleParameters() {
        assertUserVisibleParamCount("SecuritySheetsKt", "PhoneConfirmSheet", expected = 6)
    }

    @Test
    fun emailConfirmSheetHasExactlySixUserVisibleParameters() {
        assertUserVisibleParamCount("SecuritySheetsKt", "EmailConfirmSheet", expected = 6)
    }

    @Test
    fun passFrontHasExactlyEightUserVisibleParameters() {
        // (pass, signatureStatus, telemetry, modifier, locale, nowEpochMillis,
        // showSignatureBadge, showExpiredOverlay). The last two are the wpass-hy2
        // R2 host opt-outs (wpass-btz, wpass-d0k) — non-breaking defaults that
        // preserve the original ADR 0003 D5 posture. Adding a ninth parameter
        // (e.g. `expiredOverlay: ExpiredOverlayState` that lets a host *display*
        // an arbitrary expiry state, or `signatureBand: SignatureBand` that lets
        // a host override the badge band) would breach D5; review the ADR before
        // changing this number.
        assertUserVisibleParamCount("PassFrontKt", "PassFront", expected = 8)
    }

    @Test
    fun passBackHasExactlySevenUserVisibleParameters() {
        // (pass, locale, onUrlIntent, onPhoneIntent, onEmailIntent, telemetry, modifier).
        // D5 also requires the three intent callbacks to be non-defaulted; that's
        // structurally enforced because they have no default expressions in source.
        assertUserVisibleParamCount("PassBackKt", "PassBack", expected = 7)
    }

    @Test
    fun boundedImageHasExactlySixUserVisibleParameters() {
        // (bytes, role, contentDescription, bounds, telemetry, modifier). D5: no
        // form of `bounds: ImageRenderBounds? = null` that would let a caller opt out.
        assertUserVisibleParamCount("BoundedImageKt", "BoundedImage", expected = 6)
    }

    @Test
    fun boundedImageBoundsParameterIsTheImageRenderBoundsType() {
        // Belt: locking the count guarantees no extra params; suspenders: confirm the
        // bounds parameter is in fact the strong type, not e.g. a nullable.
        val method = findComposable("BoundedImageKt", "BoundedImage")
        val typeNames = method.parameterTypes.map { it.simpleName }
        assertThat(typeNames).contains("ImageRenderBounds")
    }

    // Document-surface shape locks (DocumentTrustCaption / DocumentTile / DocumentView
    // / DocumentsLane and the no-overload + PdfRendererBinder-not-Client assertions)
    // moved to passes-pdf-ui::DocumentSurfaceLockTest with the composables (wpass-r4z).

    @Test
    fun securitySheetsAllAcceptUiTelemetryGuard() {
        listOf("B3UrlConfirmSheet", "PhoneConfirmSheet", "EmailConfirmSheet").forEach { name ->
            val method = findComposable("SecuritySheetsKt", name)
            val typeNames = method.parameterTypes.map { it.simpleName }
            assertWithMessage("$name must declare a UiTelemetryGuard parameter")
                .that(typeNames)
                .contains("UiTelemetryGuard")
        }
    }

    @Test
    fun barcodeCreateConfirmSheetHasExactlyFourUserVisibleParameters() {
        // (payloadKind, telemetry, onConfirm, onCancel). Adding any further parameter
        // (a "skipConfirmation" / per-arm silence flag, an override for the kind
        // dimension) would weaken the wpass-lzi.9 trust posture and is what this
        // lock exists to prevent.
        assertUserVisibleParamCount(
            "BarcodeCreateConfirmSheetKt",
            "BarcodeCreateConfirmSheet",
            expected = 4,
        )
    }

    @Test
    fun barcodeCreateConfirmSheetAcceptsUiTelemetryGuard() {
        val method = findComposable("BarcodeCreateConfirmSheetKt", "BarcodeCreateConfirmSheet")
        val typeNames = method.parameterTypes.map { it.simpleName }
        assertWithMessage("BarcodeCreateConfirmSheet must declare a UiTelemetryGuard parameter")
            .that(typeNames)
            .contains("UiTelemetryGuard")
    }

    @Test
    fun passImportConfirmHasExactlySevenUserVisibleParameters() {
        // (pass, signatureStatus, telemetry, onConfirm, onDismiss, modifier, locale).
        // No `enabled`, no `skipConfirmation`, no `showTrustCaption` — drift here is a
        // trust-claim regression (decision-wlt-0tn-q1 1a).
        assertUserVisibleParamCount("PassImportConfirmKt", "PassImportConfirm", expected = 7)
    }

    @Test
    fun passImportConfirmAcceptsUiTelemetryGuard() {
        val method = findComposable("PassImportConfirmKt", "PassImportConfirm")
        val typeNames = method.parameterTypes.map { it.simpleName }
        assertWithMessage("PassImportConfirm must declare a UiTelemetryGuard parameter")
            .that(typeNames)
            .contains("UiTelemetryGuard")
    }

    @Test
    fun passImportRejectionSheetHasExactlyThreeUserVisibleParameters() {
        // (kind, telemetry, onDismiss). The sheet is dismiss-only by design (no Save /
        // Open / Anyway button); a future addition of a fourth callback would be a
        // policy change to the lenient-with-disclosure signature stance.
        assertUserVisibleParamCount("PassImportRejectionKt", "PassImportRejectionSheet", expected = 3)
    }

    @Test
    fun scannableCardTrustCaptionHasExactlyOneUserVisibleParameter() {
        // (modifier) — C2 in SCANNABLE_CARD_THREAT_MODEL.md: no `enabled`, no theme
        // suppression flag, no overload that hides the caption. The "Created by you"
        // caption is structurally always-on, mirroring DocumentTrustCaption.
        assertUserVisibleParamCount(
            "ScannableCardTrustCaptionKt",
            "ScannableCardTrustCaption",
            expected = 1,
        )
    }

    @Test
    fun scannableCardTileHasExactlyThreeUserVisibleParameters() {
        // (card, onClick, modifier). No share/export, no overflow menu, no
        // showCaption flag — drift here is a trust-claim regression (SCANNABLE_CARD_
        // THREAT_MODEL.md C2: caption non-suppressibility).
        assertUserVisibleParamCount("ScannableCardTileKt", "ScannableCardTile", expected = 3)
    }

    @Test
    fun scannableCardScreenHasExactlyTwoUserVisibleParameters() {
        // (card, modifier). The trust caption is composed inside the surface; no
        // parameter omits it.
        assertUserVisibleParamCount(
            "ScannableCardScreenKt",
            "ScannableCardScreen",
            expected = 2,
        )
    }

    @Test
    fun scannableCardRowTileHasExactlyThreeUserVisibleParameters() {
        // (card, onClick, modifier). Wallet-row register sibling of ScannableCardTile.
        // The row deliberately drops the carousel tile's caption per the threat-model
        // concession in SCANNABLE_CARD_THREAT_MODEL.md C1 / C2; adding a 4th parameter
        // (e.g. `showSignatureBadge`, `leadingIcon`, `onLongPress`) either re-opens the
        // trust-conflation risk or expands the surface past what the concession permits.
        // Review the concession before changing this number.
        assertUserVisibleParamCount("ScannableCardRowTileKt", "ScannableCardRowTile", expected = 3)
    }

    @Test
    fun scannableCardSurfacesHaveNoOverloads() {
        // The caption non-suppressibility rule extends to overloads: a future
        // contributor cannot quietly add `ScannableCardTile(..., showCaption: Boolean)`
        // as a sibling with the same name. `ScannableCardRowTile` is included even
        // though it does not itself render the caption — the threat-model concession is
        // a specific row shape (label + neutral leading strip + format subtitle) and an
        // overload that adds richer chrome would dilute the shape this lock pins.
        listOf(
            "ScannableCardTrustCaptionKt" to "ScannableCardTrustCaption",
            "ScannableCardTileKt" to "ScannableCardTile",
            "ScannableCardScreenKt" to "ScannableCardScreen",
            "ScannableCardRowTileKt" to "ScannableCardRowTile",
        ).forEach { (file, name) ->
            val klass = Class.forName("is.walt.passes.ui.$file")
            val matches = klass.methods.filter { it.name == name || it.name.startsWith("$name-") }
            assertWithMessage("$name should have exactly one declared overload")
                .that(matches.size)
                .isEqualTo(1)
        }
    }

    @Test
    fun passImportRejectionSheetAcceptsParseFailureKindAndUiTelemetryGuard() {
        val method = findComposable("PassImportRejectionKt", "PassImportRejectionSheet")
        val typeNames = method.parameterTypes.map { it.simpleName }
        assertWithMessage("PassImportRejectionSheet must declare a ParseFailureKind parameter")
            .that(typeNames)
            .contains("ParseFailureKind")
        assertWithMessage("PassImportRejectionSheet must declare a UiTelemetryGuard parameter")
            .that(typeNames)
            .contains("UiTelemetryGuard")
    }

    // -- helpers -------------------------------------------------------------------

    private fun findComposable(fileClassSimpleName: String, methodName: String): Method {
        val klass = Class.forName("is.walt.passes.ui.$fileClassSimpleName")
        // Kotlin's value-class name mangling appends `-<hash>` to a JVM method name
        // when the function takes a value-class parameter. `passes-core` exposes
        // several value classes (`PassLocale`, `PassInstant`, `ImageBytes`,
        // `ColorValue`), so most of these composables compile to e.g.
        // `PassFront-I15CzMI`. Match either the bare name or the mangled prefix.
        return klass.methods
            .filter { it.name == methodName || it.name.startsWith("$methodName-") }
            .maxByOrNull { it.parameterCount }
            ?: error("Composable $fileClassSimpleName.$methodName not found")
    }

    private fun userVisibleParameterCount(method: Method): Int {
        // Compose appends `Composer $composer` and `int $changed` (and `int $changed1`
        // for high-arity functions). Strip them by their JVM type names.
        val params = method.parameterTypes
        var count = params.size
        // Walk backwards stripping Composer and int parameters that are clearly
        // Compose-synthetic. We stop at the first parameter that is neither Composer
        // nor a primitive int.
        var i = params.lastIndex
        while (i >= 0) {
            val t = params[i]
            val isComposer = t.name == "androidx.compose.runtime.Composer"
            val isInt = t.name == "int"
            if (!isComposer && !isInt) break
            count--
            i--
        }
        // The very-last user-visible parameter could legitimately be an `Int`
        // (e.g. PassFront accepts `nowEpochMillis: Long`, not Int — so this is safe
        // for our composables). If a future composable adds a trailing `Int` arg,
        // re-anchor the strip on the Composer parameter only.
        return count
    }

    private fun assertUserVisibleParamCount(
        fileClassSimpleName: String,
        methodName: String,
        expected: Int,
    ) {
        val method = findComposable(fileClassSimpleName, methodName)
        val actual = userVisibleParameterCount(method)
        assertWithMessage(
            "$methodName user-visible parameter count drifted; review ADR 0003 D5 " +
                "before changing this number. Full method signature: $method",
        )
            .that(actual)
            .isEqualTo(expected)
    }
}
