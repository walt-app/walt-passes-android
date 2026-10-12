package `is`.walt.passes.document.ui

import android.os.ParcelFileDescriptor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import `is`.walt.passes.document.DocumentRejectedKind
import `is`.walt.passes.document.ui.theme.DocumentSemantics
import `is`.walt.passes.document.ui.theme.DocumentTheme
import `is`.walt.passes.image.android.ImageDecodeBinder
import `is`.walt.passes.image.android.ImageDecodeRejectedKind
import `is`.walt.passes.image.android.ImageDecodeResult
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.ProbeResult
import `is`.walt.passes.pdf.android.RenderResult
import `is`.walt.passes.pdf.android.RenderSourceRect
import `is`.walt.passes.ui.core.ArgbColor
import java.util.Collections

/** Placeholder semantics shared by the document surface tests; override a slot with `copy`. */
internal val testDocumentSemantics = DocumentSemantics(
    captionBackground = ArgbColor(0xFF202020.toInt()),
    captionForeground = ArgbColor(0xFFFFFFFF.toInt()),
    tileBackground = ArgbColor(0xFFF5F5F5.toInt()),
    tileForeground = ArgbColor(0xFF202020.toInt()),
    tileLabelForeground = ArgbColor(0xFF606060.toInt()),
    laneBackground = ArgbColor(0xFFEEEEEE.toInt()),
    documentBadgeBackground = ArgbColor(0xFFD0D0D0.toInt()),
    documentBadgeForeground = ArgbColor(0xFF202020.toInt()),
)

@Composable
internal fun ThemedHost(
    semantics: DocumentSemantics = testDocumentSemantics,
    content: @Composable () -> Unit,
) {
    MaterialTheme {
        DocumentTheme(semantics = semantics, content = content)
    }
}

/** One call into the isolated renderer. */
internal data class RenderRequest(
    val page: Int,
    val widthPx: Int,
    val heightPx: Int,
    val sourceRect: RenderSourceRect,
)

/**
 * Probes as [pageCount] pages and records every render request in call order. Rejects each
 * render, which keeps the tests free of SharedMemory plumbing that Robolectric cannot round-trip.
 */
internal class RecordingRenderer(private val pageCount: Int) : PdfRendererBinder {
    private val seen = Collections.synchronizedList(mutableListOf<RenderRequest>())

    override suspend fun probe(pdf: ParcelFileDescriptor): ProbeResult = ProbeResult.Ok(pageCount = pageCount)

    override suspend fun render(
        pdf: ParcelFileDescriptor,
        page: Int,
        widthPx: Int,
        heightPx: Int,
        sourceRect: RenderSourceRect,
    ): RenderResult {
        seen += RenderRequest(page, widthPx, heightPx, sourceRect)
        return RenderResult.Rejected(DocumentRejectedKind.RendererFailed)
    }

    fun requests(): List<RenderRequest> = synchronized(seen) { seen.toList() }

    fun pages(): List<Int> = requests().map { it.page }
}

internal object RejectingImageDecoder : ImageDecodeBinder {
    override suspend fun decode(
        image: ParcelFileDescriptor,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): ImageDecodeResult = ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecodeFailed)
}
