package `is`.walt.passes.document

/**
 * The image container formats the importer recognises at sniff time. Each arm corresponds to
 * a distinct magic-byte sequence that [sniffImageFormat] matches:
 *
 *  - [Png] — `89 50 4E 47 0D 0A 1A 0A` (8 bytes, ISO 15948).
 *  - [Jpeg] — `FF D8 FF` (3-byte SOI + marker prefix, ITU T.81 / ISO 10918-1).
 *  - [WebP] — `52 49 46 46 ?? ?? ?? ?? 57 45 42 50` ("RIFF....WEBP", 12 bytes, Google WebP).
 *
 * Salvaged from the closed PR #146 `passes-image-core` proposal and re-homed here: the sniff
 * is the importer's first job, and this module is where PDF-vs-image branching lives. HEIF /
 * HEIC is intentionally absent — its `ftyp`-box detection needs to read further than a
 * fixed-offset magic check, and the three formats here cover the overwhelming majority of
 * user-supplied images (screenshots, photos, scans). The bounded decoder downstream
 * (`passes-image`) accepts a wider MIME allowlist; this sniff is the narrower import gate.
 */
public enum class ImageFormat {
    Png,
    Jpeg,
    WebP,
}
