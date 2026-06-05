package `is`.walt.passes.barcode

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.ScannableFormat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The exhaustive JVM suite for the live-camera frame path (wpass-7xo.6), the counterpart to the
 * static [ZxingBarcodeSymbolDecoderTest] decode-contract suite. Like that one, every positive case
 * ENCODES a symbol with ZXing's own writer and decodes it back, so no device or CameraX is needed;
 * here the round-trip runs through `Y-plane bytes + geometry → PlanarYUVLuminanceSource →
 * decodeLuminance` — the exact pixel→symbol path the consumer's per-frame analyzer runs, minus the
 * `ImageProxy` glue.
 *
 * Coverage, mirroring the static suite plus the geometry surface unique to the live path:
 *  - **stride padding** — `rowStride > width` (zero-copy strip) and `pixelStride > 1` (tight repack),
 *    including the boundary where `rowStride == width * pixelStride`;
 *  - **failure arms** — a blank plane (no locatable symbol) and a located-but-ECC-exceeded symbol
 *    both surface as the honest [BarcodeDecodeResult.NoBarcodeFound], never a fabricated payload;
 *  - **allowlist** — a non-roster symbology (PDF417, Aztec) is not decoded;
 *  - **geometry preconditions** — the [decodeYPlane] `require` guards reject malformed caller wiring
 *    (non-positive dims, `pixelStride < 1`, `rowStride < width * pixelStride`, undersized buffer)
 *    with [IllegalArgumentException] rather than widening the result surface.
 */
class YPlaneFrameDecodeTest {
    // --- Round-trip + stride padding ------------------------------------------------------------

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
        assertThat(decodeInterleaved("WALT-LIVE-7", BarcodeFormat.QR_CODE, pixelStride = 2, rowPadding = 16))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode("WALT-LIVE-7", ScannableFormat.Qr))
    }

    @Test
    fun qrDecodesThroughWiderPixelStride() {
        // A wider pixelStride (3) proves the repack lifts the strided samples generally, not just
        // the semi-planar 2 case.
        assertThat(decodeInterleaved("WALT-LIVE-7", BarcodeFormat.QR_CODE, pixelStride = 3, rowPadding = 8))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode("WALT-LIVE-7", ScannableFormat.Qr))
    }

    @Test
    fun qrDecodesWhenRowStrideEqualsRowSpan() {
        // Boundary: rowStride == width * pixelStride exactly (no trailing padding) is the tightest
        // legal interleaved plane and must be accepted, not rejected by the rowStride guard.
        assertThat(decodeInterleaved("WALT-LIVE-7", BarcodeFormat.QR_CODE, pixelStride = 2, rowPadding = 0))
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode("WALT-LIVE-7", ScannableFormat.Qr))
    }

    // --- Failure arms ---------------------------------------------------------------------------

    @Test
    fun blankPlaneReturnsNoBarcodeFound() {
        // No locatable symbol -> NotFoundException -> the honest NoBarcodeFound.
        val width = 200
        val height = 200
        val blank = ByteArray(width * height) { 0xFF.toByte() }

        assertThat(decodeYPlane(blank, width, height, rowStride = width))
            .isEqualTo(BarcodeDecodeResult.NoBarcodeFound)
    }

    @Test
    fun eccExceededSymbolReturnsNoBarcodeFound() {
        // A genuine, locatable QR whose data modules are corrupted past its error-correction budget.
        // Through the production MultiFormatReader path (decodeYPlane), this surfaces as
        // NoBarcodeFound -- a corrupt symbol must NEVER yield a fabricated payload.
        val plane = eccExceededQrPlane()

        assertThat(decodeYPlane(plane.bytes, plane.width, plane.height, rowStride = plane.width))
            .isEqualTo(BarcodeDecodeResult.NoBarcodeFound)
    }

    @Test
    fun eccExceededSymbolIsLocatedButUndecodable() {
        // Pins WHY the previous case is the reader-exception class and not merely an absent symbol:
        // a bare single-format QRCodeReader on the identical luminance LOCATES the symbol and throws
        // a non-NotFound ReaderException (checksum/format). MultiFormatReader collapses every such
        // sub-reader ReaderException back into NotFoundException, which is why decodeLuminance reports
        // the same NoBarcodeFound for "located but undecodable" as for "nothing there".
        val plane = eccExceededQrPlane()
        val bitmap = BinaryBitmap(HybridBinarizer(planarSource(plane)))

        val thrown =
            assertThrows(ReaderException::class.java) {
                QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true))
            }
        assertThat(thrown).isNotInstanceOf(NotFoundException::class.java)
    }

    // --- Allowlist (non-roster symbologies rejected) --------------------------------------------

    @Test
    fun pdf417IsNotDecodedThroughYPlane() {
        // PDF417 is intentionally absent from the v1 roster, so the pinned POSSIBLE_FORMATS hints
        // never run a PDF417 decoder: a genuine PDF417 symbol reads as no locatable barcode.
        assertThat(decodeNonRoster("BOARDING-PASS-PAYLOAD", BarcodeFormat.PDF_417))
            .isEqualTo(BarcodeDecodeResult.NoBarcodeFound)
    }

    @Test
    fun aztecIsNotDecodedThroughYPlane() {
        assertThat(decodeNonRoster("BOARDING-PASS-PAYLOAD", BarcodeFormat.AZTEC))
            .isEqualTo(BarcodeDecodeResult.NoBarcodeFound)
    }

    // --- Geometry preconditions (caller-wiring guards) ------------------------------------------

    @Test
    fun nonPositiveWidthThrows() {
        val bytes = ByteArray(16)
        assertThrows(IllegalArgumentException::class.java) {
            decodeYPlane(bytes, width = 0, height = 4, rowStride = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeYPlane(bytes, width = -1, height = 4, rowStride = 4)
        }
    }

    @Test
    fun nonPositiveHeightThrows() {
        val bytes = ByteArray(16)
        assertThrows(IllegalArgumentException::class.java) {
            decodeYPlane(bytes, width = 4, height = 0, rowStride = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeYPlane(bytes, width = 4, height = -1, rowStride = 4)
        }
    }

    @Test
    fun pixelStrideBelowOneThrows() {
        val bytes = ByteArray(16)
        assertThrows(IllegalArgumentException::class.java) {
            decodeYPlane(bytes, width = 4, height = 4, rowStride = 4, pixelStride = 0)
        }
    }

    @Test
    fun rowStrideSmallerThanRowSpanThrows() {
        // rowStride must cover width * pixelStride; one short feeds the binarizer overlapping rows.
        val bytes = ByteArray(256)
        assertThrows(IllegalArgumentException::class.java) {
            decodeYPlane(bytes, width = 10, height = 10, rowStride = 9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeYPlane(bytes, width = 10, height = 10, rowStride = 19, pixelStride = 2)
        }
    }

    @Test
    fun undersizedBufferThrows() {
        // maxIndex = (h-1)*rowStride + (w-1)*pixelStride; the buffer must hold maxIndex + 1 bytes.
        // 4x4 tight => maxIndex = 3*4 + 3 = 15 => minimum 16 bytes; 15 is one short.
        val tooSmall = ByteArray(15)
        assertThrows(IllegalArgumentException::class.java) {
            decodeYPlane(tooSmall, width = 4, height = 4, rowStride = 4)
        }
    }

    @Test
    fun bufferExactlyLargeEnoughIsAccepted() {
        // The matching upper boundary: a buffer of exactly maxIndex + 1 bytes clears the guard and
        // decodes (a blank 4x4 plane has no symbol, so NoBarcodeFound -- not an IllegalArgumentException).
        val exact = ByteArray(16) { 0xFF.toByte() }

        assertThat(decodeYPlane(exact, width = 4, height = 4, rowStride = 4))
            .isEqualTo(BarcodeDecodeResult.NoBarcodeFound)
    }

    // --- Fixtures -------------------------------------------------------------------------------

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

    /**
     * Encode [content] into a [pixelStride]-interleaved plane (real Y at every [pixelStride]-th byte)
     * with [rowPadding] extra bytes per row, then decode it back through [decodeYPlane].
     */
    private fun decodeInterleaved(
        content: String,
        format: BarcodeFormat,
        pixelStride: Int,
        rowPadding: Int,
    ): BarcodeDecodeResult {
        val tight = encodeYPlane(content, format, 320, 320, rowPadding = 0)
        val rowStride = tight.width * pixelStride + rowPadding
        val interleaved = ByteArray(rowStride * tight.height)
        for (row in 0 until tight.height) {
            for (col in 0 until tight.width) {
                interleaved[row * rowStride + col * pixelStride] = tight.bytes[row * tight.width + col]
            }
        }
        return decodeYPlane(interleaved, tight.width, tight.height, rowStride, pixelStride)
    }

    /** Render a non-roster [format] symbol into a tight luminance plane and decode it. */
    private fun decodeNonRoster(
        content: String,
        format: BarcodeFormat,
    ): BarcodeDecodeResult {
        val plane = encodeYPlane(content, format, 0, 0, rowPadding = 0)
        return decodeYPlane(plane.bytes, plane.width, plane.height, rowStride = plane.width)
    }

    private fun planarSource(plane: YPlane): PlanarYUVLuminanceSource =
        PlanarYUVLuminanceSource(plane.bytes, plane.width, plane.height, 0, 0, plane.width, plane.height, false)

    /**
     * A real version-4-L QR ("located") whose data modules are flipped past its error-correction
     * budget ("undecodable"). Finder, separator, timing, format and the version-4 alignment patterns
     * are preserved so the symbol still locates; [FLIP_COUNT] scattered data flips then exceed ECC.
     * Scaled up so the binarizer samples clean module blocks.
     */
    private fun eccExceededQrPlane(): YPlane {
        val matrix =
            MultiFormatWriter().encode(
                "WALT-LIVE-7-".repeat(8),
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(
                    EncodeHintType.MARGIN to 0,
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                ),
            )
        val w = matrix.width
        val h = matrix.height
        val flipped = scatteredDataFlips(w, h)
        val scale = 6
        val sw = w * scale
        val sh = h * scale
        val bytes = ByteArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val mx = x / scale
                val my = y / scale
                val black = matrix.get(mx, my) xor (my * w + mx in flipped)
                bytes[y * sw + x] = if (black) BLACK else WHITE
            }
        }
        return YPlane(bytes, sw, sh)
    }

    /**
     * [FLIP_COUNT] data-module indices, strided so they scatter spatially instead of clustering into
     * a block (a solid block defeats the locator before ECC ever runs). Finder/separator/format,
     * timing, and the version-4 alignment patterns are excluded so the symbol stays locatable.
     */
    private fun scatteredDataFlips(
        w: Int,
        h: Int,
    ): Set<Int> {
        val dataModules = ArrayList<Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!isStructuralModule(x, y, w, h)) dataModules.add(y * w + x)
            }
        }
        val flipped = HashSet<Int>()
        for (idx in 0 until FLIP_COUNT) {
            flipped.add(dataModules[idx * FLIP_STRIDE % dataModules.size])
        }
        return flipped
    }

    /** True for the version-4 function patterns that must survive corruption for the symbol to locate. */
    private fun isStructuralModule(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ): Boolean =
        x <= 8 && y <= 8 || // top-left finder + separator + format
            x >= w - 9 && y <= 8 || // top-right finder
            x <= 8 && y >= h - 9 || // bottom-left finder
            x == 6 || y == 6 || // timing patterns
            x in 24..28 && y in 24..28 // version-4 alignment pattern

    private companion object {
        const val BLACK = 0x00.toByte()
        const val WHITE = 0xFF.toByte()

        // 20 scattered flips reliably exceed version-4-L ECC while keeping the symbol locatable
        // (verified: bare QRCodeReader -> ChecksumException; MultiFormatReader -> NotFound).
        const val FLIP_COUNT = 20
        const val FLIP_STRIDE = 7
    }
}
