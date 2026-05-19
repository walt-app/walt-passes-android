package `is`.walt.passes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.core.ScannableCardCreateInput
import `is`.walt.passes.core.ScannableCardCreateResult
import `is`.walt.passes.core.ScannableCardId
import `is`.walt.passes.core.ScannableCardInputValidator
import `is`.walt.passes.core.ScannableFormat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pins the layout-bounds contract of [ScannableCardView] for 1D barcodes.
 *
 * Regression guard for wpass-0j1: [BarcodeEncoder] emits 1D matrices at the natural
 * symbology minimum, which is exactly one module tall. Without a per-format
 * [androidx.compose.ui.layout.ContentScale], `Image` defaults to
 * `ContentScale.Fit` and the painter's ~200:1 intrinsic ratio collapses the painted
 * height to ~1-2 dp in a normal-aspect container. The fix is per-format scale; this
 * test would have caught the original bug by asserting the rendered barcode fills
 * (rather than collapses inside) its container.
 *
 * QR is not asserted here because both `Fit` and `FillBounds` yield identical
 * layout bounds — the visible distortion lives in pixel placement inside those
 * bounds, which `getUnclippedBoundsInRoot` does not observe. The QR-stays-`Fit`
 * contract is pinned by [ScannableCardView]'s KDoc.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScannableCardViewLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun oneDimensionalBarcodeFillsContainerHeight() {
        // Container is much taller than the per-format minHeight (96 dp) so the test
        // actually exercises the scale path; a slot of exactly 96 dp tall would pass
        // even under the broken `Fit` behavior.
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(width = 320.dp, height = 200.dp)) {
                    ScannableCardView(
                        card = code128Fixture(),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        val bounds = composeRule
            .onNodeWithContentDescription("Gym")
            .getUnclippedBoundsInRoot()
        // Pre-fix this would be ~1-2 dp. Anything close to the container height is
        // proof FillBounds is in effect for 1D.
        assertThat(bounds.height.value).isGreaterThan(150f)
        assertThat(bounds.width.value).isGreaterThan(300f)
    }

    private fun code128Fixture(label: String = "Gym"): ScannableCard = card(
        format = ScannableFormat.Code128,
        payload = "ABCDE12345",
        label = label,
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
}
