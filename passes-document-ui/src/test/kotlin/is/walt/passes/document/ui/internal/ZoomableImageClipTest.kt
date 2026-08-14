package `is`.walt.passes.document.ui.internal

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * Pixel-level lock that zoomed [ZoomableImage] content cannot draw outside its slot
 * (ADR 0005 Z.8 / wpass-pl7.4 review). The bitmap is scaled with `graphicsLayer`, which
 * draws OUTSIDE layout bounds unless clipped; on the full-screen surface the slot's
 * sibling is the docked trust-caption band, so un-clipped overdraw puts attacker-chosen
 * document pixels behind and around the caption. The PDF arm was only incidentally
 * protected by `HorizontalPager`'s own clipping; the image arm has no pager, so the clip
 * must live on [ZoomableImage] itself.
 *
 * Layout-bounds assertions cannot see overdraw, so this renders to pixels: a solid-red
 * bitmap in the full-screen dock structure, the deterministic double-tap 2x zoom, then a
 * `view.draw` capture (compose-test's window capture does not run under Robolectric)
 * asserting no red reaches the dock band. The dock is deliberately UNPAINTED: the theme
 * contract permits a transparent captionBackground, and an opaque dock would mask the
 * overdraw in sibling draw order — precisely the configuration under which the defect is
 * user-visible. A vacuous pass is ruled out by asserting the zoom really engaged (red
 * reaches the slot edges the letterboxed 1x fit leaves white).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZoomableImageClipTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun doubleTapZoomedBitmapNeverDrawsIntoTheDockBandBelowItsSlot() {
        val red = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(RED) }
            .asImageBitmap()

        composeRule.setContent {
            // The full-screen surface's BottomDockedLayout shape: the zoom slot takes the
            // space above a dock band, exactly how FullScreenDocumentView hosts the arms.
            Box(modifier = Modifier.size(200.dp, 400.dp).background(Color.White)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxWidth().height(340.dp)) {
                        ZoomableImage(
                            bitmap = red,
                            contentDescription = "zoom-target",
                            modifier = Modifier.fillMaxSize(),
                            pageAspect = 0.5f,
                        )
                    }
                    // No background: models the transparent-captionBackground theme, where
                    // overdraw into the dock band is not masked by the dock's own paint.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .testTag("dock"),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("zoom-target").performTouchInput {
            doubleClick()
        }
        composeRule.waitForIdle()

        val view = (composeRule.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val capture = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(capture))
        val density = composeRule.density.density
        val dockTopPx = (
            composeRule.onNodeWithTag("dock").getUnclippedBoundsInRoot().top.value * density
            ).roundToInt()

        val counts = countRed(capture, dockTopPx)
        // Sanity: the red bitmap rendered AND the double-tap zoom engaged — otherwise
        // the dock assertion would pass vacuously against an unzoomed (in-bounds) fit.
        assertThat(counts.inSlot).isGreaterThan(0)
        assertThat(counts.atSlotEdge).isGreaterThan(0)
        assertWithMessage(
            "zoomed content pixels drew into the dock band below the slot (Z.8); " +
                "ZoomableImage must clip to its own bounds",
        ).that(counts.inDock).isEqualTo(0)
    }

    private data class RedCounts(val inSlot: Int, val inDock: Int, val atSlotEdge: Int)

    private fun countRed(capture: Bitmap, dockTopPx: Int): RedCounts {
        var inSlot = 0
        var inDock = 0
        var atSlotEdge = 0
        for (i in 0 until capture.width * capture.height) {
            val x = i % capture.width
            val y = i / capture.width
            if (capture.getPixel(x, y) != RED) continue
            if (y >= dockTopPx) inDock++ else inSlot++
            // At 1x the 0.5-aspect fit leaves letterbox gutters at the slot's horizontal
            // edges; red there proves the 2x zoom really engaged.
            if (y < dockTopPx && (x < 4 || x > capture.width - 5)) atSlotEdge++
        }
        return RedCounts(inSlot, inDock, atSlotEdge)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
