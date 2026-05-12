package `is`.walt.passes.pdf.android.internal

import android.os.ParcelFileDescriptor
import `is`.walt.passes.pdf.DocumentRejectedKind
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.ProbeResult
import `is`.walt.passes.pdf.android.RenderResult

/**
 * A [PdfRendererBinder] that returns the same [DocumentRejectedKind] for every probe
 * and every render. Used by [RendererSession] callers to surface a connect-time failure
 * through the same probe/render shape as a real binder; the importer's existing
 * rejection routing then folds it onto [DocumentRejectedKind] without a separate
 * "session connect failed" code path.
 *
 * Lifted to its own type because the shape ("a binder that always rejects with X") is
 * plausibly useful for unit-test fakes too.
 */
internal class RejectingBinder(
    private val kind: DocumentRejectedKind,
) : PdfRendererBinder {
    override suspend fun probe(pdf: ParcelFileDescriptor): ProbeResult = ProbeResult.Rejected(kind)

    override suspend fun render(
        pdf: ParcelFileDescriptor,
        page: Int,
        widthPx: Int,
        heightPx: Int,
    ): RenderResult = RenderResult.Rejected(kind)
}
