package `is`.walt.passes.document

import `is`.walt.passes.core.ScannableFormat

/**
 * Opaque identifier for a stored [BarcodedImageDocument]. A distinct [DocumentId] arm — sibling
 * to [ImageDocumentId] and [PdfDocumentId] — so a composite id cannot be substituted for a
 * plain-image id (or vice versa) in APIs that expect one specific kind.
 */
@JvmInline
public value class BarcodedImageDocumentId(public override val value: String) : DocumentId

/**
 * The pure-Kotlin model for a composite artifact: a stored still image PLUS a barcode that was
 * extracted from that image at import time (wpass-8lu). The THIRD [Document] arm, and a strict
 * superset of [ImageDocument] — every image field is here, plus the decoded [barcodePayload] and
 * its [barcodeFormat]. It is one artifact id rendering as one wallet row, never a host-side join
 * of an image entity and a card entity.
 *
 * Relationship to the other arms:
 *
 *  - The image half ([widthPx] / [heightPx] / persisted original bytes) is identical to
 *    [ImageDocument]; the display surface renders it through the SAME isolated image-decode
 *    sandbox (`passes-image`). The dimensions are the bounded raster Walt decoded in the
 *    sandbox, never an in-process decode of the untrusted source bytes.
 *  - The barcode half ([barcodePayload] / [barcodeFormat]) was produced by the isolated
 *    still-image barcode decoder (`passes-barcode`): the original image bytes were decoded
 *    in-sandbox and only the payload + [ScannableFormat] crossed the binder, matching the
 *    wpass-i9x isolation invariant. The host process never ran a codec over the source bytes.
 *
 * When an imported image yields NO barcode, the importer degrades to a plain [ImageDocument]
 * rather than producing a [BarcodedImageDocument] with an empty payload — the composite arm
 * exists only when a code was actually found and (consumer-side) confirmed.
 *
 * [barcodePayload] is the verbatim decoded symbol contents; the consumer re-encodes it across
 * symbologies with `passes-core`'s `BarcodeEncoder` for the detail-surface format switcher, so a
 * single stored payload backs every rendered symbology. [barcodeFormat] is the symbology the
 * code was originally detected as — the switcher's initial selection.
 *
 * [displayLabel] is supplied at import time by the consumer; the model never derives it from
 * image metadata (EXIF / XMP) or from the barcode payload, part of the
 * no-extraction-from-content discipline (D4).
 */
public data class BarcodedImageDocument(
    public override val id: BarcodedImageDocumentId,
    public override val displayLabel: String,
    public override val byteCount: Long,
    public val widthPx: Int,
    public val heightPx: Int,
    public val barcodePayload: String,
    public val barcodeFormat: ScannableFormat,
    public override val importedAtEpochMs: Long,
    public override val provenance: Provenance = Provenance.UserProvided,
) : Document
