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
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.BarcodeEncoder
import `is`.walt.passes.core.BarcodeMatrix
import `is`.walt.passes.core.EncodeResult
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.core.ScannableFormat

/**
 * Renders [card]'s barcode by feeding `(payload, format)` through
 * [BarcodeEncoder.encode] and painting the resulting [BarcodeMatrix] as a 1-bit
 * bitmap. Enforces a minimum on-screen size so the barcode is reliably scannable at
 * gate distance: 240 dp x 240 dp for the QR symbology; 320 dp x 96 dp for the four
 * 1D symbologies (Code128, EAN-13, UPC-A, Code39). Same sizing scheme as the existing
 * [BarcodeView] (which renders verified-pass PKPASS barcodes), so the kernel's two
 * barcode-rendering surfaces stay visually consistent at gate.
 *
 * Encoder failures (rare; the kernel validator already gated the input at create
 * time) render as an empty placeholder of the same minimum size rather than throwing,
 * so a corrupted or otherwise-unencodable row does not crash the surface that
 * surrounds it. The placeholder is silent intentionally: the consumer's tile chrome
 * (issue wpass-lzi.8) is the surface that explains the artifact class to the user
 * and renders the "Created by you" trust caption; this composable is the bare-bones
 * scanner-facing matrix and nothing else.
 *
 * Card chrome — background tint from [ScannableCard.color], label rendering, the
 * "Created by you" trust caption — is intentionally NOT in this composable. Those
 * belong on the surrounding tile (wpass-lzi.8) so this surface can be reused as the
 * full-screen scan view without dragging tile chrome along.
 *
 * `[BitmapPainter]` is constructed with `filterQuality = FilterQuality.None` so the
 * Compose scaler does not introduce bilinear blur between modules. Sharp module
 * edges are essential for scanner reliability at gate distance; the per-module
 * upscale from the source bitmap to the final dp size happens at draw time via
 * nearest-neighbor.
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
        // Encoder-failure placeholder. Same dimensions as the barcode so the surrounding
        // layout does not shift between the success and failure paths.
        Spacer(
            modifier = modifier.defaultMinSize(
                minWidth = minWidthDp.dp,
                minHeight = minHeightDp.dp,
            ),
        )
    }
}

/**
 * Minimum on-screen size per symbology, in dp. The QR symbology lays out as a square;
 * the 1D symbologies prefer a wide rectangle so the bars have enough horizontal
 * resolution to scan reliably without the user having to tilt the phone. Values
 * mirror the existing [BarcodeView] for consistency at gate.
 */
private fun ScannableFormat.minRenderSizeDp(): Pair<Int, Int> = when (this) {
    ScannableFormat.Qr -> 240 to 240
    ScannableFormat.Code128,
    ScannableFormat.Ean13,
    ScannableFormat.UpcA,
    ScannableFormat.Code39,
    -> 320 to 96
}

/**
 * Paints a [BarcodeMatrix] into a 1-bit-per-module ARGB_8888 bitmap. The bitmap is
 * matrix-sized, not screen-sized — Compose scales it to the final dp container via
 * `BitmapPainter(filterQuality = None)`, which is nearest-neighbor and so preserves
 * sharp module edges.
 *
 * Two passes over the matrix would be wasteful; the loop materializes the whole
 * `pixels` IntArray in one sweep and hands it to `Bitmap.createBitmap` in one call.
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
