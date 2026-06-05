package `is`.walt.passes.barcode

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.ScannableFormat
import org.junit.Test

/**
 * Smoke coverage for the live-camera frame path (wpass-7xo.5): prove a barcode survives the
 * `Y-plane bytes + geometry → PlanarYUVLuminanceSource → decodeLuminance` route without a device
 * or CameraX. Each case ENCODES a symbol with ZXing's own writer, lays the black/white matrix into
 * a luminance plane (optionally with HAL row padding), and decodes it back — the exact pixel→symbol
 * path the consumer's per-frame analyzer runs, minus the `ImageProxy` glue. The exhaustive stride /
 * failure-arm / allowlist suite lives in the blocker wpass-7xo.6.
 */
class YPlaneFrameDecodeTest {
    @Test
    fun qrDecodesFromTightlyPackedYPlane() {
        val plane = encodeYPlane("WALT-LIVE-7", BarcodeFormat.QR_CODE, 320, 320, rowPadding = 0)

        assertThat(decodeYPlane(plane.bytes, plane.width, plane.height, rowStride = plane.width))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode("WALT-LIVE-7", ScannableFormat.Qr))
    }

    @Test
    fun qrDecodesThroughRowStridePadding() {
        // The common HAL case (ZXing #1387): rowStride > width. Pad each row by 48 bytes of noise;
        // a correct dataWidth = rowStride strips it, a wrong one feeds garbage into the binarizer.
        val padding = 48
        val plane = encodeYPlane("WALT-LIVE-7", BarcodeFormat.QR_CODE, 320, 320, rowPadding = padding)

        assertThat(
            decodeYPlane(
                plane.bytes,
                plane.width,
                plane.height,
                rowStride = plane.width + padding,
            ),
        ).isEqualTo(BarcodeDecodeResult.DecodedBarcode("WALT-LIVE-7", ScannableFormat.Qr))
    }

    @Test
    fun code128DecodesThroughRowStridePadding() {
        val padding = 32
        val plane = encodeYPlane("LOYALTY-ABC-123", BarcodeFormat.CODE_128, 600, 200, rowPadding = padding)

        assertThat(
            decodeYPlane(
                plane.bytes,
                plane.width,
                plane.height,
                rowStride = plane.width + padding,
            ),
        ).isEqualTo(BarcodeDecodeResult.DecodedBarcode("LOYALTY-ABC-123", ScannableFormat.Code128))
    }

    @Test
    fun qrDecodesThroughInterleavedPixelStride() {
        // A pixelStride == 2 semi-planar plane: real Y samples sit at even byte offsets, filler at
        // the odd ones. The tight-repack path must lift exactly the luminance bytes back out.
        val padding = 16
        val plane = encodeYPlane("WALT-LIVE-7", BarcodeFormat.QR_CODE, 320, 320, rowPadding = 0)
        val pixelStride = 2
        val rowStride = plane.width * pixelStride + padding
        val interleaved = ByteArray(rowStride * plane.height)
        for (row in 0 until plane.height) {
            for (col in 0 until plane.width) {
                interleaved[row * rowStride + col * pixelStride] = plane.bytes[row * plane.width + col]
            }
        }

        assertThat(
            decodeYPlane(
                interleaved,
                plane.width,
                plane.height,
                rowStride = rowStride,
                pixelStride = pixelStride,
            ),
        ).isEqualTo(BarcodeDecodeResult.DecodedBarcode("WALT-LIVE-7", ScannableFormat.Qr))
    }

    private class YPlane(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * Encode [content] as [format] and render the matrix into a luminance plane: black module → 0x00,
     * white → 0xFF. [rowPadding] appends that many filler bytes after each row so the plane's stride
     * exceeds its width, reproducing the HAL padding the decoder must strip.
     */
    private fun encodeYPlane(
        content: String,
        format: BarcodeFormat,
        width: Int,
        height: Int,
        rowPadding: Int,
    ): YPlane {
        val matrix = MultiFormatWriter().encode(content, format, width, height)
        val w = matrix.width
        val h = matrix.height
        val rowStride = w + rowPadding
        val bytes = ByteArray(rowStride * h)
        for (y in 0 until h) {
            val rowStart = y * rowStride
            for (x in 0 until w) {
                bytes[rowStart + x] = if (matrix.get(x, y)) BLACK else WHITE
            }
            // Leave padding bytes as 0x00 (black) so a stride bug would corrupt the right edge.
        }
        return YPlane(bytes, w, h)
    }

    private companion object {
        const val BLACK = 0x00.toByte()
        const val WHITE = 0xFF.toByte()
    }
}
