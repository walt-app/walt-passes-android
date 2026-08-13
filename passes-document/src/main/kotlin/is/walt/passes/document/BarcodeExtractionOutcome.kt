package `is`.walt.passes.document

import `is`.walt.passes.core.DecodeFailureReason

/**
 * Why an imported image is a plain image rather than a composite (wpass-pl7.5). Carried on
 * [DocumentPersist.Image] so the consumer's confirm sheet can say what actually happened instead
 * of rendering "no code found" for every non-composite outcome.
 *
 * The arms are the four distinguishable ways the composite path declines to produce a barcode,
 * and they call for different copy AND different affordances: [Failed] with
 * [DecodeFailureReason.DecodeTimedOut] is a load signal that a user-initiated retry may clear,
 * [DecodeFailureReason.ImageTooLarge] never will, [NoCodeFound] means the sandbox read the image
 * fine, and [Declined] means the user already rejected the read. Collapsing them to a single null
 * hid a watchdog kill behind "no code found" — the failure mode the wpass-pl7.2 retry ladder is
 * most likely to introduce.
 *
 * NOTHING here carries the decoded payload or any image bytes: a BCBP boarding pass payload
 * carries passenger name and PNR, and the composite arm ([DocumentPersist.BarcodedImage]) is the
 * only place a payload crosses this seam — and only after the user has confirmed it.
 */
public sealed interface BarcodeExtractionOutcome {
    /**
     * The caller did not opt into composites (no `confirmBarcode` hook), so no isolated decode
     * ran. Distinct from [NoCodeFound]: nothing looked at the image, so the absence of a code is
     * unknown, not observed.
     */
    public data object NotAttempted : BarcodeExtractionOutcome

    /** The isolated decode completed and located no barcode in the image. */
    public data object NoCodeFound : BarcodeExtractionOutcome

    /** The isolated decode could not complete; [reason] is the decoder's own bucket, verbatim. */
    public data class Failed(public val reason: DecodeFailureReason) : BarcodeExtractionOutcome

    /**
     * A code was decoded and shown to the user, who declined it at the confirm gate (or the
     * consumer's confirm hook threw, which is treated as a decline so a confirm-UI bug cannot fail
     * the import). The payload is deliberately not carried: the user has just rejected it.
     */
    public data object Declined : BarcodeExtractionOutcome
}
