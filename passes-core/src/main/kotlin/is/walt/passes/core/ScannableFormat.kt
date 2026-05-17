package `is`.walt.passes.core

/**
 * The barcode formats a [ScannableCard] may render. The v1 roster covers the long tail of
 * physical-world cards real users actually hold:
 *
 *  - [Code128] — most modern membership/loyalty cards (alphanumeric, variable length)
 *  - [Ean13] — European retail barcodes (13 numeric digits)
 *  - [UpcA] — North American retail barcodes (12 numeric digits)
 *  - [Code39] — older institutional cards (alphanumeric, fixed charset)
 *  - [Qr] — modern QR-based loyalty / event / payment cards
 *
 * Pdf417 and Aztec are intentionally absent from v1: they are largely vendor-issued
 * (boarding passes, transit) and arrive via PKPASS already.
 *
 * Distinct type from [BarcodeFormat] (the PKPASS-pass barcode enum). The two are
 * deliberately not unified — a verified PKPASS barcode and a user-typed card barcode are
 * different trust artifacts that happen to share a rendering technology. Casing also
 * differs (`Qr` here vs `QR` there): this enum follows Kotlin's PascalCase enum
 * convention; the PKPASS one predates the convention switch in this repo.
 */
public enum class ScannableFormat {
    Code128,
    Ean13,
    UpcA,
    Code39,
    Qr,
}
