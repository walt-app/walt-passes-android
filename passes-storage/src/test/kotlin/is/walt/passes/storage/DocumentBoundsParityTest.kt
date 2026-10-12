package `is`.walt.passes.storage

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.document.PdfImportConfig
import org.junit.Test

/**
 * Storage re-checks the import caps with its own copy of the numbers, since it has no
 * production edge to `passes-document-core`. This keeps the two copies in lockstep.
 */
class DocumentBoundsParityTest {

    @Test
    fun maxBytesMatchesTheImportCap() {
        assertThat(DocumentBounds.MAX_BYTES).isEqualTo(PdfImportConfig.DEFAULT_MAX_BYTES)
    }

    @Test
    fun maxPagesMatchesTheImportCap() {
        assertThat(DocumentBounds.MAX_PAGES).isEqualTo(PdfImportConfig.DEFAULT_MAX_PAGES)
    }
}
