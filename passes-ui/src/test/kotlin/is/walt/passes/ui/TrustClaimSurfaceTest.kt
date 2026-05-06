package `is`.walt.passes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ColorValue
import `is`.walt.passes.core.ImageBytes
import `is`.walt.passes.core.ImageRole
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassColors
import `is`.walt.passes.core.PassField
import `is`.walt.passes.core.PassFields
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.ui.theme.ArgbColor
import `is`.walt.passes.ui.theme.CategoryAccentColors
import `is`.walt.passes.ui.theme.ExpiredBadgeStyle
import `is`.walt.passes.ui.theme.PassesSemantics
import `is`.walt.passes.ui.theme.PassesTheme
import `is`.walt.passes.ui.theme.SecuritySheetStyle
import `is`.walt.passes.ui.theme.SignatureBadgeColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric-backed Compose smoke tests for the trust-claim-bearing surfaces.
 *
 * Each test exercises a single surface and asserts the audit-relevant property,
 * not visual fidelity. Screenshot / pixel-level coverage is the implementation
 * bead's emulator-backed instrumentation work; here we lock the behavioral
 * contract: badge appears, overlay appears, security sheets show the verbatim
 * target, telemetry fires the documented events.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TrustClaimSurfaceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val telemetry = RecordingGuard()

    private val semantics = PassesSemantics(
        signatureBadge = SignatureBadgeColors(
            unsignedBackground = ArgbColor(0xFFFFE0E0.toInt()),
            unsignedForeground = ArgbColor(0xFF101010.toInt()),
            selfSignedBackground = ArgbColor(0xFFFFF0E0.toInt()),
            selfSignedForeground = ArgbColor(0xFF101010.toInt()),
            appleVerifiedBackground = ArgbColor(0xFFE0FFE0.toInt()),
            appleVerifiedForeground = ArgbColor(0xFF101010.toInt()),
            certChainIncompleteBackground = ArgbColor(0xFFFFFFE0.toInt()),
            certChainIncompleteForeground = ArgbColor(0xFF101010.toInt()),
        ),
        expiredBadge = ExpiredBadgeStyle(
            pillBackground = ArgbColor(0xFF202020.toInt()),
            pillForeground = ArgbColor(0xFFFFFFFF.toInt()),
            scrimAlpha = 96,
        ),
        securitySheet = SecuritySheetStyle(
            sheetBackground = ArgbColor(0xFFFFFFFF.toInt()),
            emphasisBackground = ArgbColor(0xFFEFEFEF.toInt()),
            emphasisForeground = ArgbColor(0xFF000000.toInt()),
            bodyForeground = ArgbColor(0xFF202020.toInt()),
            confirmContainer = ArgbColor(0xFF202020.toInt()),
            confirmForeground = ArgbColor(0xFFFFFFFF.toInt()),
            cancelForeground = ArgbColor(0xFF202020.toInt()),
        ),
        categoryAccent = CategoryAccentColors(
            boardingPass = ArgbColor(0xFF1D4ED8.toInt()),
            eventTicket = ArgbColor(0xFF7C2D92.toInt()),
            coupon = ArgbColor(0xFF555555.toInt()),
            storeCard = ArgbColor(0xFF555555.toInt()),
            generic = ArgbColor(0xFF555555.toInt()),
        ),
    )

    @Test
    fun trustBadgeIsVisibleAndBoundToSignatureStatus() {
        composeRule.setContent {
            ThemedHost {
                PassFront(
                    pass = passFixture(),
                    signatureStatus = SignatureStatus.AppleVerified,
                    nowEpochMillis = 0L,
                    telemetry = telemetry,
                )
            }
        }
        composeRule.onNodeWithText("Verified").assertIsDisplayed()
    }

    @Test
    fun trustBadgeUnsignedCopy() = trustBadgeCopy(SignatureStatus.Unsigned, "Unsigned")
    @Test
    fun trustBadgeSelfSignedCopy() = trustBadgeCopy(SignatureStatus.SelfSigned, "Self-signed")
    @Test
    fun trustBadgeIncompleteCopy() = trustBadgeCopy(SignatureStatus.CertChainIncomplete, "Signature unknown")

    private fun trustBadgeCopy(status: SignatureStatus, expected: String) {
        composeRule.setContent {
            ThemedHost {
                PassFront(
                    pass = passFixture(),
                    signatureStatus = status,
                    nowEpochMillis = 0L,
                    telemetry = telemetry,
                )
            }
        }
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun expiredOverlayShowsForExpiredPass() {
        composeRule.setContent {
            ThemedHost {
                ExpiredOverlay(state = ExpiredOverlayState.Expired(PassInstant(0L)))
            }
        }
        composeRule.onNodeWithText("Expired").assertIsDisplayed()
    }

    @Test
    fun voidedOverlayShowsForVoidedPass() {
        composeRule.setContent {
            ThemedHost {
                ExpiredOverlay(state = ExpiredOverlayState.Voided)
            }
        }
        composeRule.onNodeWithText("Voided").assertIsDisplayed()
    }

    @Test
    fun expiredOverlayRendersNothingForValidPass() {
        composeRule.setContent {
            ThemedHost {
                ExpiredOverlay(state = ExpiredOverlayState.None)
                Text("sentinel")
            }
        }
        composeRule.onNodeWithText("sentinel").assertIsDisplayed()
    }

    @Test
    fun securitySheetVerbatimUrlAndConfirmTelemetry() {
        var confirmed = 0
        var dismissed = 0
        val intent = B3UrlIntent(
            url = "https://example.com/help",
            sourceField = SourceField("support", "Support", "Acme"),
        )
        composeRule.setContent {
            ThemedHost {
                B3UrlConfirmSheet(
                    intent = intent,
                    passType = PassType.BoardingPass,
                    telemetry = telemetry,
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
        // Sheet shows the verbatim URL (substring match because the actual displayed
        // text is wrapped in FSI/PDI bidi isolates — see the dedicated isolation tests
        // below for that property) and the show-telemetry has fired.
        composeRule.onNodeWithText(
            "https://example.com/help",
            substring = true,
        ).assertIsDisplayed()
        composeRule.waitForIdle()

        assertThat(telemetry.events).contains("shown:Url:BoardingPass")
        assertThat(confirmed).isEqualTo(0)
        assertThat(dismissed).isEqualTo(0)
    }

    @Test
    fun securitySheetVerbatimPhone() {
        val intent = PhoneIntent(
            phoneNumber = "+1 (555) 123-4567",
            sourceField = SourceField("phone", "Phone", "Acme"),
        )
        composeRule.setContent {
            ThemedHost {
                PhoneConfirmSheet(
                    intent = intent,
                    passType = PassType.EventTicket,
                    telemetry = telemetry,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("+1 (555) 123-4567", substring = true).assertIsDisplayed()
        composeRule.waitForIdle()
        assertThat(telemetry.events).contains("shown:Phone:EventTicket")
    }

    @Test
    fun securitySheetVerbatimEmail() {
        val intent = EmailIntent(
            emailAddress = "support@example.com",
            sourceField = SourceField("email", "Email", "Acme"),
        )
        composeRule.setContent {
            ThemedHost {
                EmailConfirmSheet(
                    intent = intent,
                    passType = PassType.Coupon,
                    telemetry = telemetry,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("support@example.com", substring = true).assertIsDisplayed()
        composeRule.waitForIdle()
        assertThat(telemetry.events).contains("shown:Email:Coupon")
    }

    @Test
    fun securitySheetUrlIsBidiIsolated() {
        // Defense-in-depth: even after the scanner rejects bidi-bearing matches, the
        // sheet wraps the displayed URL in U+2068...U+2069 (FSI/PDI) so any residual
        // directional context from surrounding chrome cannot reorder the URL glyphs.
        val intent = B3UrlIntent(
            url = "https://example.com/help",
            sourceField = SourceField("support", "Support", "Acme"),
        )
        composeRule.setContent {
            ThemedHost {
                B3UrlConfirmSheet(
                    intent = intent,
                    passType = PassType.BoardingPass,
                    telemetry = telemetry,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        // The displayed text is FSI + url + PDI. Look up by the full isolated form.
        val isolatedUrl = "⁨https://example.com/help⁩"
        composeRule.onNodeWithText(isolatedUrl).assertIsDisplayed()
    }

    @Test
    fun securitySheetIsolatesOrganizationName() {
        // The organization name comes from the parsed pass and is rendered in the
        // sheet's body line. A bidi character in the org name must not reorder
        // surrounding chrome text. Test by verifying the org name displays inside
        // the FSI/PDI fence.
        val intent = B3UrlIntent(
            url = "https://example.com",
            sourceField = SourceField("support", "Support", "Acme Corp"),
        )
        composeRule.setContent {
            ThemedHost {
                B3UrlConfirmSheet(
                    intent = intent,
                    passType = PassType.BoardingPass,
                    telemetry = telemetry,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "⁨Acme Corp⁩ — ⁨Support⁩",
        ).assertIsDisplayed()
    }

    @Test
    fun boundedImageDecoderRejectsOversizedInput() {
        // Tests the decoder driver directly rather than going through the composable,
        // since Robolectric's coroutine harness does not progress `LaunchedEffect`
        // dispatched onto `Dispatchers.IO` to completion within `waitForIdle()`. The
        // composable is exercised on a real device by the implementation bead's
        // instrumentation tests; here we lock the JVM-pure decode-and-classify logic
        // that the composable wraps.
        //
        // Robolectric's ImageDecoder shadow returns a stub bitmap (typically 100 x 100
        // for arbitrary input rather than rejecting the malformed bytes), so we use
        // pathologically tight bounds (1 x 1 / 1 px area) to force the bounds-check
        // path. Any non-empty bitmap exceeds these caps; the rejection reason should
        // be one of the bounds arms, not Malformed or Other.
        val anyBytes = ByteArray(64) { (it * 31).toByte() }
        val tightBounds = ImageRenderBounds(maxWidthPx = 1, maxHeightPx = 1, maxAreaPx = 1L)
        val (_, rejection) = decodeBoundedBitmap(anyBytes, tightBounds)
        assertThat(rejection).isNotNull()
        assertThat(rejection!!.name).matches("ExceedsWidth|ExceedsHeight|ExceedsArea")
    }

    // Document-surface trust assertions (caption, tile bidi-isolation, lane wiring)
    // moved to passes-pdf-ui::DocumentTrustSurfaceTest with the composables (wpass-r4z).

    @Composable
    private fun ThemedHost(content: @Composable () -> Unit) {
        MaterialTheme {
            PassesTheme(semantics = semantics, content = content)
        }
    }

    private fun passFixture(
        type: PassType = PassType.Generic,
        expirationDate: PassInstant? = null,
        voided: Boolean = false,
    ): Pass = Pass(
        type = type,
        serialNumber = "0",
        description = "fixture",
        organizationName = "Acme",
        expirationDate = expirationDate,
        voided = voided,
        colors = PassColors(
            foreground = ColorValue(0x000000),
            background = ColorValue(0xFFFFFF),
            label = ColorValue(0x444444),
        ),
        frontFields = PassFields(
            primary = listOf(PassField(key = "p", label = "From", value = "JFK")),
        ),
        backFields = emptyList(),
        barcode = null,
        images = emptyMap(),
        locales = emptyMap(),
    )
}

private class RecordingGuard : UiTelemetryGuard {
    val events: MutableList<String> = mutableListOf()
    override fun onPassRendered(type: PassType, signatureBand: SignatureBand) {
        events += "rendered:${type.name}:${signatureBand.name}"
    }
    override fun onPassBackOpened(type: PassType) { events += "back:${type.name}" }
    override fun onSecuritySheetShown(intentKind: SecurityIntentKind, type: PassType) {
        events += "shown:${intentKind.name}:${type.name}"
    }
    override fun onSecuritySheetConfirmed(intentKind: SecurityIntentKind, type: PassType) {
        events += "confirm:${intentKind.name}:${type.name}"
    }
    override fun onSecuritySheetDismissed(intentKind: SecurityIntentKind, type: PassType) {
        events += "dismiss:${intentKind.name}:${type.name}"
    }
    override fun onImageDecodeRejected(reason: ImageDecodeRejection) {
        events += "decode:${reason.name}"
    }
}
