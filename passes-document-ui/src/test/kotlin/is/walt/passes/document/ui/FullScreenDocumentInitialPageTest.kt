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
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pins that the full-screen PDF pager opens on `initialPage`, so a host can carry the page
 * the reader was on into full screen. The full-screen pager composes no page beyond the
 * viewport, so the only page rendered is the one shown.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FullScreenDocumentInitialPageTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Closed in @After: the documented pdfFile contract is that the fd outlives the composition.
    private var pfd: ParcelFileDescriptor? = null
    private var file: File? = null

    @After
    fun tearDown() {
        pfd?.close()
        file?.delete()
    }

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
    fun opensOnTheRequestedPage() {
        assertThat(requestedPagesWhenOpenedAt(initialPage = 2)).containsExactly(2)
    }

    @Test
    fun defaultsToTheFirstPage() {
        assertThat(requestedPagesWhenOpenedAt(initialPage = null)).containsExactly(0)
    }

    @Test
    fun clampsAPageBeyondTheEndToTheLastPage() {
        assertThat(requestedPagesWhenOpenedAt(initialPage = 99)).containsExactly(3)
    }

    @Test
    fun clampsANegativePageToTheFirstPage() {
        assertThat(requestedPagesWhenOpenedAt(initialPage = -1)).containsExactly(0)
    }

    private fun requestedPagesWhenOpenedAt(initialPage: Int?): List<Int> {
        val requestedPages = Collections.synchronizedList(mutableListOf<Int>())
        val recordingRenderer = object : PdfRendererBinder {
            override suspend fun probe(pdf: ParcelFileDescriptor): ProbeResult =
                ProbeResult.Ok(pageCount = PAGE_COUNT)

            override suspend fun render(
                pdf: ParcelFileDescriptor,
                page: Int,
                widthPx: Int,
                heightPx: Int,
                sourceRect: RenderSourceRect,
            ): RenderResult {
                requestedPages += page
                // Rejected keeps the test free of SharedMemory plumbing.
                return RenderResult.Rejected(DocumentRejectedKind.RendererFailed)
            }
        }
        val doc = PdfDocument(
            id = PdfDocumentId("doc-initial-page"),
            displayLabel = "doc.pdf",
            byteCount = 1024L,
            pageCount = PAGE_COUNT,
            importedAtEpochMs = 0L,
        )
        val backing = File.createTempFile("doc", ".pdf").apply { writeBytes(byteArrayOf(0x25)) }
        file = backing
        val fd = ParcelFileDescriptor.open(backing, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = fd

        composeRule.setContent {
            ThemedHost {
                if (initialPage == null) {
                    FullScreenDocumentView(
                        doc = doc,
                        pdfFile = fd,
                        renderer = recordingRenderer,
                        onClose = {},
                    )
                } else {
                    FullScreenDocumentView(
                        doc = doc,
                        pdfFile = fd,
                        renderer = recordingRenderer,
                        onClose = {},
                        initialPage = initialPage,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        return requestedPages.toList()
    }

    @Composable
    private fun ThemedHost(content: @Composable () -> Unit) {
        MaterialTheme {
            DocumentTheme(semantics = semantics, content = content)
        }
    }

    private companion object {
        const val PAGE_COUNT = 4
    }
}
