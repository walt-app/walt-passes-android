package `is`.walt.passes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device pin that `CodeCard` resolves its colors through `facePaint` — in both
 * directions. `ScannableCardTrustSurfaceTest` pins `facePaint`'s own output, but both of its
 * branches paint *something* and neither changes the composition tree, so at the call site
 * neither re-inlining `faceTint.isSpecified` (reintroducing the wpass-80y.5 bug) nor dropping
 * `faceTint` entirely (ignoring the consumer's tint) is visible to a composition assertion.
 * Reading painted pixels is what closes both seams.
 *
 * This is why the file exists rather than folding into the Robolectric suite:
 * `captureToImage()` does not work there even at `@GraphicsMode(NATIVE)` — its `forceRedraw`
 * handshake times out with both `createComposeRule` and `createAndroidComposeRule` (probed,
 * wpass-80y.5). Same reason `DocumentViewInstrumentedTest` lives in `androidTest`. The
 * managed-device matrix in `passes-ui/build.gradle.kts` and the connected-tests CI step are
 * what actually run it; without those this class would assert into a void.
 *
 * The theme uses colors that appear nowhere else in the palette, so no assertion here can
 * pass by coincidence of a Material default or a 26.08.08 tint.
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

        // The label node's bounds cover both halves of the bug: the gaps between glyphs are
        // face paint, the glyphs themselves are the ink. Under the pre-fix gate the face
        // painted the transparent tint (so the gaps show the host window, not SURFACE) and
        // inkOn read luminance 0 off it (so the glyphs come out white, not ON_SURFACE).
        val pixels = capturedLabelPixels()

        assertWithMessage("a transparent faceTint must paint the theme surface, not the tint")
            .that(pixels.any { it == SURFACE.toArgb() })
            .isTrue()
        assertWithMessage("ink must come from the theme, not from inkOn(Color.Transparent)")
            .that(pixels.any { it isNear ON_SURFACE })
            .isTrue()
    }

    @Test
    fun opaqueFaceTintReachesTheCardFace() {
        composeRule.setContent {
            ThemedHost {
                ScannableCardScreen(card = qrFixture(), faceTint = TINT)
            }
        }
        composeRule.waitForIdle()

        // The opposite direction from the test above, and it fails against a different
        // regression: a CodeCard rewritten to ignore faceTint and always take the theme
        // tokens would keep every other test on this branch green, including that one.
        val pixels = capturedLabelPixels()

        assertWithMessage("an opaque faceTint must reach the card face")
            .that(pixels.any { it == TINT.toArgb() })
            .isTrue()
        assertWithMessage("a tinted face must not fall back to the theme surface")
            .that(pixels.any { it == SURFACE.toArgb() })
            .isFalse()
    }

    /** Pixels of the label node, whose bounds span face paint and ink. */
    private fun capturedLabelPixels(): IntArray {
        val image: ImageBitmap = composeRule.onNodeWithText("⁨Membership⁩").captureToImage()
        return IntArray(image.width * image.height).also(image::readPixels)
    }

    /**
     * Channel-wise near-match. Flat face paint is compared exactly, but glyph interiors are
     * antialiased, so an exact ink match would depend on a fully-opaque interior pixel
     * surviving the renderer's text pipeline — which varies across the API 28..36 matrix.
     */
    private infix fun Int.isNear(expected: Color): Boolean {
        val want = expected.toArgb()
        return listOf(24, 16, 8, 0).all { shift ->
            abs(((this shr shift) and 0xFF) - ((want shr shift) and 0xFF)) <= CHANNEL_TOLERANCE
        }
    }

    @Composable
    private fun ThemedHost(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = lightColorScheme(surface = SURFACE, onSurface = ON_SURFACE),
        ) {
            PassesTheme(semantics = semantics, content = content)
        }
    }

    // unverifiedArtifact is left at its Placeholder default — nothing here asserts on the
    // caption. The rest are required constructor slots this test never reads.
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
        // Off-palette on purpose: none is a Material default or a 26.08.08 tint. TINT is dark
        // enough that inkOn flips to white on it, so ON_SURFACE cannot appear in that test.
        val SURFACE = Color(0xFF3B2E57)
        val ON_SURFACE = Color(0xFFF3E9C8)
        val TINT = Color(0xFF14503C)
        val UNUSED = ArgbColor(0xFF000000.toInt())

        const val CHANNEL_TOLERANCE = 8
    }
}
