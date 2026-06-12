package `is`.walt.passes.image

/**
 * Opaque identifier for a stored [ImageDocument]. Wrapped in a value class so calling
 * code cannot accidentally substitute a [String] from another domain (a pass id, a
 * filename, a user input) into APIs that expect an image document id.
 */
@JvmInline
public value class ImageDocumentId(public val value: String)

/**
 * The pure-Kotlin model for a successfully-imported image. Mirrors the role [is.walt.passes.pdf.PdfDocument]
 * plays in `passes-pdf-core` for PDF files, but is a *sibling* concept — `ImageDocument`,
 * `PdfDocument`, and `Pass` share no superclass. Images are not signature-verified; their
 * trust caption is sourced from [provenance], which has a single arm by design.
 *
 * The displayed [displayLabel] is supplied at import time by the consumer; the model
 * layer never derives it from image metadata (EXIF, XMP, etc.), because metadata
 * extraction is part of the no-extraction-from-content discipline. Callers should pass
 * a filename if they have one and a date-based fallback ("Image, added <date>") otherwise.
 *
 * [widthPx] and [heightPx] are the intrinsic pixel dimensions of the source image as
 * reported by the decoder at import time. They are stored so the UI can compute aspect
 * ratio without re-decoding.
 */
public data class ImageDocument(
    public val id: ImageDocumentId,
    public val displayLabel: String,
    public val byteCount: Long,
    public val format: ImageFormat,
    public val widthPx: Int,
    public val heightPx: Int,
    public val importedAtEpochMs: Long,
    public val provenance: Provenance = Provenance.UserProvided,
)

/**
 * Where an [ImageDocument] came from. Single arm by design: the only legitimate source
 * today is the user importing a file from their device. The arm exists not because there
 * are alternatives but because *not having* this enum would let a future contributor add
 * a silent "downloaded by Walt" provenance without a code-review trail.
 *
 * The presence of this enum also signals the policy: images are NEVER signature-verified.
 * There is no `SignatureStatus` analogue for image documents, by design. Adding a second
 * arm here is a security-policy change requiring re-review.
 */
public enum class Provenance {
    UserProvided,
}
