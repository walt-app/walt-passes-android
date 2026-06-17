package `is`.walt.passes.document

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.BarcodeEncoder
import `is`.walt.passes.core.BarcodeMatrix
import `is`.walt.passes.core.EncodeResult
import `is`.walt.passes.core.ScannableFormat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device end-to-end for the composite (barcoded-image) artifact arm (wpass-8lu) — the
 * follow-on to the image-document on-device suite (wpass-0jw). This is what only a real device
 * proves: the public [DocumentImporter] facade, with NO test seam, driving the real
 * `memfd` materialization and BOTH isolated services (the `passes-image` decode sandbox for the
 * display raster + the `passes-barcode` decode sandbox for the symbol) across the real bind, and
 * folding the live `BarcodeDecodeResult` onto the right [DocumentImportResult] arm.
 *
 * The isolation invariant under test: the host process never decodes the user-image bytes — the
 * barcode payload is produced inside the permissionless `passes-barcode` sandbox and only
 * `{payload, ScannableFormat}` crosses the binder. (`BarcodeDecodeServiceInstrumentedTest`
 * pins the sandbox's own privilege/surface contracts; here we prove the orchestrator wires it.)
 *
 * Fixtures are generated in-process from `passes-core`'s [BarcodeEncoder] (matrix → scaled
 * [Bitmap] → PNG fd), so the corpus is auditable in source and this module needs no ZXing
 * dependency. The storage write path for [DocumentInsert.BarcodedImage] is the image path plus
 * two TEXT columns and is covered by the JVM/Robolectric round-trip and the v6→v7
 * `SchemaMigrationTest`; this suite focuses on the device-only isolated import.
 *
 * Runs in CI's connected-tests matrix and on a physical device. Not `@Ignore`'d: `./gradlew
 * check` does not run androidTest, so a workstation with no device stays green; only
 * `connectedAndroidTest` / managed-device tasks execute it.
 */
@RunWith(AndroidJUnit4::class)
class CompositeImportInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun imageCarryingAQrCodeImportsAsACompositeWithTheDecodedPayload() {
        val payload = "WALT-COMPOSITE-8LU"
        val source = pngFd("composite-qr", barcodeBitmap(payload, ScannableFormat.Qr))

        val persisted = mutableListOf<DocumentPersist>()
        val result = source.use {
            runBlocking {
                importer().import(
                    source = DocumentImportSource.FileDescriptor(it),
                    displayLabel = "membership.png",
                    confirmBarcode = { _, _ -> true },
                    persist = { p -> persisted += p },
                )
            }
        }

        val composite = result as DocumentImportResult.ImportedBarcodedImage
        assertThat(composite.doc.barcodePayload).isEqualTo(payload)
        assertThat(composite.doc.barcodeFormat).isEqualTo(ScannableFormat.Qr)
        assertThat(composite.doc.widthPx).isGreaterThan(0)
        assertThat(composite.doc.heightPx).isGreaterThan(0)

        // The SAME single row carries the image bytes AND the barcode — one artifact.
        val persistedComposite = persisted.single() as DocumentPersist.BarcodedImage
        assertThat(persistedComposite.format).isEqualTo(ImageFormat.Png)
        assertThat(persistedComposite.barcodePayload).isEqualTo(payload)
        assertThat(persistedComposite.barcodeFormat).isEqualTo(ScannableFormat.Qr)
        assertThat(persistedComposite.thumbnailBytes).isNotEmpty()
    }

    @Test
    fun declinedConfirmationPersistsAPlainImageNotAComposite() {
        // The confirm-before-usable gate: a real code is present, but the consumer declines (a
        // suspected misread). Nothing composite is persisted; the artifact degrades to an image.
        val source = pngFd("declined-qr", barcodeBitmap("MAYBE-MISREAD", ScannableFormat.Qr))

        val persisted = mutableListOf<DocumentPersist>()
        val result = source.use {
            runBlocking {
                importer().import(
                    source = DocumentImportSource.FileDescriptor(it),
                    displayLabel = "card.png",
                    confirmBarcode = { _, _ -> false },
                    persist = { p -> persisted += p },
                )
            }
        }

        assertThat(result).isInstanceOf(DocumentImportResult.ImportedImage::class.java)
        assertThat(persisted.single()).isInstanceOf(DocumentPersist.Image::class.java)
    }

    @Test
    fun imageWithNoBarcodeDegradesToPlainImage() {
        // A barcode-less photo: extraction finds nothing in the sandbox, the import degrades to a
        // plain Document.Image (the wpass-i9x graceful-degradation path).
        val source = pngFd("no-barcode", solidBitmap(512, 384))

        val persisted = mutableListOf<DocumentPersist>()
        val result = source.use {
            runBlocking {
                importer().import(
                    source = DocumentImportSource.FileDescriptor(it),
                    displayLabel = "photo.png",
                    confirmBarcode = { _, _ -> true },
                    persist = { p -> persisted += p },
                )
            }
        }

        assertThat(result).isInstanceOf(DocumentImportResult.ImportedImage::class.java)
        assertThat(persisted.single()).isInstanceOf(DocumentPersist.Image::class.java)
    }

    private fun importer(): DocumentImporter = DocumentImporter.create(context)

    /**
     * Render [payload] as [format] via the kernel encoder, scaling each module up and padding a
     * white quiet zone so the isolated decoder reliably re-reads it after the import-time
     * downscale. Returns an ARGB_8888 bitmap.
     */
    private fun barcodeBitmap(payload: String, format: ScannableFormat): Bitmap {
        val matrix: BarcodeMatrix =
            when (val r = BarcodeEncoder.encode(payload, format)) {
                is EncodeResult.Success -> r.matrix
                is EncodeResult.Failure -> error("fixture encode failed: ${r.reason}")
            }
        val scale = 8
        val quiet = 4 * scale
        val width = matrix.width * scale + quiet * 2
        val height = matrix.height * scale + quiet * 2
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        for (my in 0 until matrix.height) {
            for (mx in 0 until matrix.width) {
                if (!matrix.isSet(mx, my)) continue
                val left = quiet + mx * scale
                val top = quiet + my * scale
                for (py in top until top + scale) {
                    for (px in left until left + scale) {
                        bitmap.setPixel(px, py, Color.BLACK)
                    }
                }
            }
        }
        return bitmap
    }

    private fun solidBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private fun pngFd(name: String, bitmap: Bitmap): ParcelFileDescriptor {
        val file = File.createTempFile(name, ".png", context.cacheDir)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        // Unlink immediately: the open descriptor keeps the inode alive for the importer's read,
        // so nothing accumulates in cacheDir across repeated device/CI runs (review rec 1).
        file.delete()
        return pfd
    }
}
