package `is`.walt.passes.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.BarcodeEncoder
import `is`.walt.passes.core.BarcodeMatrix
import `is`.walt.passes.core.EncodeResult
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.ui.core.isolated

/**
 * Renders [card]'s barcode through [BarcodeEncoder] as a 1-bit-per-module bitmap.
 * Minimum on-screen sizes mirror [BarcodeView] so the two barcode surfaces are
 * consistent at gate distance: 240 dp square for QR, 320 dp x 96 dp for the four
 * 1D symbologies. Painted with `FilterQuality.None` so the per-module upscale stays
 * nearest-neighbor and module edges remain sharp.
 *
 * `contentScale` differs per format. QR uses [ContentScale.Fit] because its matrix
 * is square and [ContentScale.FillBounds] would distort it if the slot is non-square.
 * The four 1D symbologies use [ContentScale.FillBounds] because [BarcodeEncoder]
 * emits their matrices at the symbology's natural minimum — exactly one module tall
 * (see `ZxingBarcodeEncoder.writeMatrix`) — and `Fit` against a ~200:1 painter
 * intrinsic ratio collapses the painted height to a few pixels in a normal-aspect
 * slot. `FillBounds` stretches vertically and, combined with [FilterQuality.None],
 * keeps module edges sharp since 1D barcodes carry no data on the vertical axis.
 *
 * Encoder failures render as a same-sized [Spacer] with a TalkBack-readable
 * `contentDescription` rather than throwing. Card chrome (background tint, label,
 * "Created by you" trust caption) belongs on the surrounding tile (wpass-lzi.8) so
 * this surface can be reused as the full-screen scan view unchanged.
 *
 * When [showPayloadCaption] is true the encoded payload is rendered as a monospace,
 * user-selectable caption beneath the barcode — fallback for when a point-of-sale
 * scanner cannot read the code (GH issue #102). The caption is FSI/PDI isolated as
 * defense-in-depth on top of the create-boundary Cf/Cc rejection (C3 in
 * `docs/SCANNABLE_CARD_THREAT_MODEL.md`). Default false; only [ScannableCardScreen]
 * opts in. The tile / row registers keep it off because their identification-sized
 * preview leaves no room for a legible caption.
 */
@Composable
public fun ScannableCardView(
    card: ScannableCard,
    modifier: Modifier = Modifier,
    showPayloadCaption: Boolean = false,
) {
    val bitmap = remember(card.payload, card.format) {
        when (val result = BarcodeEncoder.encode(card.payload, card.format)) {
            is EncodeResult.Success -> result.matrix.toMonochromeBitmap()
            is EncodeResult.Failure -> null
        }
    }

    if (!showPayloadCaption) {
        BarcodeImage(card.format, bitmap, imageDescription = card.label, modifier = modifier)
    } else {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CAPTION_GAP),
        ) {
            // Drop the image's contentDescription so TalkBack reads the caption,
            // not the duplicate label. clearAndSetSemantics would dedupe too but
            // would zap the caption.
            BarcodeImage(card.format, bitmap, imageDescription = null, modifier = Modifier)
            SelectionContainer {
                Text(
                    text = isolated(card.payload),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun BarcodeImage(
    format: ScannableFormat,
    bitmap: Bitmap?,
    imageDescription: String?,
    modifier: Modifier,
) {
    val (minWidthDp, minHeightDp) = format.minRenderSizeDp()
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(
                image = bitmap.asImageBitmap(),
                filterQuality = FilterQuality.None,
            ),
            contentDescription = imageDescription,
            contentScale = format.contentScale(),
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

/** Per-symbology paint scale. See ScannableCardView KDoc for the QR-vs-1D split. */
private fun ScannableFormat.contentScale(): ContentScale = when (this) {
    ScannableFormat.Qr -> ContentScale.Fit
    ScannableFormat.Code128,
    ScannableFormat.Ean13,
    ScannableFormat.UpcA,
    ScannableFormat.Code39,
    -> ContentScale.FillBounds
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

private val CAPTION_GAP = 12.dp
