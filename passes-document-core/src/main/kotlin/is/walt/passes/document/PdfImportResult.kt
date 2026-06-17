package `is`.walt.passes.document

/**
 * The outcome of attempting to import a PDF. Modelled as a sealed interface so the consumer
 * gets compile-time exhaustiveness when branching on import results, mirroring the
 * `ParseResult` shape in `passes-core`.
 *
 * There is intentionally no `Tampered` arm here: PDFs are not signature-verified (ADR 0005
 * D5), so "tampered" is not a category Walt can detect or report. Consumers wanting to
 * communicate "this is just a file" should rely on the absence of a signature-status type
 * on [PdfDocument] rather than expecting an explicit Untrusted arm.
 */
public sealed interface PdfImportResult {
    public data class Imported(public val doc: PdfDocument) : PdfImportResult

    public data class Rejected(public val kind: DocumentRejectedKind) : PdfImportResult
}
