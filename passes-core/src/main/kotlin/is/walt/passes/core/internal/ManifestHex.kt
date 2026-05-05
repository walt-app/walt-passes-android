package `is`.walt.passes.core.internal

/**
 * Hex-decoding helpers shared by [verifyManifest]. PKPASS manifest hashes are
 * always lowercase in well-formed archives, but tolerating mixed case is cheap and
 * matches what every other PKPASS reader in the wild does. Returns `null` (not an
 * exception) on bad input so the caller can map the failure to
 * [ManifestFailure.InvalidHashFormat] without a try/catch.
 */
internal fun decodeSha1HexOrNull(hex: String): ByteArray? {
    if (hex.length != SHA1_HEX_LENGTH) return null
    return decodeHexBytesOrNull(hex)
}

private fun decodeHexBytesOrNull(hex: String): ByteArray? {
    val out = ByteArray(SHA1_BYTE_LENGTH)
    for (i in 0 until SHA1_BYTE_LENGTH) {
        val hi = hexDigitValue(hex[2 * i])
        val lo = hexDigitValue(hex[2 * i + 1])
        if (hi < 0 || lo < 0) return null
        out[i] = (hi shl HEX_NIBBLE_SHIFT or lo).toByte()
    }
    return out
}

private fun hexDigitValue(c: Char): Int =
    when (c) {
        in '0'..'9' -> c.code - '0'.code
        in 'a'..'f' -> c.code - 'a'.code + HEX_LETTER_OFFSET
        in 'A'..'F' -> c.code - 'A'.code + HEX_LETTER_OFFSET
        else -> -1
    }

internal const val SHA1_BYTE_LENGTH: Int = 20
internal const val SHA1_HEX_LENGTH: Int = SHA1_BYTE_LENGTH * 2
private const val HEX_NIBBLE_SHIFT = 4
private const val HEX_LETTER_OFFSET = 10
