package `is`.walt.passes.image

/**
 * The image formats accepted at import. Each arm corresponds to a distinct magic-byte
 * sequence that [sniffImageFormat] recognises:
 *
 *  - [Png] — `89 50 4E 47 0D 0A 1A 0A` (8 bytes, ISO 15948).
 *  - [Jpeg] — `FF D8 FF` (3-byte SOI + marker prefix, ITU T.81 / ISO 10918-1).
 *  - [WebP] — `52 49 46 46 ?? ?? ?? ?? 57 45 42 50` ("RIFF....WEBP", 12 bytes, Google WebP).
 *
 * HEIF / HEIC is intentionally absent: its ftyp-box detection requires reading further
 * into the file than a fixed-offset magic check, and its decoder requires API 28+.
 * Accepting only the three formats above covers the overwhelming majority of
 * user-supplied images (screenshots, photos, scans) without the parser complexity.
 */
public enum class ImageFormat {
    Png,
    Jpeg,
    WebP,
}
