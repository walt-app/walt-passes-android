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

    // Closed in @After, not inside the test: the documented pdfFile contract is that
    // the fd outlives the composition.
    private var pfd: ParcelFileDescriptor? = null
    private var file: File? = null

    @After
    fun tearDown() {
        pfd?.close()
        file?.delete()
    }

    @Test
    fun pdfPagerRequestsCurrentAndAdjacentPageButNotTheRest() {
        val recordingRenderer = RecordingRenderer(pageCount = 5)
        val doc = PdfDocument(
            id = PdfDocumentId("doc-1"),
            displayLabel = "doc.pdf",
            byteCount = 1024L,
            pageCount = 5,
            importedAtEpochMs = 0L,
        )
        val backing = File.createTempFile("doc", ".pdf").apply { writeBytes(byteArrayOf(0x25)) }
        file = backing
        val fd = ParcelFileDescriptor.open(backing, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = fd

        composeRule.setContent {
            ThemedHost {
                DocumentView(doc = doc, pdfFile = fd, renderer = recordingRenderer)
            }
        }
        composeRule.waitForIdle()

        assertThat(recordingRenderer.pages().toSet()).containsExactly(0, 1)
    }
}
