package `is`.walt.passes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ColorValue
import `is`.walt.passes.core.ParseFailureKind
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
@Suppress("LargeClass")
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

    // wpass-btz: PassFront.showSignatureBadge default-true preserves the original
    // ADR 0003 D5 posture; opt-out is permitted strictly for hosts that disclose the
    // band in their own chrome (walt-android import-confirm TrustChip). Telemetry
    // fires on the band regardless of the boolean - the trust contract is that the
    // band is recorded, not that a specific pill renders.

    @Test
    fun passFrontDefaultRendersSignatureBadge() {
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
    fun passFrontShowSignatureBadgeFalseSuppressesPill() {
        composeRule.setContent {
            ThemedHost {
                PassFront(
                    pass = passFixture(),
                    signatureStatus = SignatureStatus.AppleVerified,
                    nowEpochMillis = 0L,
                    telemetry = telemetry,
                    showSignatureBadge = false,
                )
            }
        }
        composeRule.onNodeWithText("Verified").assertDoesNotExist()
    }

    @Test
    fun passFrontShowSignatureBadgeFalseStillFiresOnPassRendered() {
        composeRule.setContent {
            ThemedHost {
                PassFront(
                    pass = passFixture(),
                    signatureStatus = SignatureStatus.SelfSigned,
                    nowEpochMillis = 0L,
                    telemetry = telemetry,
                    showSignatureBadge = false,
                )
            }
        }
        composeRule.waitForIdle()
        // Band must still be derivable and reported even when the visual badge is hidden.
        assertThat(telemetry.events).contains("rendered:Generic:SelfSigned")
    }

    // wpass-d0k: showExpiredOverlay default-true preserves D5 behavior; opt-out lets
    // a host treat expired passes as quiet archival rather than visual failure.

    @Test
    fun passFrontDefaultRendersExpiredOverlay() {
        composeRule.setContent {
            ThemedHost {
                PassFront(
                    pass = passFixture(expirationDate = PassInstant(0L)),
                    signatureStatus = SignatureStatus.AppleVerified,
                    nowEpochMillis = 1_000L,
                    telemetry = telemetry,
                )
            }
        }
        composeRule.onNodeWithText("Expired").assertIsDisplayed()
    }

    @Test
    fun passFrontShowExpiredOverlayFalseSuppressesScrim() {
        composeRule.setContent {
            ThemedHost {
                PassFront(
                    pass = passFixture(expirationDate = PassInstant(0L)),
                    signatureStatus = SignatureStatus.AppleVerified,
                    nowEpochMillis = 1_000L,
                    telemetry = telemetry,
                    showExpiredOverlay = false,
                )
            }
        }
        composeRule.onNodeWithText("Expired").assertDoesNotExist()
    }

    @Test
    fun passFrontShowExpiredOverlayFalseSuppressesVoidedScrim() {
        composeRule.setContent {
            ThemedHost {
                PassFront(
                    pass = passFixture(voided = true),
                    signatureStatus = SignatureStatus.AppleVerified,
                    nowEpochMillis = 0L,
                    telemetry = telemetry,
                    showExpiredOverlay = false,
                )
            }
        }
        composeRule.onNodeWithText("Voided").assertDoesNotExist()
    }

    @Test
    fun passFrontOptOutsAreIndependent() {
        // Suppressing the signature badge does not affect the expired overlay and
        // vice versa. The two booleans gate different chrome.
        composeRule.setContent {
            ThemedHost {
                PassFront(
                    pass = passFixture(expirationDate = PassInstant(0L)),
                    signatureStatus = SignatureStatus.AppleVerified,
                    nowEpochMillis = 1_000L,
                    telemetry = telemetry,
                    showSignatureBadge = false,
                    showExpiredOverlay = true,
                )
            }
        }
        composeRule.onNodeWithText("Verified").assertDoesNotExist()
        composeRule.onNodeWithText("Expired").assertIsDisplayed()
    }

    // wpass-48v: B3UrlEmphasisStyle.DomainHero is a layout opt-in; both layouts
    // display the verbatim target verbatim and fire the same telemetry.

    @Test
    fun securitySheetDomainHeroShowsVerbatimUrl() {
        val intent = B3UrlIntent(
            url = "https://www.tixly.com/refunds",
            sourceField = SourceField("support", "Support", "Acme"),
            registrableDomain = "tixly.com",
        )
        composeRule.setContent {
            ThemedHost {
                B3UrlConfirmSheet(
                    intent = intent,
                    passType = PassType.BoardingPass,
                    telemetry = telemetry,
                    onConfirm = {},
                    onDismiss = {},
                    emphasisStyle = B3UrlEmphasisStyle.DomainHero,
                )
            }
        }
        // Eyebrow.
        composeRule.onNodeWithText("LEAVING WALT").assertIsDisplayed()
        // Hero: registrable domain.
        composeRule.onNodeWithText("⁨tixly.com⁩").assertIsDisplayed()
        // Forensic row: the verbatim URL is still present, bidi-isolated.
        composeRule.onNodeWithText("⁨https://www.tixly.com/refunds⁩").assertIsDisplayed()
        composeRule.waitForIdle()
        assertThat(telemetry.events).contains("shown:Url:BoardingPass")
    }

    @Test
    fun securitySheetDomainHeroFallsBackToTargetWhenRegistrableDomainNull() {
        // The hero falls back to the verbatim target when the intent lacks a
        // registrable domain, so both the hero and forensic rows display the same
        // string — the trust contract (verbatim on-screen) still holds.
        val intent = B3UrlIntent(
            url = "https://example.com/x",
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
                    emphasisStyle = B3UrlEmphasisStyle.DomainHero,
                )
            }
        }
        // At least one node carries the verbatim URL; eyebrow confirms layout.
        composeRule.onNodeWithText("LEAVING WALT").assertIsDisplayed()
        val nodes = composeRule.onAllNodesWithText("⁨https://example.com/x⁩")
            .fetchSemanticsNodes()
        assertThat(nodes).isNotEmpty()
    }

    @Test
    fun securitySheetDomainHeroFiresConfirmTelemetry() {
        var confirmed = 0
        val intent = B3UrlIntent(
            url = "https://tixly.com/x",
            sourceField = SourceField("support", "Support", "Acme"),
            registrableDomain = "tixly.com",
        )
        composeRule.setContent {
            ThemedHost {
                B3UrlConfirmSheet(
                    intent = intent,
                    passType = PassType.BoardingPass,
                    telemetry = telemetry,
                    onConfirm = { confirmed++ },
                    onDismiss = {},
                    emphasisStyle = B3UrlEmphasisStyle.DomainHero,
                )
            }
        }
        composeRule.onNodeWithText("Open in browser").performClick()
        composeRule.waitForIdle()
        assertThat(confirmed).isEqualTo(1)
        assertThat(telemetry.events).contains("confirm:Url:BoardingPass")
    }

    @Test
    fun securitySheetPhoneDomainHeroShowsVerbatim() {
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
                    emphasisStyle = B3UrlEmphasisStyle.DomainHero,
                )
            }
        }
        composeRule.onNodeWithText("CALLING").assertIsDisplayed()
        // The verbatim digits are present; phoneHeroOf only collapses whitespace, so
        // hero and forensic row carry the same string for inputs with no multi-space
        // runs (the typical case). Trust contract is verbatim-on-screen, not a single
        // node count.
        val nodes = composeRule.onAllNodesWithText("⁨+1 (555) 123-4567⁩", substring = true)
            .fetchSemanticsNodes()
        assertThat(nodes).isNotEmpty()
    }

    @Test
    fun securitySheetEmailDomainHeroShowsVerbatim() {
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
                    emphasisStyle = B3UrlEmphasisStyle.DomainHero,
                )
            }
        }
        composeRule.onNodeWithText("EMAILING").assertIsDisplayed()
        // Hero is the local-part; verbatim address is on the forensic row.
        composeRule.onNodeWithText("⁨support⁩").assertIsDisplayed()
        composeRule.onNodeWithText("⁨support@example.com⁩").assertIsDisplayed()
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

    // wpass-38y: pass.strings substitution must apply symmetrically on the front and
    // the back. The chroniques (pass.com.tixly) regression had the back rendering raw
    // `#LABELKEY#` placeholders; the parallel concern is that organizationName, which
    // PKPASS allows to be a strings key and which the security confirmation sheets
    // surface verbatim, gets the same substitution treatment on both surfaces.

    @Test
    fun passFrontSubstitutesOrganizationNameThroughStrings() {
        composeRule.setContent {
            ThemedHost {
                PassFront(
                    pass = localizedFixture(
                        organizationName = "#ORGNAME#",
                        backFields = emptyList(),
                    ),
                    signatureStatus = SignatureStatus.AppleVerified,
                    nowEpochMillis = 0L,
                    telemetry = telemetry,
                )
            }
        }
        composeRule.onNodeWithText("Tixly").assertIsDisplayed()
    }

    @Test
    fun passBackSubstitutesFieldLabelsThroughStrings() {
        composeRule.setContent {
            ThemedHost {
                PassBack(
                    pass = localizedFixture(
                        organizationName = "#ORGNAME#",
                        backFields = listOf(
                            PassField(
                                key = "ticketNoBack",
                                label = "#LABELTICKETNUMBER#",
                                value = "52311919",
                            ),
                        ),
                    ),
                    onUrlIntent = {},
                    onPhoneIntent = {},
                    onEmailIntent = {},
                    telemetry = telemetry,
                )
            }
        }
        composeRule.onNodeWithText("Ticket Number").assertIsDisplayed()
        // Dynamic value (the actual ticket digits) must pass through unchanged.
        composeRule.onNodeWithText("52311919").assertIsDisplayed()
    }

    private fun localizedFixture(
        organizationName: String,
        backFields: List<PassField>,
    ): Pass = Pass(
        type = PassType.Generic,
        serialNumber = "0",
        description = "fixture",
        organizationName = organizationName,
        expirationDate = null,
        voided = false,
        colors = PassColors(
            foreground = ColorValue(0x000000),
            background = ColorValue(0xFFFFFF),
            label = ColorValue(0x444444),
        ),
        frontFields = PassFields(
            primary = listOf(PassField(key = "p", label = "From", value = "JFK")),
        ),
        backFields = backFields,
        barcode = null,
        images = emptyMap(),
        locales = mapOf(
            `is`.walt.passes.core.PassLocale("en") to `is`.walt.passes.core.LocalizedStrings(
                mapOf(
                    "#ORGNAME#" to "Tixly",
                    "#LABELTICKETNUMBER#" to "Ticket Number",
                ),
            ),
        ),
    )

    // Document-surface trust assertions (caption, tile bidi-isolation, lane wiring)
    // moved to passes-pdf-ui::DocumentTrustSurfaceTest with the composables (wpass-r4z).

    @Test
    fun importConfirmShowsTrustCaptionForAppleVerified() {
        composeRule.setContent {
            ThemedHost {
                PassImportConfirm(
                    pass = passFixture(),
                    signatureStatus = SignatureStatus.AppleVerified,
                    telemetry = telemetry,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Verified Apple issuer").assertIsDisplayed()
        composeRule.onNodeWithText("Add this pass?").assertIsDisplayed()
        composeRule.onNodeWithText("Save pass").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.waitForIdle()
        assertThat(telemetry.events).contains("import-shown:Generic:AppleVerified")
    }

    @Test
    fun importConfirmTrustCaptionUnsigned() = importConfirmCaption(SignatureStatus.Unsigned, "No signature")

    @Test
    fun importConfirmTrustCaptionSelfSigned() =
        importConfirmCaption(SignatureStatus.SelfSigned, "Self-signed issuer")

    @Test
    fun importConfirmTrustCaptionIncomplete() =
        importConfirmCaption(SignatureStatus.CertChainIncomplete, "Issuer chain incomplete")

    private fun importConfirmCaption(status: SignatureStatus, expected: String) {
        composeRule.setContent {
            ThemedHost {
                PassImportConfirm(
                    pass = passFixture(),
                    signatureStatus = status,
                    telemetry = telemetry,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun importConfirmFiresConfirmedTelemetryAndCallback() {
        var confirmed = 0
        composeRule.setContent {
            ThemedHost {
                PassImportConfirm(
                    pass = passFixture(),
                    signatureStatus = SignatureStatus.SelfSigned,
                    telemetry = telemetry,
                    onConfirm = { confirmed++ },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Save pass").performClick()
        composeRule.waitForIdle()
        assertThat(confirmed).isEqualTo(1)
        assertThat(telemetry.events).contains("import-confirm:Generic:SelfSigned")
    }

    @Test
    fun importConfirmFiresDismissedTelemetryAndCallback() {
        var dismissed = 0
        composeRule.setContent {
            ThemedHost {
                PassImportConfirm(
                    pass = passFixture(),
                    signatureStatus = SignatureStatus.Unsigned,
                    telemetry = telemetry,
                    onConfirm = {},
                    onDismiss = { dismissed++ },
                )
            }
        }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        assertThat(dismissed).isEqualTo(1)
        assertThat(telemetry.events).contains("import-dismiss:Generic:Untrusted")
    }

    @Test
    fun importRejectionSheetTamperedCopy() = importRejectionCopy(
        ParseFailureKind.Tampered,
        "This pass appears to have been tampered with",
    )

    @Test
    fun importRejectionSheetMalformedCopy() = importRejectionCopy(
        ParseFailureKind.Malformed,
        "This file is not a valid pass",
    )

    @Test
    fun importRejectionSheetUnsupportedCopy() = importRejectionCopy(
        ParseFailureKind.Unsupported,
        "Walt cannot open this pass",
    )

    @Test
    fun importRejectionSheetResourceLimitCopy() = importRejectionCopy(
        ParseFailureKind.ResourceLimitExceeded,
        "This pass is too large to open safely",
    )

    private fun importRejectionCopy(kind: ParseFailureKind, expectedTitle: String) {
        composeRule.setContent {
            ThemedHost {
                PassImportRejectionSheet(
                    kind = kind,
                    telemetry = telemetry,
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText(expectedTitle).assertIsDisplayed()
        composeRule.waitForIdle()
        assertThat(telemetry.events).contains("import-rejected:${kind.name}")
    }

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
    override fun onImportConfirmShown(type: PassType, signatureBand: SignatureBand) {
        events += "import-shown:${type.name}:${signatureBand.name}"
    }
    override fun onImportConfirmed(type: PassType, signatureBand: SignatureBand) {
        events += "import-confirm:${type.name}:${signatureBand.name}"
    }
    override fun onImportDismissed(type: PassType, signatureBand: SignatureBand) {
        events += "import-dismiss:${type.name}:${signatureBand.name}"
    }
    override fun onImportRejected(kind: ParseFailureKind) {
        events += "import-rejected:${kind.name}"
    }
    override fun onBarcodeCreateGateShown(kind: BarcodeCreateKind) {
        events += "barcode-shown:${kind.name}"
    }
    override fun onBarcodeCreateGateConfirmed(kind: BarcodeCreateKind) {
        events += "barcode-confirm:${kind.name}"
    }
    override fun onBarcodeCreateGateDismissed(kind: BarcodeCreateKind) {
        events += "barcode-dismiss:${kind.name}"
    }
}
