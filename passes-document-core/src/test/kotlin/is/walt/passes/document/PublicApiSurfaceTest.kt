package `is`.walt.passes.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the public API surface of `passes-document-core`. There is no renderer or import implementation
 * yet (those land in wpass-5v9 / wpass-pdf.renderer-service); these tests target two things:
 *
 *  1. Drift detection — every sealed arm and enum value referenced by the import contract is
 *     reachable from a test. Removing an arm fails compilation; adding one without updating
 *     the [DocumentRejectedKind] enum fails compilation in the consumer-side `when`s.
 *  2. Default-policy locks — [PdfImportConfig] defaults encode ADR 0005 D7 (25 MB / 10 pages
 *     / 5 s); flipping a default is a deliberate, test-breaking change.
 */
class PublicApiSurfaceTest {
    @Test
    fun pdfImportConfigDefaultsMatchAdr0005D7() {
        val cfg = PdfImportConfig()
        assertThat(cfg.maxBytes).isEqualTo(25L * 1024 * 1024)
        assertThat(cfg.maxPages).isEqualTo(10)
        assertThat(cfg.renderTimeoutMs).isEqualTo(5_000L)
        assertThat(PdfImportConfig.DEFAULT_MAX_BYTES).isEqualTo(cfg.maxBytes)
        assertThat(PdfImportConfig.DEFAULT_MAX_PAGES).isEqualTo(cfg.maxPages)
        assertThat(PdfImportConfig.DEFAULT_RENDER_TIMEOUT_MS).isEqualTo(cfg.renderTimeoutMs)
    }

    @Test
    fun pdfImportConfigDefaultGuardIsNoOp() {
        assertThat(PdfImportConfig().telemetryGuard).isSameInstanceAs(DocumentTelemetryGuard.NoOp)
    }

    @Test
    fun documentRejectedKindHasExactlyTheEightListedArms() {
        val all =
            setOf(
                DocumentRejectedKind.OversizedAtImport,
                DocumentRejectedKind.NotAPdf,
                DocumentRejectedKind.Encrypted,
                DocumentRejectedKind.TooManyPages,
                DocumentRejectedKind.RendererFailed,
                DocumentRejectedKind.UnsupportedAndroidVersion,
                DocumentRejectedKind.EncoderFailed,
                DocumentRejectedKind.StorageHandoffFailed,
            )
        assertThat(all).containsExactlyElementsIn(DocumentRejectedKind.entries)
        assertThat(DocumentRejectedKind.entries).hasSize(8)
    }

    @Test
    fun provenanceHasExactlyUserProvided() {
        assertThat(Provenance.entries).containsExactly(Provenance.UserProvided)
    }

    @Test
    fun pdfImportResultArmsAreReachableViaWhen() {
        val rejected: PdfImportResult = PdfImportResult.Rejected(DocumentRejectedKind.Encrypted)
        val branch =
            when (rejected) {
                is PdfImportResult.Imported -> "imported"
                is PdfImportResult.Rejected -> "rejected"
            }
        assertThat(branch).isEqualTo("rejected")
    }

    @Test
    fun pdfDocumentConstructorIsExercisedWithEveryShape() {
        val doc =
            PdfDocument(
                id = PdfDocumentId("doc-1"),
                displayLabel = "boarding-pass.pdf",
                byteCount = 1_234_567L,
                pageCount = 3,
                importedAtEpochMs = 1_800_000_000_000L,
                provenance = Provenance.UserProvided,
            )
        assertThat(doc.id).isEqualTo(PdfDocumentId("doc-1"))
        assertThat(doc.provenance).isEqualTo(Provenance.UserProvided)
    }

    @Test
    fun pdfDocumentDefaultProvenanceIsUserProvided() {
        val doc =
            PdfDocument(
                id = PdfDocumentId("doc-1"),
                displayLabel = "x",
                byteCount = 0,
                pageCount = 0,
                importedAtEpochMs = 0L,
            )
        assertThat(doc.provenance).isEqualTo(Provenance.UserProvided)
    }

    @Test
    fun imageDocumentConstructorIsExercisedWithEveryShape() {
        val doc =
            ImageDocument(
                id = ImageDocumentId("img-1"),
                displayLabel = "ticket.jpg",
                byteCount = 2_345_678L,
                widthPx = 1080,
                heightPx = 1920,
                importedAtEpochMs = 1_800_000_000_000L,
                provenance = Provenance.UserProvided,
            )
        assertThat(doc.id).isEqualTo(ImageDocumentId("img-1"))
        assertThat(doc.widthPx).isEqualTo(1080)
        assertThat(doc.heightPx).isEqualTo(1920)
        assertThat(doc.provenance).isEqualTo(Provenance.UserProvided)
    }

    @Test
    fun imageDocumentDefaultProvenanceIsUserProvided() {
        val doc =
            ImageDocument(
                id = ImageDocumentId("img-1"),
                displayLabel = "x",
                byteCount = 0,
                widthPx = 1,
                heightPx = 1,
                importedAtEpochMs = 0L,
            )
        assertThat(doc.provenance).isEqualTo(Provenance.UserProvided)
    }

    @Test
    fun documentSealedHierarchyHasExactlyThePdfImageAndBarcodedImageArms() {
        // Document is the sealed supertype; all arms are assignable to it, and each id to
        // DocumentId. Adding a fourth arm is a deliberate, test-breaking edit here (and a
        // security review, since each arm is an untrusted-content surface — ADR 0005). The
        // third arm (BarcodedImageDocument, wpass-8lu) is a composite: an image plus a barcode
        // extracted from it in the isolated decoder.
        val pdf: Document =
            PdfDocument(
                id = PdfDocumentId("p"),
                displayLabel = "p",
                byteCount = 0,
                pageCount = 1,
                importedAtEpochMs = 0L,
            )
        val image: Document =
            ImageDocument(
                id = ImageDocumentId("i"),
                displayLabel = "i",
                byteCount = 0,
                widthPx = 1,
                heightPx = 1,
                importedAtEpochMs = 0L,
            )
        val barcodedImage: Document =
            BarcodedImageDocument(
                id = BarcodedImageDocumentId("b"),
                displayLabel = "b",
                byteCount = 0,
                widthPx = 1,
                heightPx = 1,
                barcodePayload = "PAYLOAD",
                barcodeFormat = `is`.walt.passes.core.ScannableFormat.Code128,
                importedAtEpochMs = 0L,
            )
        val arms =
            listOf(pdf, image, barcodedImage).map { doc ->
                when (doc) {
                    is PdfDocument -> "pdf"
                    is ImageDocument -> "image"
                    is BarcodedImageDocument -> "barcodedImage"
                }
            }
        assertThat(arms).containsExactly("pdf", "image", "barcodedImage").inOrder()
        assertThat(pdf.id).isInstanceOf(PdfDocumentId::class.java)
        assertThat(image.id).isInstanceOf(ImageDocumentId::class.java)
        assertThat(barcodedImage.id).isInstanceOf(BarcodedImageDocumentId::class.java)
    }

    @Test
    fun documentTelemetryGuardNoOpAcceptsAllEventShapes() {
        val guard: DocumentTelemetryGuard = DocumentTelemetryGuard.NoOp
        guard.onImportStarted()
        guard.onImportSucceeded(
            DocumentImportSucceededEvent(byteCount = 12_345L, pageCount = 4, durationMillis = 42L),
        )
        guard.onImportFailed(
            DocumentImportFailedEvent(outcome = DocumentRejectedKind.Encrypted, durationMillis = 7L),
        )
        guard.onConsumerRenderFailed(ConsumerRenderFailure.OutOfMemory)
        guard.onConsumerRenderFailed(ConsumerRenderFailure.SharedMemoryUnavailable)
        guard.onConsumerRenderFailed(ConsumerRenderFailure.DimensionMismatch)
        guard.onConsumerRenderFailed(ConsumerRenderFailure.Other)
    }

    @Test
    fun consumerRenderFailureHasExactlyTheFourListedArms() {
        val all =
            setOf(
                ConsumerRenderFailure.OutOfMemory,
                ConsumerRenderFailure.SharedMemoryUnavailable,
                ConsumerRenderFailure.DimensionMismatch,
                ConsumerRenderFailure.Other,
            )
        assertThat(all).containsExactlyElementsIn(ConsumerRenderFailure.entries)
        assertThat(ConsumerRenderFailure.entries).hasSize(4)
    }

    /**
     * Mirrors `passes-ui::PublicApiSurfaceTest.uiTelemetryGuardEventsAreEnumsAndPrimitivesOnly`:
     * an exhaustive behavioral exercise that pins every guard method against an
     * enums-and-primitives-only shape. Adding a free-form `String`, `ByteArray`, or `Throwable`
     * parameter to any method below would either fail to compile against this fixture or fall
     * through the `DocumentTelemetryGuardSurfaceTest` allowlist.
     */
    @Test
    fun documentTelemetryGuardEventsAreEnumsAndPrimitivesOnly() {
        val recorded = mutableListOf<String>()
        val guard =
            object : DocumentTelemetryGuard {
                override fun onImportStarted() {
                    recorded += "started"
                }

                override fun onImportSucceeded(event: DocumentImportSucceededEvent) {
                    recorded += "ok:${event.byteCount}:${event.pageCount}:${event.durationMillis}"
                }

                override fun onImportFailed(event: DocumentImportFailedEvent) {
                    recorded += "failed:${event.outcome.name}:${event.durationMillis}"
                }

                override fun onConsumerRenderFailed(reason: ConsumerRenderFailure) {
                    recorded += "render:${reason.name}"
                }
            }
        guard.onImportStarted()
        guard.onImportSucceeded(
            DocumentImportSucceededEvent(byteCount = 99L, pageCount = 2, durationMillis = 11L),
        )
        guard.onImportFailed(
            DocumentImportFailedEvent(outcome = DocumentRejectedKind.TooManyPages, durationMillis = 3L),
        )
        guard.onConsumerRenderFailed(ConsumerRenderFailure.DimensionMismatch)

        assertThat(recorded).containsExactly(
            "started",
            "ok:99:2:11",
            "failed:TooManyPages:3",
            "render:DimensionMismatch",
        ).inOrder()
    }
}
