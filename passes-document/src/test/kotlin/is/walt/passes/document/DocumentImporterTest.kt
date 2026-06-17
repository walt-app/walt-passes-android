package `is`.walt.passes.document

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.DecodeFailureReason
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.document.DefaultDocumentImporter.ImageDecodeOutcome
import `is`.walt.passes.image.android.ImageDecodeRejectedKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * JVM orchestration tests for [DocumentImporter] — the trust-claim-bearing sniff/branch/map.
 * The two isolated backends are replaced by seams (no live binder, no real decode), so this
 * suite verifies exactly the importer's own contribution: which backend each magic gets routed
 * to, that the buffered ORIGINAL bytes reach `persist`, and how each backend outcome folds onto
 * [DocumentImportResult]. The real memfd + bind path is covered on device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DocumentImporterTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val pngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4,
    )
    private val pdfBytes = "%PDF-1.7\n%binary".toByteArray()

    @Test
    fun pdfMagicRoutesToThePdfBackendAndForwardsOriginalBytesToPersist() = runTest {
        var seenByBackend: ByteArray? = null
        val persisted = mutableListOf<DocumentPersist>()
        val importer = importer(
            pdfImport = { bytes, label, persist ->
                seenByBackend = bytes
                persist(label, bytes, 3, byteArrayOf(9, 9))
                PdfImportResult.Imported(pdfDoc(label, bytes.size.toLong(), pageCount = 3))
            },
        )

        val result = importer.import(source(pdfBytes), "boarding.pdf") { persisted += it }

        assertThat(result).isInstanceOf(DocumentImportResult.ImportedPdf::class.java)
        assertThat(seenByBackend).isEqualTo(pdfBytes)
        val pdf = persisted.single() as DocumentPersist.Pdf
        assertThat(pdf.label).isEqualTo("boarding.pdf")
        assertThat(pdf.bytes).isEqualTo(pdfBytes)
        assertThat(pdf.pageCount).isEqualTo(3)
        assertThat(pdf.thumbnailBytes).isEqualTo(byteArrayOf(9, 9))
    }

    @Test
    fun pdfBackendStorageHandoffFailureHoistsToTheSharedArm() = runTest {
        val importer = importer(
            pdfImport = { _, _, _ ->
                PdfImportResult.Rejected(DocumentRejectedKind.StorageHandoffFailed)
            },
        )
        val result = importer.import(source(pdfBytes), "x.pdf") {}
        assertThat(result).isEqualTo(DocumentImportResult.StorageHandoffFailed)
    }

    @Test
    fun pdfBackendOtherRejectionMapsToPdfRejectedWithTheSameKind() = runTest {
        val importer = importer(
            pdfImport = { _, _, _ -> PdfImportResult.Rejected(DocumentRejectedKind.Encrypted) },
        )
        val result = importer.import(source(pdfBytes), "x.pdf") {}
        assertThat(result).isEqualTo(DocumentImportResult.PdfRejected(DocumentRejectedKind.Encrypted))
    }

    @Test
    fun imageMagicRoutesToTheImageBackendAndPersistsOriginalBytesWithDimensions() = runTest {
        var maxPxRequested = -1
        val persisted = mutableListOf<DocumentPersist>()
        val telemetry = RecordingImageGuard()
        val importer = importer(
            imageTelemetry = telemetry,
            imageDecode = { _, maxPx ->
                maxPxRequested = maxPx
                ImageDecodeOutcome.Decoded(thumbnailBytes = byteArrayOf(7), widthPx = 800, heightPx = 600)
            },
        )

        val result = importer.import(source(pngBytes), "ticket.png") { persisted += it }

        val imported = result as DocumentImportResult.ImportedImage
        assertThat(imported.doc.widthPx).isEqualTo(800)
        assertThat(imported.doc.heightPx).isEqualTo(600)
        assertThat(imported.doc.byteCount).isEqualTo(pngBytes.size.toLong())
        // importedAt is stamped from the injected wall clock, not System.currentTimeMillis().
        assertThat(imported.doc.importedAtEpochMs).isEqualTo(FIXED_WALL_CLOCK_MS)
        assertThat(maxPxRequested).isEqualTo(DocumentImportConfig.DEFAULT_MAX_IMAGE_DECODE_PX)
        val image = persisted.single() as DocumentPersist.Image
        assertThat(image.bytes).isEqualTo(pngBytes)
        assertThat(image.format).isEqualTo(ImageFormat.Png)
        assertThat(image.widthPx).isEqualTo(800)
        assertThat(image.thumbnailBytes).isEqualTo(byteArrayOf(7))
        assertThat(telemetry.events).containsExactly("started", "ok:Png:800:600").inOrder()
    }

    @Test
    fun imageDecodeRejectionMapsToImageRejectedAndEmitsDecodeTelemetry() = runTest {
        val telemetry = RecordingImageGuard()
        val importer = importer(
            imageTelemetry = telemetry,
            imageDecode = { _, _ -> ImageDecodeOutcome.Rejected(ImageDecodeRejectedKind.DimensionsTooLarge) },
        )
        val result = importer.import(source(pngBytes), "big.png") {}
        assertThat(result)
            .isEqualTo(DocumentImportResult.ImageRejected(ImageDecodeRejectedKind.DimensionsTooLarge))
        assertThat(telemetry.events).containsExactly("started", "failed:Decode").inOrder()
    }

    @Test
    fun imagePersistFailureHoistsToStorageHandoffAndEmitsStorageTelemetry() = runTest {
        val telemetry = RecordingImageGuard()
        val importer = importer(
            imageTelemetry = telemetry,
            imageDecode = { _, _ -> ImageDecodeOutcome.Decoded(byteArrayOf(1), 10, 10) },
        )
        val result = importer.import(source(pngBytes), "ticket.png") { error("db down") }
        assertThat(result).isEqualTo(DocumentImportResult.StorageHandoffFailed)
        assertThat(telemetry.events).containsExactly("started", "failed:StorageHandoff").inOrder()
    }

    @Test
    fun unrecognizedBytesReturnUnrecognizedAndTouchNoBackend() = runTest {
        var pdfCalled = false
        var imageCalled = false
        val importer = importer(
            pdfImport = { _, _, _ ->
                pdfCalled = true
                PdfImportResult.Rejected(DocumentRejectedKind.NotAPdf)
            },
            imageDecode = { _, _ ->
                imageCalled = true
                ImageDecodeOutcome.Rejected(ImageDecodeRejectedKind.NotAnImage)
            },
        )
        val result = importer.import(source(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)), "mystery.bin") {}
        assertThat(result).isEqualTo(DocumentImportResult.Unrecognized)
        assertThat(pdfCalled).isFalse()
        assertThat(imageCalled).isFalse()
    }

    // -- composite artifact (wpass-8lu) ---------------------------------------------

    @Test
    fun imageWithBarcodeRoutesToCompositeAndPersistsPayloadAndFormat() = runTest {
        var extractSawBytes: ByteArray? = null
        val persisted = mutableListOf<DocumentPersist>()
        val importer = importer(
            imageDecode = { _, _ -> ImageDecodeOutcome.Decoded(byteArrayOf(7), widthPx = 640, heightPx = 480) },
            barcodeExtract = { bytes ->
                extractSawBytes = bytes
                BarcodeDecodeResult.DecodedBarcode("PASS-12345", ScannableFormat.Code128)
            },
        )

        val result = importer.import(
            source = source(pngBytes),
            displayLabel = "card.png",
            confirmBarcode = { _, _ -> true },
            persist = { persisted += it },
        )

        val imported = result as DocumentImportResult.ImportedBarcodedImage
        assertThat(imported.doc.widthPx).isEqualTo(640)
        assertThat(imported.doc.heightPx).isEqualTo(480)
        assertThat(imported.doc.barcodePayload).isEqualTo("PASS-12345")
        assertThat(imported.doc.barcodeFormat).isEqualTo(ScannableFormat.Code128)
        assertThat(imported.doc.byteCount).isEqualTo(pngBytes.size.toLong())
        // The barcode decoder saw the SAME ORIGINAL bytes the image decoder did.
        assertThat(extractSawBytes).isEqualTo(pngBytes)
        val persistedComposite = persisted.single() as DocumentPersist.BarcodedImage
        assertThat(persistedComposite.bytes).isEqualTo(pngBytes)
        assertThat(persistedComposite.format).isEqualTo(ImageFormat.Png)
        assertThat(persistedComposite.barcodePayload).isEqualTo("PASS-12345")
        assertThat(persistedComposite.barcodeFormat).isEqualTo(ScannableFormat.Code128)
        assertThat(persistedComposite.thumbnailBytes).isEqualTo(byteArrayOf(7))
    }

    @Test
    fun imageWithNoBarcodeDegradesToPlainImage() = runTest {
        val persisted = mutableListOf<DocumentPersist>()
        val importer = importer(
            imageDecode = { _, _ -> ImageDecodeOutcome.Decoded(byteArrayOf(7), 100, 100) },
            barcodeExtract = { BarcodeDecodeResult.NoBarcodeFound },
        )

        val result = importer.import(
            source = source(pngBytes),
            displayLabel = "plain.png",
            confirmBarcode = { _, _ -> true },
            persist = { persisted += it },
        )

        assertThat(result).isInstanceOf(DocumentImportResult.ImportedImage::class.java)
        assertThat(persisted.single()).isInstanceOf(DocumentPersist.Image::class.java)
    }

    @Test
    fun barcodeExtractionFailureDegradesToPlainImageRatherThanFailingImport() = runTest {
        val importer = importer(
            imageDecode = { _, _ -> ImageDecodeOutcome.Decoded(byteArrayOf(7), 100, 100) },
            barcodeExtract = { BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.ImageDecodeFailed) },
        )
        val result = importer.import(
            source = source(pngBytes),
            displayLabel = "plain.png",
            confirmBarcode = { _, _ -> true },
            persist = {},
        )
        assertThat(result).isInstanceOf(DocumentImportResult.ImportedImage::class.java)
    }

    @Test
    fun withoutAConfirmHookExtractionIsSkippedAndTheResultIsAPlainImage() = runTest {
        // Opt-in default (wpass-8lu review rec 2): no hook -> no isolated barcode round-trip, and a
        // present code never silently produces a composite.
        var extractionCalled = false
        val persisted = mutableListOf<DocumentPersist>()
        val importer = importer(
            imageDecode = { _, _ -> ImageDecodeOutcome.Decoded(byteArrayOf(7), 100, 100) },
            barcodeExtract = {
                extractionCalled = true
                BarcodeDecodeResult.DecodedBarcode("INCIDENTAL", ScannableFormat.Qr)
            },
        )

        val result = importer.import(source(pngBytes), "photo.png") { persisted += it }

        assertThat(extractionCalled).isFalse()
        assertThat(result).isInstanceOf(DocumentImportResult.ImportedImage::class.java)
        assertThat(persisted.single()).isInstanceOf(DocumentPersist.Image::class.java)
    }

    @Test
    fun confirmHookThrowingNonCancellationDegradesToPlainImage() = runTest {
        // A confirm-UI bug must not fail the whole import: a non-cancellation throw is treated as
        // a declined confirmation and the artifact degrades to a plain image.
        val persisted = mutableListOf<DocumentPersist>()
        val importer = importer(
            imageDecode = { _, _ -> ImageDecodeOutcome.Decoded(byteArrayOf(7), 100, 100) },
            barcodeExtract = { BarcodeDecodeResult.DecodedBarcode("CODE", ScannableFormat.Qr) },
        )

        val result = importer.import(
            source = source(pngBytes),
            displayLabel = "card.png",
            confirmBarcode = { _, _ -> error("confirm UI crashed") },
            persist = { persisted += it },
        )

        assertThat(result).isInstanceOf(DocumentImportResult.ImportedImage::class.java)
        assertThat(persisted.single()).isInstanceOf(DocumentPersist.Image::class.java)
    }

    @Test
    fun confirmHookCancellationPropagatesOutOfImportAndPersistsNothing() = runTest {
        // CancellationException must propagate to preserve structured concurrency (not be swallowed
        // to a declined confirmation), and nothing is persisted because confirm runs before persist.
        var persisted = false
        var propagated = false
        val importer = importer(
            imageDecode = { _, _ -> ImageDecodeOutcome.Decoded(byteArrayOf(7), 100, 100) },
            barcodeExtract = { BarcodeDecodeResult.DecodedBarcode("CODE", ScannableFormat.Qr) },
        )

        try {
            importer.import(
                source = source(pngBytes),
                displayLabel = "card.png",
                confirmBarcode = { _, _ -> throw CancellationException("scope cancelled") },
                persist = { persisted = true },
            )
        } catch (_: CancellationException) {
            propagated = true
        }

        assertThat(propagated).isTrue()
        assertThat(persisted).isFalse()
    }

    @Test
    fun declinedConfirmationDegradesToPlainImageAndPersistsNoBarcode() = runTest {
        val persisted = mutableListOf<DocumentPersist>()
        val importer = importer(
            imageDecode = { _, _ -> ImageDecodeOutcome.Decoded(byteArrayOf(7), 100, 100) },
            barcodeExtract = { BarcodeDecodeResult.DecodedBarcode("MAYBE-MISREAD", ScannableFormat.Qr) },
        )

        val result = importer.import(
            source = source(pngBytes),
            displayLabel = "card.png",
            confirmBarcode = { _, _ -> false },
            persist = { persisted += it },
        )

        assertThat(result).isInstanceOf(DocumentImportResult.ImportedImage::class.java)
        assertThat(persisted.single()).isInstanceOf(DocumentPersist.Image::class.java)
    }

    @Test
    fun confirmHookSeesTheDecodedPayloadBeforePersist() = runTest {
        val confirmArgs = mutableListOf<Pair<String, ScannableFormat>>()
        var persistedBeforeConfirm = false
        var confirmed = false
        val importer = importer(
            imageDecode = { _, _ -> ImageDecodeOutcome.Decoded(byteArrayOf(7), 100, 100) },
            barcodeExtract = { BarcodeDecodeResult.DecodedBarcode("SEEN-FIRST", ScannableFormat.Ean13) },
        )

        importer.import(
            source = source(pngBytes),
            displayLabel = "card.png",
            persist = { if (!confirmed) persistedBeforeConfirm = true },
            confirmBarcode = { payload, format ->
                confirmArgs += payload to format
                confirmed = true
                true
            },
        )

        assertThat(confirmArgs).containsExactly("SEEN-FIRST" to ScannableFormat.Ean13)
        assertThat(persistedBeforeConfirm).isFalse()
    }

    @Test
    fun pdfWithEmbeddedBytesNeverRunsBarcodeExtraction() = runTest {
        var extractionCalled = false
        val importer = importer(
            pdfImport = { _, _, _ -> PdfImportResult.Imported(pdfDoc("x.pdf", 10, 1)) },
            barcodeExtract = {
                extractionCalled = true
                BarcodeDecodeResult.NoBarcodeFound
            },
        )
        // Opt in to composites; the PDF branch must STILL never run image-barcode extraction.
        importer.import(
            source = source(pdfBytes),
            displayLabel = "x.pdf",
            confirmBarcode = { _, _ -> true },
            persist = {},
        )
        assertThat(extractionCalled).isFalse()
    }

    // -- helpers --------------------------------------------------------------------

    private fun importer(
        pdfImport: suspend (ByteArray, String, PdfPersist) -> PdfImportResult =
            { _, _, _ -> PdfImportResult.Rejected(DocumentRejectedKind.NotAPdf) },
        imageDecode: suspend (ByteArray, Int) -> ImageDecodeOutcome =
            { _, _ -> ImageDecodeOutcome.Rejected(ImageDecodeRejectedKind.NotAnImage) },
        // Default: no barcode found, so an image import yields a plain ImportedImage. Composite
        // tests override this to a DecodedBarcode.
        barcodeExtract: suspend (ByteArray) -> BarcodeDecodeResult =
            { BarcodeDecodeResult.NoBarcodeFound },
        imageTelemetry: ImageImportTelemetryGuard = ImageImportTelemetryGuard.NoOp,
        wallClock: () -> Long = { FIXED_WALL_CLOCK_MS },
    ): DefaultDocumentImporter =
        DefaultDocumentImporter(
            config = DocumentImportConfig(imageTelemetryGuard = imageTelemetry),
            pdfImport = pdfImport,
            imageDecode = imageDecode,
            barcodeExtract = barcodeExtract,
            now = { 0L },
            wallClock = wallClock,
            idGenerator = { "fixed-id" },
        )

    private fun source(bytes: ByteArray): DocumentImportSource.FileDescriptor {
        val file: File = tmp.newFile()
        file.writeBytes(bytes)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return DocumentImportSource.FileDescriptor(pfd)
    }

    private fun pdfDoc(label: String, byteCount: Long, pageCount: Int): PdfDocument =
        PdfDocument(
            id = PdfDocumentId("pdf-id"),
            displayLabel = label,
            byteCount = byteCount,
            pageCount = pageCount,
            importedAtEpochMs = 0L,
        )

    private class RecordingImageGuard : ImageImportTelemetryGuard {
        val events: MutableList<String> = mutableListOf()
        override fun onImportStarted() { events += "started" }
        override fun onImportSucceeded(event: ImageImportSucceededEvent) {
            events += "ok:${event.format}:${event.widthPx}:${event.heightPx}"
        }
        override fun onImportFailed(event: ImageImportFailedEvent) {
            events += "failed:${event.outcome.name}"
        }
    }

    private companion object {
        // Arbitrary fixed epoch-ms the injected wall clock returns, so importedAt is pinnable.
        const val FIXED_WALL_CLOCK_MS: Long = 1_700_000_000_000L
    }
}
