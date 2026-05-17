package `is`.walt.passes.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.BarcodeEncoder
import `is`.walt.passes.core.BarcodeMatrix
import `is`.walt.passes.core.EncodeResult
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.core.ScannableFormat

/**
 * Renders [card]'s barcode through [BarcodeEncoder] as a 1-bit-per-module bitmap.
 * Minimum on-screen sizes mirror [BarcodeView] so the two barcode surfaces are
 * consistent at gate distance: 240 dp square for QR, 320 dp x 96 dp for the four
 * 1D symbologies. Painted with `FilterQuality.None` so the per-module upscale stays
 * nearest-neighbor and module edges remain sharp.
 *
 * Encoder failures render as a same-sized [Spacer] with a TalkBack-readable
 * `contentDescription` rather than throwing. Card chrome (background tint, label,
 * "Created by you" trust caption) belongs on the surrounding tile (wpass-lzi.8) so
 * this surface can be reused as the full-screen scan view unchanged.
 */
@Composable
public fun ScannableCardView(
    card: ScannableCard,
    modifier: Modifier = Modifier,
) {
    val (minWidthDp, minHeightDp) = card.format.minRenderSizeDp()

    val bitmap = remember(card.payload, card.format) {
        when (val result = BarcodeEncoder.encode(card.payload, card.format)) {
            is EncodeResult.Success -> result.matrix.toMonochromeBitmap()
            is EncodeResult.Failure -> null
        }
    }

    if (bitmap != null) {
        Image(
            painter = BitmapPainter(
                image = bitmap.asImageBitmap(),
                filterQuality = FilterQuality.None,
            ),
            contentDescription = card.label,
            modifier = modifier.defaultMinSize(
                minWidth = minWidthDp.dp,
                minHeight = minHeightDp.dp,
            ),
        )
    } else {
        // Same dimensions as the barcode so layout does not shift between paths.
        // TalkBack signal so a user with vision support gets *something* — silent
        // blank rectangles are the worst a11y failure mode.
        Spacer(
            modifier = modifier
                .defaultMinSize(minWidth = minWidthDp.dp, minHeight = minHeightDp.dp)
                .semantics { contentDescription = "Barcode failed to render" },
        )
    }
}

/** Per-symbology min on-screen size in dp. Mirrors [BarcodeView] for gate consistency. */
private fun ScannableFormat.minRenderSizeDp(): Pair<Int, Int> = when (this) {
    ScannableFormat.Qr -> 240 to 240
    ScannableFormat.Code128,
    ScannableFormat.Ean13,
    ScannableFormat.UpcA,
    ScannableFormat.Code39,
    -> 320 to 96
}

/**
 * Paints a [BarcodeMatrix] into a matrix-sized ARGB_8888 bitmap. Compose scales to
 * the final dp container nearest-neighbor via `BitmapPainter(filterQuality = None)`.
 */
private fun BarcodeMatrix.toMonochromeBitmap(): Bitmap {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val rowOffset = y * width
        for (x in 0 until width) {
            pixels[rowOffset + x] = if (isSet(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
