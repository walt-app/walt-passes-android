package `is`.walt.passes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.core.ScannableCardCreateInput
import `is`.walt.passes.core.ScannableCardCreateResult
import `is`.walt.passes.core.ScannableCardId
import `is`.walt.passes.core.ScannableCardInputValidator
import `is`.walt.passes.core.ScannableFormat
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

/**
 * On-device pin for the one thing the JVM tests cannot observe: that `CodeCard` actually
 * resolves its colors through `facePaint`, rather than re-deriving them inline.
 *
 * `ScannableCardTrustSurfaceTest` pins `facePaint`'s output directly, but both of its
 * branches paint *something* and neither changes the composition tree, so re-inlining
 * `faceTint.isSpecified` at the call site would reintroduce the wpass-80y.5 bug with every
 * unit test still green. Reading the painted pixel is what closes that seam, and
 * `captureToImage()` does not work under Robolectric even at `@GraphicsMode(NATIVE)` — its
 * `forceRedraw` handshake times out with both `createComposeRule` and
 * `createAndroidComposeRule`. Same reason `DocumentViewInstrumentedTest` lives here.
 *
 * The theme deliberately uses colors that appear nowhere else, so a passing assertion cannot
 * be a coincidence of the default palette.
 */
@RunWith(AndroidJUnit4::class)
class ScannableCardFaceTintInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun transparentFaceTintPaintsTheThemeSurfaceNotTheTint() {
        composeRule.setContent {
            ThemedHost {
                ScannableCardScreen(card = qrFixture(), faceTint = Color.Transparent)
            }
        }
        composeRule.waitForIdle()

        // The label node's bounds cover both halves of the bug: glyph pixels are the ink,
        // the gaps between them are face paint. Under the pre-fix gate the face painted the
        // transparent tint (so those gaps show the host window, not SURFACE) and inkOn read
        // luminance 0 off it (so the glyphs come out white, not ON_SURFACE). Each assertion
        // therefore fails independently against the old gate.
        val face = composeRule.onNodeWithText("⁨Membership⁩").captureToImage()
        val pixels = IntArray(face.width * face.height)
        face.readPixels(pixels)

        assertWithMessage("a transparent faceTint must paint the theme surface, not the tint")
            .that(pixels.toList())
            .contains(SURFACE.toArgb())
        assertWithMessage("ink must come from the theme, not from inkOn(Color.Transparent)")
            .that(pixels.toList())
            .contains(ON_SURFACE.toArgb())
    }

    @Composable
    private fun ThemedHost(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = lightColorScheme(surface = SURFACE, onSurface = ON_SURFACE),
        ) {
            PassesTheme(semantics = semantics, content = content)
        }
    }

    // Only unverifiedArtifact matters to this surface; the rest are required constructor
    // slots filled with values this test never reads.
    private val semantics = PassesSemantics(
        signatureBadge = SignatureBadgeColors(
            unsignedBackground = UNUSED,
            unsignedForeground = UNUSED,
            selfSignedBackground = UNUSED,
            selfSignedForeground = UNUSED,
            appleVerifiedBackground = UNUSED,
            appleVerifiedForeground = UNUSED,
            certChainIncompleteBackground = UNUSED,
            certChainIncompleteForeground = UNUSED,
        ),
        expiredBadge = ExpiredBadgeStyle(
            pillBackground = UNUSED,
            pillForeground = UNUSED,
            scrimAlpha = 96,
        ),
        securitySheet = SecuritySheetStyle(
            sheetBackground = UNUSED,
            emphasisBackground = UNUSED,
            emphasisForeground = UNUSED,
            bodyForeground = UNUSED,
            confirmContainer = UNUSED,
            confirmForeground = UNUSED,
            cancelForeground = UNUSED,
        ),
        categoryAccent = CategoryAccentColors(
            boardingPass = UNUSED,
            eventTicket = UNUSED,
            coupon = UNUSED,
            storeCard = UNUSED,
            generic = UNUSED,
        ),
        unverifiedArtifact = UnverifiedArtifactStyle(
            accent = ArgbColor(0xFF8A4A2E.toInt()),
            captionBackground = ArgbColor(0xFFFFF0E0.toInt()),
            captionForeground = ArgbColor(0xFF301010.toInt()),
        ),
    )

    private fun qrFixture(): ScannableCard {
        val result = ScannableCardInputValidator.validate(
            input = ScannableCardCreateInput(
                payload = "WALT-MEMBER-12345",
                format = ScannableFormat.Qr,
                label = "Membership",
            ),
            id = ScannableCardId("test"),
            createdAt = PassInstant(0L),
        )
        return (result as ScannableCardCreateResult.Success).card
    }

    private companion object {
        // Off-palette on purpose: neither is a Material default or a 26.08.08 tint.
        val SURFACE = Color(0xFF3B2E57)
        val ON_SURFACE = Color(0xFFF3E9C8)
        val UNUSED = ArgbColor(0xFF000000.toInt())
    }
}
