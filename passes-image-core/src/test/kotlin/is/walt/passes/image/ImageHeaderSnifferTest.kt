package `is`.walt.passes.image

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageHeaderSnifferTest {

    // -- PNG ------------------------------------------------------------------

    @Test
    fun acceptsValidPngHeader() {
        assertThat(sniffImageFormat(PNG_MAGIC)).isEqualTo(ImageFormat.Png)
    }

    @Test
    fun acceptsPngWithTrailingData() {
        val bytes = PNG_MAGIC + byteArrayOf(0x00, 0x00, 0x00, 0x0D, 'I'.code.toByte())
        assertThat(sniffImageFormat(bytes)).isEqualTo(ImageFormat.Png)
    }

    @Test
    fun rejectsPngTruncatedToSevenBytes() {
        assertThat(sniffImageFormat(PNG_MAGIC.copyOf(7))).isNull()
    }

    @Test
    fun rejectsPngWithWrongFifthByte() {
        val bad = PNG_MAGIC.copyOf()
        bad[4] = 0x00
        assertThat(sniffImageFormat(bad)).isNull()
    }

    // -- JPEG -----------------------------------------------------------------

    @Test
    fun acceptsJfifJpegHeader() {
        val jfif = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        assertThat(sniffImageFormat(jfif)).isEqualTo(ImageFormat.Jpeg)
    }

    @Test
    fun acceptsExifJpegHeader() {
        val exif = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x00, 0x18)
        assertThat(sniffImageFormat(exif)).isEqualTo(ImageFormat.Jpeg)
    }

    @Test
    fun acceptsJpegWithExactlyThreeBytes() {
        assertThat(sniffImageFormat(JPEG_SOI)).isEqualTo(ImageFormat.Jpeg)
    }

    @Test
    fun rejectsJpegTruncatedToTwoBytes() {
        assertThat(sniffImageFormat(JPEG_SOI.copyOf(2))).isNull()
    }

    @Test
    fun rejectsJpegWithWrongThirdByte() {
        val bad = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00.toByte())
        assertThat(sniffImageFormat(bad)).isNull()
    }

    // -- WebP -----------------------------------------------------------------

    @Test
    fun acceptsValidWebPHeader() {
        assertThat(sniffImageFormat(WEBP_MAGIC)).isEqualTo(ImageFormat.WebP)
    }

    @Test
    fun acceptsWebPWithArbitraryChunkSizeBytes() {
        val bytes = WEBP_MAGIC.copyOf()
        // bytes 4-7 are the RIFF chunk size — should be ignored by the sniffer
        bytes[4] = 0xAB.toByte()
        bytes[5] = 0xCD.toByte()
        bytes[6] = 0xEF.toByte()
        bytes[7] = 0x01.toByte()
        assertThat(sniffImageFormat(bytes)).isEqualTo(ImageFormat.WebP)
    }

    @Test
    fun rejectsWebPTruncatedToElevenBytes() {
        assertThat(sniffImageFormat(WEBP_MAGIC.copyOf(11))).isNull()
    }

    @Test
    fun rejectsRiffWithoutWebPFourCC() {
        val riffNotWebP = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // RIFF
            0x00, 0x00, 0x00, 0x00, // size
            0x41, 0x56, 0x49, 0x20, // AVI  (not WEBP)
        )
        assertThat(sniffImageFormat(riffNotWebP)).isNull()
    }

    // -- Cross-format rejection -----------------------------------------------

    @Test
    fun rejectsPdfMagic() {
        assertThat(sniffImageFormat("%PDF-1.7\n".toByteArray(Charsets.US_ASCII))).isNull()
    }

    @Test
    fun rejectsZipMagic() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00)
        assertThat(sniffImageFormat(zip)).isNull()
    }

    @Test
    fun rejectsEmptyArray() {
        assertThat(sniffImageFormat(byteArrayOf())).isNull()
    }

    @Test
    fun rejectsSingleByte() {
        assertThat(sniffImageFormat(byteArrayOf(0xFF.toByte()))).isNull()
    }

    @Test
    fun rejectsAllZeroes() {
        assertThat(sniffImageFormat(ByteArray(16))).isNull()
    }

    // -- JPEG bytes rejected as PNG / WebP ------------------------------------

    @Test
    fun jpegBytesAreNotDetectedAsPng() {
        assertThat(sniffImageFormat(JPEG_SOI)).isNotEqualTo(ImageFormat.Png)
    }

    @Test
    fun pngBytesAreNotDetectedAsJpeg() {
        assertThat(sniffImageFormat(PNG_MAGIC)).isNotEqualTo(ImageFormat.Jpeg)
    }

    private companion object {
        val PNG_MAGIC = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A,
        )
        val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val WEBP_MAGIC = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // RIFF
            0x24, 0x00, 0x00, 0x00, // chunk size (arbitrary)
            0x57, 0x45, 0x42, 0x50, // WEBP
        )
    }
}
