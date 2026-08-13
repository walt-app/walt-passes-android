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
 * Every member both decodes and encodes. The error-correction, compaction and quiet-zone
 * defaults each writer runs under are pinned and argued in `ZxingBarcodeEncoder`.
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
 * build can render one. True for every member today; it stays on the surface as the gate a
 * decode-first roster addition would need.
 *
 * **Consumers building a format picker must filter on this.** The picker cannot be derived from
 * [ScannableFormat.entries] alone: [ScannableCardInputValidator] refuses a non-creatable format
 * with [ScannableCardCreateResult.UnsupportedFormat], so an unfiltered picker offers a choice
 * whose Save fails on every tap. Exposed rather than left implicit because nothing about adding
 * a decode-only member breaks a consumer's build — the failure is silent by construction.
 *
 * Backed by the same set the validator reads, so the two cannot disagree.
 */
public fun ScannableFormat.isCreatable(): Boolean = this !in ScannableFormatConstraints.decodeOnly

/**
 * Whether a rendered symbol in this format can carry a payload a scanner acts on without
 * asking — a URL, `tel:`, `mailto:`. True for the byte-capable 2D symbologies; the 1D ones
 * hold membership numbers a scanner hands back as text.
 *
 * This is the format half of the C4 create-time confirmation gate in
 * `docs/SCANNABLE_CARD_THREAT_MODEL.md`; [QrPayloadClassifier] is the payload half. Gating on
 * `== Qr` instead would let an Aztec carrying a URL render as a scannable code having never
 * been confirmed.
 *
 * An exhaustive `when` rather than a set lookup on purpose: a new roster member is a decision
 * someone has to make here, surfaced as a compile error. Deliberately NOT folded into
 * [ScannableFormatConstraints]'s charset rule, which holds the same three formats today but
 * answers "does this symbology accept any character" — a question that will not stay in step.
 */
public fun ScannableFormat.canCarryActionablePayload(): Boolean =
    when (this) {
        ScannableFormat.Qr,
        ScannableFormat.Pdf417,
        ScannableFormat.Aztec,
        -> true
        ScannableFormat.Code128,
        ScannableFormat.Code39,
        ScannableFormat.Ean13,
        ScannableFormat.UpcA,
        -> false
    }
