package `is`.walt.passes.document

/**
 * Header-sniff for the PDF magic. Returns true iff the supplied bytes begin with the
 * 8-byte sequence `%PDF-X.Y` where `X` is `1` or `2` and `Y` is any ASCII digit.
 *
 * This is the structural gate that runs *before* the renderer service is even handed the
 * input, so a MIME-spoofed file (ZIP, image, executable) can be rejected with
 * [DocumentRejectedKind.NotAPdf] without ever entering the decoder. We accept versions
 * 1.x (PDFs in the wild) and 2.x (the 2017+ ISO 32000-2 lineage) and reject everything
 * else — including PDFs with leading whitespace, which are explicitly out-of-spec at the
 * file-header level even though some forgiving parsers tolerate them.
 *
 * Anchoring to the very first byte (rather than searching the first 1024 bytes for the
 * marker, as some other tools do) is a *deliberate* deviation: the search-anchored
 * variant exists precisely because some older PDFs emit junk before the header, and
 * accepting that hands an attacker a place to hide a payload that the renderer might
 * still parse. We refuse the gain and keep the surface tight.
 */
public fun isPdfHeader(bytes: ByteArray): Boolean =
    bytes.size >= HEADER_LENGTH &&
        bytes[0] == '%'.code.toByte() &&
        bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'D'.code.toByte() &&
        bytes[3] == 'F'.code.toByte() &&
        bytes[4] == '-'.code.toByte() &&
        (bytes[5] == '1'.code.toByte() || bytes[5] == '2'.code.toByte()) &&
        bytes[6] == '.'.code.toByte() &&
        bytes[7] in '0'.code.toByte()..'9'.code.toByte()

private const val HEADER_LENGTH = 8
