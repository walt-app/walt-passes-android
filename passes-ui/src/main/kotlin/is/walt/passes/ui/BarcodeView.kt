package `is`.walt.passes.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat as ZxingFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import `is`.walt.passes.core.Barcode
import `is`.walt.passes.core.BarcodeFormat

/**
 * Renders [barcode] using ZXing. Enforces a minimum on-screen size so the barcode is
 * reliably scannable at gate distance: 240 dp x 240 dp for QR / Aztec; 320 dp x 96 dp
 * for PDF417 / Code128.
 *
 * `altText` from the [Barcode], when present, is rendered below the barcode at small
 * caption type. It is the accessibility-and-fallback text the PKPASS spec defines for
 * scanners that fail.
 *
 * Decoding errors (rare; would mean ZXing rejected the encoded message) render as an
 * empty placeholder rather than throwing, so a malformed barcode does not crash the
 * pass-rendering surface.
 */
@Composable
public fun BarcodeView(
    barcode: Barcode,
    modifier: Modifier = Modifier,
) {
    val zxingFormat = barcode.format.toZxing()
    val (minWidthDp, minHeightDp) = when (barcode.format) {
        BarcodeFormat.QR, BarcodeFormat.Aztec -> 240 to 240
        BarcodeFormat.PDF417, BarcodeFormat.Code128 -> 320 to 96
    }

    val bitmap = remember(barcode.message, zxingFormat, minWidthDp, minHeightDp) {
        runCatching {
            encodeBarcode(
                message = barcode.message,
                format = zxingFormat,
                widthPx = minWidthDp * 3,
                heightPx = minHeightDp * 3,
            )
        }.getOrNull()
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (bitmap != null) {
            Image(
                painter = BitmapPainter(bitmap.asImageBitmap()),
                contentDescription = barcode.altText,
                modifier = Modifier.defaultMinSize(
                    minWidth = minWidthDp.dp,
                    minHeight = minHeightDp.dp,
                ),
            )
        } else {
            // Decode-failure placeholder. Same dimensions as the barcode so the layout
            // does not shift between the success and failure paths.
            Spacer(Modifier.defaultMinSize(minWidth = minWidthDp.dp, minHeight = minHeightDp.dp))
        }

        if (!barcode.altText.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = barcode.altText!!,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun encodeBarcode(
    message: String,
    format: ZxingFormat,
    widthPx: Int,
    heightPx: Int,
): Bitmap {
    val matrix: BitMatrix = try {
        MultiFormatWriter().encode(message, format, widthPx, heightPx)
    } catch (e: WriterException) {
        throw IllegalArgumentException("ZXing rejected payload", e)
    }
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val rowOffset = y * width
        for (x in 0 until width) {
            pixels[rowOffset + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun BarcodeFormat.toZxing(): ZxingFormat = when (this) {
    BarcodeFormat.QR -> ZxingFormat.QR_CODE
    BarcodeFormat.PDF417 -> ZxingFormat.PDF_417
    BarcodeFormat.Aztec -> ZxingFormat.AZTEC
    BarcodeFormat.Code128 -> ZxingFormat.CODE_128
}
