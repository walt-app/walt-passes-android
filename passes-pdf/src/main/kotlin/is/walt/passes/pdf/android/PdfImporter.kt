package `is`.walt.passes.pdf.android

import android.content.Context
import `is`.walt.passes.pdf.PdfImportConfig
import `is`.walt.passes.pdf.PdfImportResult

/**
 * The single import entry point that ADR 0005 G.1 calls for. Owns the trust-claim-bearing
 * orchestration that the consumer (walt-android) would otherwise have to assemble itself:
 *
 *  1. Runtime API-34 gate (G.1) — fires *before* any source bytes are read.
 *  2. Memfd-backed materialization with a fail-fast size cap (D7, F.1).
 *  3. Magic-byte header sniff before the renderer process is bound (D4 cover).
 *  4. Isolated-process renderer bind (D3) → probe → page-zero render.
 *  5. Storage handoff via a caller-supplied `persist` lambda.
 *  6. Telemetry start / success / failure with enums-and-durations only.
 *
 * Composing those by hand in walt-android would be the parallel-implementation
 * pattern the repository's "DECISIVE CONSTRAINT" forbids: trust claims must live in
 * this repository, not be reassembled by the consumer. [PdfImporter] is the seam that
 * keeps that invariant honest — every import goes through this orchestration, and
 * every step here is independently surface-locked by tests in this module.
 *
 * Storage is wired through a callback rather than a `PassRepository` dependency so the
 * `passes-pdf` and `passes-storage` modules remain independent peers per the
 * project's module rules. The consumer's Hilt graph supplies a lambda that calls
 * `PassRepository.insertDocument`; the importer itself stays storage-agnostic and
 * remains testable without spinning up SQLCipher.
 */
public interface PdfImporter {
    /**
     * Run the import sequence end-to-end. Returns [PdfImportResult.Imported] on success,
     * or [PdfImportResult.Rejected] folded onto the [is.walt.passes.pdf.DocumentRejectedKind]
     * enum at the first failing step. The renderer service is unbound before this method
     * returns regardless of outcome.
     *
     * [persist] is invoked exactly once on the success path, after the page-zero render
     * succeeds and before the [PdfImportResult.Imported] arm is constructed. It is never
     * invoked on a rejection. If [persist] throws, the import returns
     * [is.walt.passes.pdf.DocumentRejectedKind.RendererFailed] (the persist call sits at
     * the same trust band as the renderer handoff — a thrown exception there is a
     * downstream infrastructure failure the importer cannot recover from), and telemetry
     * fires `onImportFailed`.
     *
     * [displayLabel] is supplied by the consumer; the importer does not derive it from
     * the source, because the source's metadata is part of the no-extraction-from-content
     * discipline (ADR 0005 D4). The label is forwarded verbatim to [persist].
     */
    public suspend fun import(
        source: PdfImportSource,
        displayLabel: String,
        persist: suspend (label: String, pdfBytes: ByteArray, pageCount: Int, thumbnailBytes: ByteArray) -> Unit,
    ): PdfImportResult

    public companion object {
        public fun create(
            context: Context,
            config: PdfImportConfig = PdfImportConfig(),
        ): PdfImporter = DefaultPdfImporter(context, config)
    }
}
