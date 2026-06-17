package `is`.walt.passes.document

/**
 * Header-sniff for the three accepted image formats. Returns the detected [ImageFormat], or
 * `null` if the byte sequence matches no supported magic. Salvaged from PR #146's
 * `passes-image-core` sniffer and re-homed in the importer module; the sibling
 * [isPdfHeader][`is`.walt.passes.document.isPdfHeader] gate (in `passes-document-core`) is the PDF half
 * of the same branch.
 *
 * Each format is identified by its leading magic bytes:
 *
 *  - **PNG**: first 8 bytes are `89 50 4E 47 0D 0A 1A 0A` (the PNG signature).
 *  - **JPEG**: first 3 bytes are `FF D8 FF` (SOI marker + next-marker prefix). The fourth
 *    byte identifies the JFIF/EXIF/raw variant; it is intentionally not checked — any
 *    `FF D8 FF` start is a valid JPEG preamble.
 *  - **WebP**: bytes 0–3 are `52 49 46 46` ("RIFF") and bytes 8–11 are `57 45 42 50`
 *    ("WEBP"). Bytes 4–7 are the file-size field and are skipped. Requires ≥ 12 bytes.
 *
 * All checks are anchored to the very first byte. Searching for the magic elsewhere in the
 * buffer (as some lenient parsers do) would let an attacker prepend an arbitrary payload
 * before the image data; anchoring collapses that surface. This gate runs *before* any decode
 * work, so a MIME-spoofed file (PDF, ZIP, executable) is rejected at the importer without ever
 * reaching the decode sandbox.
 */
public fun sniffImageFormat(bytes: ByteArray): ImageFormat? =
    when {
        isPngHeader(bytes) -> ImageFormat.Png
        isJpegHeader(bytes) -> ImageFormat.Jpeg
        isWebPHeader(bytes) -> ImageFormat.WebP
        else -> null
    }

private fun isPngHeader(bytes: ByteArray): Boolean =
    bytes.size >= PNG_HEADER_LENGTH &&
        bytes[0] == 0x89.toByte() &&
        bytes[1] == 0x50.toByte() && // 'P'
        bytes[2] == 0x4E.toByte() && // 'N'
        bytes[3] == 0x47.toByte() && // 'G'
        bytes[4] == 0x0D.toByte() &&
        bytes[5] == 0x0A.toByte() &&
        bytes[6] == 0x1A.toByte() &&
        bytes[7] == 0x0A.toByte()

private fun isJpegHeader(bytes: ByteArray): Boolean =
    bytes.size >= JPEG_HEADER_LENGTH &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()

private fun isWebPHeader(bytes: ByteArray): Boolean =
    bytes.size >= WEBP_HEADER_LENGTH &&
        bytes[0] == 0x52.toByte() && // 'R'
        bytes[1] == 0x49.toByte() && // 'I'
        bytes[2] == 0x46.toByte() && // 'F'
        bytes[3] == 0x46.toByte() && // 'F'
        // bytes[4..7] are the RIFF chunk size — intentionally skipped
        bytes[8] == 0x57.toByte() && // 'W'
        bytes[9] == 0x45.toByte() && // 'E'
        bytes[10] == 0x42.toByte() && // 'B'
        bytes[11] == 0x50.toByte() // 'P'

private const val PNG_HEADER_LENGTH = 8
private const val JPEG_HEADER_LENGTH = 3
private const val WEBP_HEADER_LENGTH = 12
