package `is`.walt.passes.image

/**
 * The outcome of attempting to import an image. Modelled as a sealed interface so the
 * consumer gets compile-time exhaustiveness when branching on import results, mirroring
 * the `ParseResult` shape in `passes-core` and `PdfImportResult` in `passes-pdf-core`.
 *
 * There is intentionally no `Tampered` arm: images are not signature-verified, so
 * "tampered" is not a category Walt can detect or report.
 */
public sealed interface ImageImportResult {
    public data class Imported(public val image: ImageDocument) : ImageImportResult

    public data class Rejected(public val kind: ImageRejectedKind) : ImageImportResult
}
