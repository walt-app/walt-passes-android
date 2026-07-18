package `is`.walt.passes.ui.internal

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.layout.ContentScale
import `is`.walt.passes.core.BarcodeMatrix
import `is`.walt.passes.core.ScannableFormat

/**
 * Failure-placeholder wording shared by ScannableCardView and CompactCodeView; both
 * surfaces' tests pin the literal, this constant keeps the two from drifting.
 */
internal const val BARCODE_RENDER_FAILURE_DESCRIPTION: String = "Barcode failed to render"

/**
 * Per-symbology paint scale shared by ScannableCardView and CompactCodeView. QR uses
 * [ContentScale.Fit] (square matrix; FillBounds would distort in a non-square slot);
 * the 1D symbologies use [ContentScale.FillBounds] because their matrices are one
 * module tall and carry no data on the vertical axis. See ScannableCardView's KDoc.
 */
internal fun ScannableFormat.barcodeContentScale(): ContentScale = when (this) {
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
 * Shared by [ScannableCardView][`is`.walt.passes.ui.ScannableCardView] and
 * [CompactCodeView][`is`.walt.passes.ui.CompactCodeView].
 */
internal fun BarcodeMatrix.toMonochromeBitmap(): Bitmap {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val rowOffset = y * width
        for (x in 0 until width) {
            pixels[rowOffset + x] = if (isSet(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
