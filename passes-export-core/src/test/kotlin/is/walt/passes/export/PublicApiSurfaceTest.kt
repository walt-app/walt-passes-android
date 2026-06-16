package `is`.walt.passes.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the public API surface of `passes-export-core`. Any change to [ArtifactKind]
 * constants or the [ExportableArtifact] interface shape is a cross-platform wire-format
 * change and must break this test intentionally.
 */
class PublicApiSurfaceTest {

    @Test
    fun artifactKindPkpassIsStableWireString() {
        assertThat(ArtifactKind.PKPASS).isEqualTo("pkpass")
    }

    @Test
    fun artifactKindScannableCardIsStableWireString() {
        assertThat(ArtifactKind.SCANNABLE_CARD).isEqualTo("scannable_card")
    }

    @Test
    fun artifactKindPdfDocumentIsStableWireString() {
        assertThat(ArtifactKind.PDF_DOCUMENT).isEqualTo("pdf_document")
    }

    @Test
    fun exportableArtifactInterfaceExposeThreeStringProperties() {
        val artifact = object : ExportableArtifact {
            override val exportKind = ArtifactKind.SCANNABLE_CARD
            override val exportId = "id-1"
            override val exportCreatedAt = "2026-06-15T15:06:40Z"
        }
        assertThat(artifact.exportKind).isEqualTo("scannable_card")
        assertThat(artifact.exportId).isEqualTo("id-1")
        assertThat(artifact.exportCreatedAt).isEqualTo("2026-06-15T15:06:40Z")
    }
}
