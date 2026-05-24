package `is`.walt.passes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.core.ScannableCardCreateInput
import `is`.walt.passes.core.ScannableCardCreateResult
import `is`.walt.passes.core.ScannableCardId
import `is`.walt.passes.core.ScannableCardInputValidator
import `is`.walt.passes.core.ScannableFormat
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import `is`.walt.passes.ui.theme.ArgbColor
import `is`.walt.passes.ui.theme.CategoryAccentColors
import `is`.walt.passes.ui.theme.ExpiredBadgeStyle
import `is`.walt.passes.ui.theme.PassesSemantics
import `is`.walt.passes.ui.theme.PassesTheme
import `is`.walt.passes.ui.theme.SecuritySheetStyle
import `is`.walt.passes.ui.theme.SignatureBadgeColors
import `is`.walt.passes.ui.theme.UnverifiedArtifactStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric-backed Compose smoke tests for the `ScannableCard` trust surfaces. The
 * behavioral contract under test maps 1:1 to the threat-model controls in
 * `docs/SCANNABLE_CARD_THREAT_MODEL.md`:
 *
 *  - **C2 (non-suppressible "Created by you" caption)**: every tile renders the verbatim
 *    caption regardless of tile-modifier-scoped size; the full-screen surface renders it
 *    docked at the bottom; theming the placeholder semantics does not remove it.
 *  - **C2 (≥2 visual distinguishers)**: indirectly asserted via the surface lock tests in
 *    `ComposableSurfaceLockTest` (param shape locks out a hide-caption flag) and the no-
 *    overload assertion. Pixel-level coverage of the dashed border + leading band + icon
 *    triple is the implementation bead's emulator-backed instrumentation work.
 *
 * Caption text is asserted by literal because the wording is the load-bearing trust
 * claim; renaming it requires updating this test, which forces a security-policy
 * conversation rather than a silent UX edit.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScannableCardTrustSurfaceTest {

    @get:Rule
    val composeRule = createComposeRule()

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
        unverifiedArtifact = UnverifiedArtifactStyle(
            accent = ArgbColor(0xFF8A4A2E.toInt()),
            captionBackground = ArgbColor(0xFFFFF0E0.toInt()),
            captionForeground = ArgbColor(0xFF301010.toInt()),
        ),
    )

    @Test
    fun trustCaptionTextIsLiteralCreatedByYou() {
        composeRule.setContent {
            ThemedHost { ScannableCardTrustCaption() }
        }
        composeRule.onNodeWithText("Created by you").assertIsDisplayed()
    }

    @Test
    fun tileRendersTrustCaptionByLiteralWording() {
        composeRule.setContent {
            ThemedHost { ScannableCardTile(card = qrFixture(), onClick = {}) }
        }
        composeRule.onNodeWithText("Created by you").assertIsDisplayed()
    }

    @Test
    fun tileRendersTrustCaptionAtConstrainedSmallSize() {
        // Constrain the tile modifier to a deliberately tight box so the caption layout
        // has to survive a smaller-than-default slot. Acceptance criterion: caption must
        // remain visible at every tile size (no truncation, no clipping out of view).
        composeRule.setContent {
            ThemedHost {
                Box(modifier = Modifier.size(160.dp, 220.dp)) {
                    ScannableCardTile(card = qrFixture(), onClick = {})
                }
            }
        }
        composeRule.onNodeWithText("Created by you").assertIsDisplayed()
    }

    @Test
    fun tileRendersTrustCaptionForOneDimensionalBarcodeFormat() {
        // The previewSize path for 1D barcodes is a different code branch from QR; both
        // must compose with the caption visible.
        composeRule.setContent {
            ThemedHost { ScannableCardTile(card = code128Fixture(), onClick = {}) }
        }
        composeRule.onNodeWithText("Created by you").assertIsDisplayed()
    }

    @Test
    fun tilePropagatesClickToOnClickCallback() {
        var clicks = 0
        composeRule.setContent {
            ThemedHost {
                ScannableCardTile(card = qrFixture(), onClick = { clicks++ })
            }
        }
        composeRule.onNodeWithText("Created by you").performClick()
        composeRule.waitForIdle()
        // Caption is part of the tile's clickable surface; tapping anywhere navigates.
        assert(clicks == 1) { "expected one click propagation, got $clicks" }
    }

    @Test
    fun fullScreenRendersTrustCaptionDockedAtBottom() {
        composeRule.setContent {
            ThemedHost { ScannableCardScreen(card = qrFixture()) }
        }
        composeRule.onNodeWithText("Created by you").assertIsDisplayed()
    }

    @Test
    fun fullScreenRendersUserSuppliedLabel() {
        composeRule.setContent {
            ThemedHost {
                ScannableCardScreen(card = qrFixture(label = "Library card"))
            }
        }
        // The label is wrapped in FSI/PDI, so look up by the isolated form. Mirrors how
        // the security-sheet tests assert isolated-form display of user-supplied strings.
        composeRule.onNodeWithText("⁨Library card⁩").assertIsDisplayed()
    }

    @Test
    fun placeholderUnverifiedArtifactStyleStillRendersCaption() {
        // PassesSemantics ships UnverifiedArtifactStyle.Placeholder so the surfaces
        // compose under a default-constructed semantics (tests / previews). Lock that
        // a host who forgets to override still gets the trust caption — degraded
        // styling is acceptable, missing caption is not.
        val defaulted = semantics.copy(
            unverifiedArtifact = UnverifiedArtifactStyle.Placeholder,
        )
        composeRule.setContent {
            PassesTheme(semantics = defaulted) {
                MaterialTheme { ScannableCardTile(card = qrFixture(), onClick = {}) }
            }
        }
        composeRule.onNodeWithText("Created by you").assertIsDisplayed()
    }

    @Test
    fun rowTileDoesNotRenderTrustCaption() {
        // The wallet-row register intentionally drops the carousel tile's "Created by
        // you" caption per the SCANNABLE_CARD_THREAT_MODEL.md C1 / C2 concession. The
        // detail surface (asserted separately by fullScreenRendersTrustCaptionDockedAtBottom)
        // is where the trust caption surfaces for this register; missing it on the row
        // is the load-bearing difference between the two siblings.
        composeRule.setContent {
            ThemedHost { ScannableCardRowTile(card = qrFixture(), onClick = {}) }
        }
        composeRule.onNodeWithText("Created by you").assertDoesNotExist()
    }

    @Test
    fun rowTileRendersIsolatedLabel() {
        composeRule.setContent {
            ThemedHost {
                ScannableCardRowTile(card = qrFixture(label = "Library card"), onClick = {})
            }
        }
        // FSI / PDI defense-in-depth on user-controlled label, same as ScannableCardTile.
        composeRule.onNodeWithText("⁨Library card⁩").assertIsDisplayed()
    }

    @Test
    fun rowTileRendersFormatSubtitle() {
        composeRule.setContent {
            ThemedHost { ScannableCardRowTile(card = code128Fixture(), onClick = {}) }
        }
        composeRule.onNodeWithText("Code 128").assertIsDisplayed()
    }

    @Test
    fun rowTilePropagatesClickToOnClickCallback() {
        var clicks = 0
        composeRule.setContent {
            ThemedHost {
                ScannableCardRowTile(card = qrFixture(), onClick = { clicks++ })
            }
        }
        composeRule.onNodeWithText("⁨Membership⁩").performClick()
        composeRule.waitForIdle()
        assert(clicks == 1) { "expected one click propagation, got $clicks" }
    }

    @Test
    fun rowTileSemanticsExposeFormatToken() {
        // The merged-descendants node sets contentDescription explicitly, which replaces
        // (not appends to) descendant Text contributions for accessibility services. The
        // format-as-subtitle is one of the two signals compensating for the dropped
        // carousel-tile chrome under the C1 / C2 wallet-row concession; a TalkBack user
        // who only hears "{label}, barcode card" loses half of that. The contentDescription
        // must inline the format token so the AT-level distinction matches the visual one.
        composeRule.setContent {
            ThemedHost {
                ScannableCardRowTile(card = qrFixture(label = "Library card"), onClick = {})
            }
        }
        composeRule
            .onNodeWithContentDescription("⁨Library card⁩, QR, barcode card")
            .assertExists()
    }

    @Test
    fun rowTileSemanticsExposeFormatTokenForOneDimensionalFormat() {
        // The QR path and the 1D path render different subtitle strings; both must reach
        // the merged contentDescription so neither barcode family is silently AT-blind.
        composeRule.setContent {
            ThemedHost { ScannableCardRowTile(card = code128Fixture(), onClick = {}) }
        }
        composeRule
            .onNodeWithContentDescription("⁨Gym⁩, Code 128, barcode card")
            .assertExists()
    }

    @Test
    fun rowTileRendersLeadingSlot() {
        composeRule.setContent {
            ThemedHost {
                ScannableCardRowTile(
                    card = qrFixture(),
                    onClick = {},
                    leadingSlot = {
                        Text(text = "GLYPH")
                    },
                )
            }
        }
        composeRule.onNodeWithText("GLYPH").assertIsDisplayed()
    }

    @Test
    fun fullScreenRendersPayloadCaptionVerbatim() {
        // GH #102: the encoded payload is displayed below the barcode as a
        // human-readable fallback for a failed POS scanner. The caption is FSI/PDI
        // isolated as defense-in-depth on the create-boundary Cf/Cc rejection (C3
        // in SCANNABLE_CARD_THREAT_MODEL.md); assert the isolated form so a
        // regression that dropped the bidi fence would fail this test.
        composeRule.setContent {
            ThemedHost {
                ScannableCardScreen(card = qrFixture(label = "Library card"))
            }
        }
        composeRule.onNodeWithText("⁨WALT-MEMBER-12345⁩").assertIsDisplayed()
    }

    @Test
    fun fullScreenRendersPayloadCaptionForOneDimensionalFormat() {
        // Both encoder paths (QR and 1D) must reach the caption — the POS-scanner
        // fallback case is more common for 1D loyalty cards than for QR.
        composeRule.setContent {
            ThemedHost { ScannableCardScreen(card = code128Fixture()) }
        }
        composeRule.onNodeWithText("⁨ABCDE12345⁩").assertIsDisplayed()
    }

    @Test
    fun tileDoesNotRenderPayloadCaption() {
        // The carousel tile is identification-only; rendering the payload below
        // its small preview would crowd the chrome and is not the surface the
        // GH #102 fallback targets. Default-off behaviour pinned here so the
        // caption stays scoped to the detail surface.
        composeRule.setContent {
            ThemedHost { ScannableCardTile(card = qrFixture(), onClick = {}) }
        }
        composeRule.onNodeWithText("⁨WALT-MEMBER-12345⁩").assertDoesNotExist()
    }

    @Test
    fun fullScreenRendersPayloadCaptionEvenWhenEncoderFails() {
        // GH #102's whole point is that the user can fall back to reading the
        // number aloud when the *scanner* fails. The kernel-internal mirror of
        // that is: when the *encoder* fails (the validator-bypassed defensive
        // path inside ScannableCardView), the caption must still render — a user
        // with an unrenderable barcode can still read their payload. An 11-digit
        // EAN-13 trips ZxingBarcodeEncoder (see BarcodeEncoderTest's
        // ean13RejectsWrongLengthAtWriter) but is rejected upstream by the
        // validator; construct the card via the internal constructor so the
        // encoder-failure UI path is exercised end-to-end.
        composeRule.setContent {
            ThemedHost { ScannableCardScreen(card = encoderRejectedEan13Fixture()) }
        }
        composeRule.onNodeWithText("⁨12345678901⁩").assertIsDisplayed()
    }

    @Test
    fun rowTileDoesNotRenderPayloadCaption() {
        // The wallet-row register is the smallest surface of the three; same
        // default-off rationale as the carousel tile.
        composeRule.setContent {
            ThemedHost { ScannableCardRowTile(card = qrFixture(), onClick = {}) }
        }
        composeRule.onNodeWithText("⁨WALT-MEMBER-12345⁩").assertDoesNotExist()
    }

    @Test
    fun rowTileLeadingSlotWithoutContentDescriptionDoesNotPolluteMergedSemantics() {
        // The slot lives inside the row's mergeDescendants block. The kernel-built
        // contentDescription on the merged node replaces (not appends to) descendant
        // contributions, so a slot composable whose icons set contentDescription = null
        // must leave the merged description exactly equal to the kernel-built string.
        // Pinning this protects the wpass-2a2 surface claim that `leadingSlot` is a
        // visual hook, not a trust signal.
        composeRule.setContent {
            ThemedHost {
                ScannableCardRowTile(
                    card = qrFixture(label = "Library card"),
                    onClick = {},
                    leadingSlot = {
                        Box(modifier = Modifier.size(24.dp))
                    },
                )
            }
        }
        composeRule
            .onNodeWithContentDescription("⁨Library card⁩, QR, barcode card")
            .assertExists()
    }

    @Composable
    private fun ThemedHost(content: @Composable () -> Unit) {
        MaterialTheme { PassesTheme(semantics = semantics, content = content) }
    }

    private fun qrFixture(label: String = "Membership"): ScannableCard = card(
        format = ScannableFormat.Qr,
        payload = "WALT-MEMBER-12345",
        label = label,
    )

    private fun code128Fixture(): ScannableCard = card(
        format = ScannableFormat.Code128,
        payload = "ABCDE12345",
        label = "Gym",
    )

    private fun card(
        format: ScannableFormat,
        payload: String,
        label: String,
    ): ScannableCard {
        val result = ScannableCardInputValidator.validate(
            input = ScannableCardCreateInput(
                payload = payload,
                format = format,
                label = label,
            ),
            id = ScannableCardId("test"),
            createdAt = PassInstant(0L),
        )
        return (result as ScannableCardCreateResult.Success).card
    }

    /**
     * Bypasses [ScannableCardInputValidator] so the test can hand
     * [ScannableCardView] a payload that ZXing's writer rejects. The validator
     * exists precisely to prevent this state in production, so the only way to
     * reach the encoder-failure UI branch from a unit test is to invoke the
     * type's internal constructor directly via kotlin-reflect. Limit usage to
     * tests that need to assert behaviour of that defensive path.
     *
     * Bound by parameter name via `callBy` rather than positional `call`: a
     * future add / remove / reorder of a primary-constructor parameter surfaces
     * as a clear missing-key failure at test time, not a same-typed-field swap
     * that quietly tests the wrong assertion.
     */
    private fun encoderRejectedEan13Fixture(): ScannableCard {
        val ctor = requireNotNull(ScannableCard::class.primaryConstructor) {
            "ScannableCard has no primary constructor"
        }
        ctor.isAccessible = true
        val args = mapOf(
            "id" to ScannableCardId("test"),
            "payload" to "12345678901",
            "format" to ScannableFormat.Ean13,
            "label" to "Library card",
            "createdAt" to PassInstant(0L),
        )
        val byName = ctor.parameters.associateBy { it.name }
        val missing = args.keys - byName.keys
        require(missing.isEmpty()) {
            "ScannableCard primary constructor drifted; unknown params: $missing. " +
                "Update encoderRejectedEan13Fixture before changing the data class."
        }
        return ctor.callBy(args.mapKeys { (name, _) -> byName.getValue(name) })
    }
}
