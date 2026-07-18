package `is`.walt.passes.document.ui

import android.os.ParcelFileDescriptor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.document.DocumentRejectedKind
import `is`.walt.passes.document.PdfDocument
import `is`.walt.passes.document.PdfDocumentId
import `is`.walt.passes.document.ui.theme.DocumentSemantics
import `is`.walt.passes.document.ui.theme.DocumentTheme
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.ProbeResult
import `is`.walt.passes.pdf.android.RenderResult
import `is`.walt.passes.pdf.android.RenderSourceRect
import `is`.walt.passes.ui.core.ArgbColor
import java.io.File
import java.util.Collections
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pins the pager's adjacent-page prefetch (wpass-tjc.3): the PDF arm composes the
 * next page beyond the viewport (`beyondViewportPageCount = 1`), so its render is
 * requested while page 0 is on screen — the peek/next swipe reveals a ready page.
 * Equally load-bearing: pages past the window are NOT requested, pinning the
 * "never the whole document rasterised" claim.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DocumentViewPagerPrefetchTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val semantics = DocumentSemantics(
        captionBackground = ArgbColor(0xFF202020.toInt()),
        captionForeground = ArgbColor(0xFFFFFFFF.toInt()),
        tileBackground = ArgbColor(0xFFF5F5F5.toInt()),
        tileForeground = ArgbColor(0xFF202020.toInt()),
        tileLabelForeground = ArgbColor(0xFF606060.toInt()),
        laneBackground = ArgbColor(0xFFEEEEEE.toInt()),
        documentBadgeBackground = ArgbColor(0xFFD0D0D0.toInt()),
        documentBadgeForeground = ArgbColor(0xFF202020.toInt()),
    )

    @Test
    fun pdfPagerRequestsCurrentAndAdjacentPageButNotTheRest() {
        val requestedPages = Collections.synchronizedSet(mutableSetOf<Int>())
        val recordingRenderer = object : PdfRendererBinder {
            override suspend fun probe(pdf: ParcelFileDescriptor): ProbeResult =
                ProbeResult.Ok(pageCount = 5)

            override suspend fun render(
                pdf: ParcelFileDescriptor,
                page: Int,
                widthPx: Int,
                heightPx: Int,
                sourceRect: RenderSourceRect,
            ): RenderResult {
                requestedPages += page
                // Rejected keeps the test free of SharedMemory plumbing; the page
                // request itself is the behavior under test.
                return RenderResult.Rejected(DocumentRejectedKind.RendererFailed)
            }
        }
        val doc = PdfDocument(
            id = PdfDocumentId("doc-1"),
            displayLabel = "doc.pdf",
            byteCount = 1024L,
            pageCount = 5,
            importedAtEpochMs = 0L,
        )
        val file = File.createTempFile("doc", ".pdf").apply { writeBytes(byteArrayOf(0x25)) }
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            composeRule.setContent {
                ThemedHost {
                    DocumentView(doc = doc, pdfFile = pfd, renderer = recordingRenderer)
                }
            }
            composeRule.waitForIdle()

            assertThat(requestedPages).containsExactly(0, 1)
        } finally {
            pfd.close()
            file.delete()
        }
    }

    @Composable
    private fun ThemedHost(content: @Composable () -> Unit) {
        MaterialTheme {
            DocumentTheme(semantics = semantics, content = content)
        }
    }
}
