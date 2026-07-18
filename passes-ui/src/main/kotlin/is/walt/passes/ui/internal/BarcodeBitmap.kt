package `is`.walt.passes.ui.internal

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import `is`.walt.passes.core.BarcodeMatrix

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
