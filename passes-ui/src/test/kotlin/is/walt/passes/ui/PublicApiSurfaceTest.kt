package `is`.walt.passes.ui

import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassColors
import `is`.walt.passes.core.PassFields
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.PassType
import `is`.walt.passes.ui.theme.ArgbColor
import `is`.walt.passes.ui.theme.CategoryAccentColors
import `is`.walt.passes.ui.theme.ExpiredBadgeStyle
import `is`.walt.passes.ui.theme.PassesSemantics
import `is`.walt.passes.ui.theme.SecuritySheetStyle
import `is`.walt.passes.ui.theme.SignatureBadgeColors
import com.google.common.truth.Truth.assertThat
import org.junit.Test

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
        }
        guard.onPassRendered(PassType.BoardingPass, SignatureBand.AppleVerified)
        guard.onPassBackOpened(PassType.EventTicket)
        guard.onSecuritySheetShown(SecurityIntentKind.Url, PassType.Generic)
        guard.onSecuritySheetConfirmed(SecurityIntentKind.Phone, PassType.StoreCard)
        guard.onSecuritySheetDismissed(SecurityIntentKind.Email, PassType.Coupon)
        guard.onImageDecodeRejected(ImageDecodeRejection.ExceedsArea)

        assertThat(recorded).containsExactly(
            "rendered:BoardingPass:AppleVerified",
            "back:EventTicket",
            "shown:Url:Generic",
            "confirm:Phone:StoreCard",
            "dismiss:Email:Coupon",
            "decode:ExceedsArea",
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
    }

    @Test
    fun passesSemanticsDataClassExposesAllFourSlotFamilies() {
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
        )
        // Reading every nested field forces them to remain in the public-API shape;
        // a rename or removal breaks the test.
        assertThat(semantics.signatureBadge.appleVerifiedBackground).isEqualTo(argb)
        assertThat(semantics.expiredBadge.scrimAlpha).isEqualTo(96)
        assertThat(semantics.securitySheet.confirmContainer).isEqualTo(argb)
        assertThat(semantics.categoryAccent.boardingPass).isEqualTo(argb)
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
