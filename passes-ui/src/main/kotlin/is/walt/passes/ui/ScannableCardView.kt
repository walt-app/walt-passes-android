package `is`.walt.passes.ui

import android.graphics.Bitmap
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
import `is`.walt.passes.core.EncodeResult
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.ui.core.isolated
import `is`.walt.passes.ui.internal.BARCODE_RENDER_FAILURE_DESCRIPTION
import `is`.walt.passes.ui.internal.barcodeContentScale
import `is`.walt.passes.ui.internal.toMonochromeBitmap

/**
 * Renders [card]'s barcode through [BarcodeEncoder] as a 1-bit-per-module bitmap.
 * Minimum on-screen sizes mirror [BarcodeView] so the two barcode surfaces are
 * consistent at gate distance: 240 dp square for the square 2D symbologies,
 * 320 dp x 96 dp for the 1D ones. Painted with `FilterQuality.None` so the
 * per-module upscale stays nearest-neighbor and module edges remain sharp.
 *
 * `contentScale` differs per format. The 2D symbologies use [ContentScale.Fit]
 * because they carry data on both axes, so [ContentScale.FillBounds] would corrupt
 * the symbol in a slot of the wrong aspect.
 * The 1D symbologies use [ContentScale.FillBounds] because [BarcodeEncoder]
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
 * `docs/SCANNABLE_CARD_THREAT_MODEL.md`). Default false, and no kernel surface opts in:
 * [ScannableCardScreen] renders the same readback on its card face so the white code
 * panel stays a pure scan target, and the tile / row registers keep it off because their
 * identification-sized preview leaves no room for a legible caption. It stays available
 * for hosts composing their own detail surface from this view.
 */
@Composable
public fun ScannableCardView(
    card: ScannableCard,
    modifier: Modifier = Modifier,
    showPayloadCaption: Boolean = false,
) {
    if (!showPayloadCaption) {
        ScannableCodeImage(card, imageDescription = card.label, modifier = modifier)
    } else {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CAPTION_GAP),
        ) {
            ScannableCodeImage(card, imageDescription = null, modifier = Modifier)
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

/**
 * The encoded code alone. [imageDescription] is null wherever the composing surface
 * already announces the label or the payload in its own text — TalkBack would otherwise
 * read the label twice. `clearAndSetSemantics` would dedupe too, but would zap the
 * neighbouring caption. Encoder failures still carry the failure description, which
 * [BarcodeImage] owns.
 */
@Composable
internal fun ScannableCodeImage(
    card: ScannableCard,
    imageDescription: String?,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(card.payload, card.format) {
        when (val result = BarcodeEncoder.encode(card.payload, card.format)) {
            is EncodeResult.Success -> result.matrix.toMonochromeBitmap()
            is EncodeResult.Failure -> null
        }
    }
    BarcodeImage(card.format, bitmap, imageDescription, modifier)
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
            contentScale = format.barcodeContentScale(),
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
                .semantics { contentDescription = BARCODE_RENDER_FAILURE_DESCRIPTION },
        )
    }
}

/** Per-symbology min on-screen size in dp. Mirrors [BarcodeView] for gate consistency. */
private fun ScannableFormat.minRenderSizeDp(): Pair<Int, Int> = when (this) {
    ScannableFormat.Qr, ScannableFormat.Aztec -> 240 to 240
    // Taller floor than the 1D row so the stacked rows are not collapsed. Provisional:
    // wpass-pl7.6 verifies it against the writer's actual aspect ratio.
    ScannableFormat.Pdf417 -> 320 to 120
    ScannableFormat.Code128,
    ScannableFormat.Ean13,
    ScannableFormat.UpcA,
    ScannableFormat.Code39,
    -> 320 to 96
}

private val CAPTION_GAP = 12.dp
