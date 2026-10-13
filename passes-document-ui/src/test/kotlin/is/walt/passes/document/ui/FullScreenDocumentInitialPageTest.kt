package `is`.walt.passes.document.ui

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.document.PdfDocument
import `is`.walt.passes.document.PdfDocumentId
import java.io.File
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
        val recordingRenderer = RecordingRenderer(pageCount = PAGE_COUNT)
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
        return recordingRenderer.pages()
    }

    private companion object {
        const val PAGE_COUNT = 4
    }
}
