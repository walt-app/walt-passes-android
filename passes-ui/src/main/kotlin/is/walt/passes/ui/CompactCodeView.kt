package `is`.walt.passes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.BarcodeEncoder
import `is`.walt.passes.core.EncodeResult
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.ui.internal.BARCODE_RENDER_FAILURE_DESCRIPTION
import `is`.walt.passes.ui.internal.barcodeContentScale
import `is`.walt.passes.ui.internal.toMonochromeBitmap

/**
 * Row-scale code render for wallet-list card faces (wpass-tjc.2; consumer epic
 * wlt-mx2d). Where [ScannableCardView] enforces gate-distance minimum sizes for the
 * detail surface, this surface renders the same `(payload, format)` pair compactly —
 * the redesign's neutral list cards show a scannable's or composite's ACTUAL code on
 * the card face (a ~90 dp QR tile, or a full-width 1D band), so the code is usable at
 * a reader straight from the list.
 *
 * The blessed-path guarantees, so consumers do not hand-roll them per card face:
 *
 *  - **White backing, both themes.** The backing is literally [Color.White], never a
 *    theme surface token, so the code scans in dark mode (spec board: "barcode
 *    tiles/thumbnails stay light"). Consumers clip the outer shape via [modifier];
 *    the backing itself is not optional.
 *  - **Quiet zones.** The [BarcodeEncoder] matrix already carries each symbology's
 *    quiet-zone modules at natural size; the fixed white inner padding on top
 *    guarantees a visible quiet zone even when the consumer's tile hugs the code.
 *  - **Sharp modules.** Nearest-neighbor upscale ([FilterQuality.None]), matching
 *    [ScannableCardView]. QR paints [ContentScale.Fit] (square matrix, no
 *    distortion); the 1D symbologies paint [ContentScale.FillBounds] because their
 *    matrices are one module tall and carry no data on the vertical axis.
 *
 * Sizing is the caller's: pass the tile size via [modifier] (the small
 * `defaultMinSize` floor only guards against a collapsed, unscannable render).
 * Encoder failures render as a same-sized white tile with a TalkBack-readable
 * "Barcode failed to render" description instead of throwing, mirroring
 * [ScannableCardView]. Encoding runs synchronously in composition (`remember`),
 * the same trade [ScannableCardView] makes: for validated list-scale payloads the
 * ZXing writers are sub-millisecond, and a card face renders one code. A consumer
 * observing jank on pathological lists owns the async wrapping.
 *
 * ## Trust posture
 *
 * This is mechanism, not chrome: no label, no eyebrow, no trust caption, no
 * signature affordance can be composed here. The C1/C2 list-surface distinctions
 * (class distinction at list scale — see `docs/SCANNABLE_CARD_THREAT_MODEL.md`) stay
 * on the consumer's card, and kernel trust captions stay on detail surfaces. C5 posture is
 * unchanged: this path only re-renders through the kernel's encoder-only
 * [BarcodeEncoder]; it adds no decode surface. [contentDescription] exists because
 * the code is usually the card face's dominant visual: the consumer passes its
 * merged card description (or null when a parent node already carries it).
 */
@Composable
public fun CompactCodeView(
    payload: String,
    format: ScannableFormat,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val bitmap = remember(payload, format) {
        when (val result = BarcodeEncoder.encode(payload, format)) {
            is EncodeResult.Success -> result.matrix.toMonochromeBitmap()
            is EncodeResult.Failure -> null
        }
    }
    val (minWidthDp, minHeightDp) = format.compactMinSizeDp()

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = minWidthDp.dp, minHeight = minHeightDp.dp)
            .background(COMPACT_CODE_BACKING),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                painter = BitmapPainter(
                    image = bitmap.asImageBitmap(),
                    filterQuality = FilterQuality.None,
                ),
                contentDescription = contentDescription,
                contentScale = format.barcodeContentScale(),
                modifier = Modifier
                    .matchParentSize()
                    .padding(QUIET_ZONE_PADDING),
            )
        } else {
            // Same-sized failure placeholder; silent blank rectangles are the worst
            // a11y failure mode. Wording matches ScannableCardView's placeholder.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .semantics { this.contentDescription = BARCODE_RENDER_FAILURE_DESCRIPTION },
            )
        }
    }
}

/**
 * Row-scale floors, deliberately below [ScannableCardView]'s gate-distance minimums:
 * they only prevent a degenerate collapsed render when the caller forgets to size
 * the tile, they are not a scannability promise at arbitrary sizes.
 */
private fun ScannableFormat.compactMinSizeDp(): Pair<Int, Int> = when (this) {
    ScannableFormat.Qr -> 48 to 48
    ScannableFormat.Code128,
    ScannableFormat.Ean13,
    ScannableFormat.UpcA,
    ScannableFormat.Code39,
    -> 96 to 32
}

private val QUIET_ZONE_PADDING = 8.dp

/**
 * Literally white, never a theme surface token — the dark-mode scannability guarantee.
 * Internal (not private) so the smoke test pins the value; rerouting the backing off
 * this constant is amending the blessed-path contract, not a refactor.
 */
internal val COMPACT_CODE_BACKING: Color = Color.White
