package `is`.walt.passes.core.internal

/**
 * Decoded PNG canvas dimensions read from the IHDR chunk. Both fields are widened to
 * [Long] because PNG's IHDR encodes width and height as unsigned 32-bit integers; a
 * signed [Int] would mis-classify any value with the high bit set as negative and a
 * subsequent `width * height` would overflow before the resource-limit check ran.
 */
internal data class PngDimensions(val width: Long, val height: Long)

/**
 * Reads the IHDR chunk of a PNG to recover its declared canvas dimensions. Used only by
 * the image-pixel-count guard in the parser-glue layer: PKPASS images are PNG-only, and
 * the cap that matters there is "renderer memory after decompression," not the on-disk
 * byte size that [SafeArchiveExtractor] already bounds.
 *
 * Returns `null` for any input that does not begin with the PNG 8-byte signature
 * followed by an IHDR chunk type at the documented offset, or whose declared dimensions
 * are non-positive. The parser-glue layer treats `null` as "skip the pixel cap for this
 * entry" — a malformed PNG is upstream content the parser does not pretend to validate
 * past the resource-bound; the renderer will surface its own decode failure.
 *
 * The function is deliberately byte-level rather than reaching for a JCE/ImageIO decoder
 * for two reasons:
 *
 *  1. **No Android framework dependency.** `passes-core` is pure JVM and KMP-friendly;
 *     pulling `ImageIO` (server-side JDK only) or `BitmapFactory` (Android-only) would
 *     break that contract.
 *  2. **No decoder allocation.** A real decode would materialize the full pixel array
 *     into memory before the cap could fire — exactly the failure mode the cap exists
 *     to prevent. The IHDR-only read pulls 24 bytes regardless of declared dimensions.
 */
internal fun readPngDimensions(bytes: ByteArray): PngDimensions? {
    val structurallyAPng =
        bytes.size >= PNG_HEADER_PLUS_IHDR_LENGTH &&
            hasPngSignature(bytes) &&
            isIhdrChunkType(bytes)
    if (!structurallyAPng) return null
    val width = readU32BigEndian(bytes, IHDR_WIDTH_OFFSET)
    val height = readU32BigEndian(bytes, IHDR_HEIGHT_OFFSET)
    return if (width <= 0L || height <= 0L) null else PngDimensions(width, height)
}

private fun hasPngSignature(bytes: ByteArray): Boolean {
    for (i in PNG_SIGNATURE.indices) {
        if (bytes[i] != PNG_SIGNATURE[i]) return false
    }
    return true
}

private fun isIhdrChunkType(bytes: ByteArray): Boolean {
    return bytes[IHDR_TYPE_OFFSET] == 'I'.code.toByte() &&
        bytes[IHDR_TYPE_OFFSET + 1] == 'H'.code.toByte() &&
        bytes[IHDR_TYPE_OFFSET + 2] == 'D'.code.toByte() &&
        bytes[IHDR_TYPE_OFFSET + 3] == 'R'.code.toByte()
}

private fun readU32BigEndian(
    b: ByteArray,
    off: Int,
): Long {
    val b0 = b[off].toLong() and 0xFFL
    val b1 = b[off + 1].toLong() and 0xFFL
    val b2 = b[off + 2].toLong() and 0xFFL
    val b3 = b[off + 3].toLong() and 0xFFL
    return b0.shl(24) or b1.shl(16) or b2.shl(8) or b3
}

private val PNG_SIGNATURE: ByteArray =
    byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

private const val IHDR_TYPE_OFFSET = 12
private const val IHDR_WIDTH_OFFSET = 16
private const val IHDR_HEIGHT_OFFSET = 20
private const val PNG_HEADER_PLUS_IHDR_LENGTH = 24
