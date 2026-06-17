package `is`.walt.passes.document

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.document.DefaultDocumentImporter.ImageDecodeOutcome
import `is`.walt.passes.image.android.ImageDecodeRejectedKind
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

    // -- helpers --------------------------------------------------------------------

    private fun importer(
        pdfImport: suspend (ByteArray, String, PdfPersist) -> PdfImportResult =
            { _, _, _ -> PdfImportResult.Rejected(DocumentRejectedKind.NotAPdf) },
        imageDecode: suspend (ByteArray, Int) -> ImageDecodeOutcome =
            { _, _ -> ImageDecodeOutcome.Rejected(ImageDecodeRejectedKind.NotAnImage) },
        imageTelemetry: ImageImportTelemetryGuard = ImageImportTelemetryGuard.NoOp,
        wallClock: () -> Long = { FIXED_WALL_CLOCK_MS },
    ): DefaultDocumentImporter =
        DefaultDocumentImporter(
            config = DocumentImportConfig(imageTelemetryGuard = imageTelemetry),
            pdfImport = pdfImport,
            imageDecode = imageDecode,
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
