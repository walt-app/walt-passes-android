package `is`.walt.passes.core

/**
 * The barcode formats a [ScannableCard] may render. The roster covers the long tail of
 * physical-world cards real users actually hold:
 *
 *  - [Code128] — most modern membership/loyalty cards (alphanumeric, variable length)
 *  - [Ean13] — European retail barcodes (13 numeric digits)
 *  - [UpcA] — North American retail barcodes (12 numeric digits)
 *  - [Code39] — older institutional cards (alphanumeric, fixed charset)
 *  - [Qr] — modern QR-based loyalty / event / payment cards
 *  - [Pdf417] — boarding passes, driver's licences, event tickets (stacked 2D)
 *  - [Aztec] — boarding passes (IATA BCBP) and transit tickets (square 2D)
 *
 * [Pdf417] and [Aztec] were absent from v1 on the assumption that vendor-issued codes arrive
 * via PKPASS. wpass-pl7 disproved it: users import boarding passes as SCREENSHOTS, which have
 * no PKPASS to arrive in, so the code was unreachable at any input scale.
 *
 * DataMatrix stays out deliberately — it widens the same surface (encoder, storage, consumer
 * format pickers) for no reported user need.
 *
 * **Read/write asymmetry (transitional).** Every member here decodes today; [Pdf417] and
 * [Aztec] do NOT yet encode — the writer arms, with their error-correction and compaction
 * defaults, land in wpass-pl7.6. Until then the encoder reports
 * [EncoderFailureReason.FormatNotEncodable] for those two.
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
    Pdf417,
    Aztec,
}

/**
 * Whether a user can create a [ScannableCard] in this format — which is to say whether this
 * build can render one. False only for the decode-only members ([Pdf417] / [Aztec] until
 * wpass-pl7.6 wires their writers).
 *
 * **Consumers building a format picker must filter on this.** The picker cannot be derived from
 * [ScannableFormat.entries] alone: [ScannableCardInputValidator] refuses a non-creatable format
 * with [ScannableCardCreateResult.UnsupportedFormat], so an unfiltered picker offers a choice
 * whose Save fails on every tap. Exposed rather than left implicit because nothing about adding
 * a decode-only member breaks a consumer's build — the failure is silent by construction.
 *
 * Backed by the same set the validator and encoder read, so the three cannot disagree.
 */
public fun ScannableFormat.isCreatable(): Boolean = this !in ScannableFormatConstraints.decodeOnly
