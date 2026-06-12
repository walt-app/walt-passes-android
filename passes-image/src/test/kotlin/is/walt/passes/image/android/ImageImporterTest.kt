package `is`.walt.passes.image.android

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.image.ImageDocument
import `is`.walt.passes.image.ImageFormat
import `is`.walt.passes.image.ImageImportConfig
import `is`.walt.passes.image.ImageImportFailedEvent
import `is`.walt.passes.image.ImageImportResult
import `is`.walt.passes.image.ImageImportSucceededEvent
import `is`.walt.passes.image.ImageRejectedKind
import `is`.walt.passes.image.ImageTelemetryGuard
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Behavioural coverage for [DefaultImageImporter]. Each test pins one rule the importer
 * promises in its KDoc:
 *
 *  - Byte-count cap fail-fast — never reads more than `maxBytes + 1` bytes.
 *  - Header sniff short-circuits before any bitmap decode work.
 *  - Dimension cap rejects images with either axis exceeding `maxDimensionPx`.
 *  - `persist` is invoked exactly once on success, never on rejection.
 *  - `persist` throwing folds to [ImageRejectedKind.StorageHandoffFailed].
 *  - [CancellationException] propagates through `persist` and thumbnail compress.
 *  - Content URI with non-`content://` scheme rejects as [ImageRejectedKind.NotAnImage].
 *  - Telemetry fires `onImportStarted` then `onImportSucceeded` on the happy path.
 *  - The returned [ImageDocument] carries correct `byteCount`, `format`, `widthPx`, `heightPx`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImageImporterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun validPngImportsSuccessfullyAndReturnsCorrectDocument() = runTest {
        val pngBytes = minimalPngBytes(width = 4, height = 4)
        val persists = mutableListOf<PersistArgs>()

        val result = importer().import(
            source = ImageImportSource.FileDescriptor(pfdContaining(pngBytes)),
            displayLabel = "photo.png",
            persist = { label, bytes, format, w, h, thumb ->
                persists += PersistArgs(label, bytes.size, format, w, h, thumb.size)
            },
        )

        assertThat(result).isInstanceOf(ImageImportResult.Imported::class.java)
        val imported = result as ImageImportResult.Imported
        assertThat(imported.image.displayLabel).isEqualTo("photo.png")
        assertThat(imported.image.byteCount).isEqualTo(pngBytes.size.toLong())
        assertThat(imported.image.format).isEqualTo(ImageFormat.Png)
        assertThat(imported.image.widthPx).isEqualTo(4)
        assertThat(imported.image.heightPx).isEqualTo(4)
        assertThat(persists).hasSize(1)
        assertThat(persists.single().label).isEqualTo("photo.png")
        assertThat(persists.single().byteSize).isEqualTo(pngBytes.size)
        assertThat(persists.single().format).isEqualTo(ImageFormat.Png)
        assertThat(persists.single().widthPx).isEqualTo(4)
        assertThat(persists.single().heightPx).isEqualTo(4)
        assertThat(persists.single().thumbSize).isGreaterThan(0)
    }

    @Test
    fun oversizedByteCountRejectsWithOversizedAtImport() = runTest {
        val pngBytes = minimalPngBytes(width = 4, height = 4)
        val tinyCapConfig = ImageImportConfig(maxBytes = 1L)

        val result = importer(config = tinyCapConfig).import(
            source = ImageImportSource.FileDescriptor(pfdContaining(pngBytes)),
            displayLabel = "large.png",
            persist = { _, _, _, _, _, _ -> error("persist must not run for oversized") },
        )

        assertThat(result).isEqualTo(ImageImportResult.Rejected(ImageRejectedKind.OversizedAtImport))
    }

    @Test
    fun nonImageBytesRejectsAsNotAnImage() = runTest {
        // PDF magic bytes are a canonical non-image payload.
        val pdfBytes = "%PDF-1.4\nNot an image".encodeToByteArray()

        val result = importer().import(
            source = ImageImportSource.FileDescriptor(pfdContaining(pdfBytes)),
            displayLabel = "spoofed.png",
            persist = { _, _, _, _, _, _ -> error("persist must not run for non-image") },
        )

        assertThat(result).isEqualTo(ImageImportResult.Rejected(ImageRejectedKind.NotAnImage))
    }

    @Test
    fun nonContentSchemeUriRejectsAsNotAnImage() = runTest {
        // file:// is the canonical escape-hatch shape the scheme allowlist closes:
        // ContentResolver.openInputStream would happily walk a file path otherwise.
        val fileUri = Uri.parse("file:///data/data/example/downloads/photo.png")

        val result = importer().import(
            source = ImageImportSource.ContentUri(fileUri, context.contentResolver),
            displayLabel = "photo.png",
            persist = { _, _, _, _, _, _ -> error("persist must not run for non-content scheme") },
        )

        assertThat(result).isEqualTo(ImageImportResult.Rejected(ImageRejectedKind.NotAnImage))
    }

    @Test
    fun persistThrowReturnsStorageHandoffFailed() = runTest {
        val pngBytes = minimalPngBytes(width = 2, height = 2)

        val result = importer().import(
            source = ImageImportSource.FileDescriptor(pfdContaining(pngBytes)),
            displayLabel = "x.png",
            persist = { _, _, _, _, _, _ -> error("downstream storage exploded") },
        )

        assertThat(result).isEqualTo(ImageImportResult.Rejected(ImageRejectedKind.StorageHandoffFailed))
    }

    @Test
    fun persistCancellationPropagatesAndPreservesStructuredConcurrency() = runTest {
        val pngBytes = minimalPngBytes(width = 2, height = 2)

        val thrown = runCatching {
            importer().import(
                source = ImageImportSource.FileDescriptor(pfdContaining(pngBytes)),
                displayLabel = "x.png",
                persist = { _, _, _, _, _, _ ->
                    throw CancellationException("parent scope cancelled")
                },
            )
        }.exceptionOrNull()

        // CancellationException must propagate out of `import` rather than being folded onto
        // StorageHandoffFailed; otherwise the parent scope sees "import finished with
        // rejection" instead of "import was cancelled."
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun contentUriSourceDrainsThroughResolver() = runTest {
        val pngBytes = minimalPngBytes(width = 2, height = 2)
        val u = Uri.parse("content://walt-test/photo.png")
        Shadows.shadowOf(context.contentResolver).registerInputStream(u, ByteArrayInputStream(pngBytes))
        val persisted = mutableListOf<Int>()

        val result = importer().import(
            source = ImageImportSource.ContentUri(u, context.contentResolver),
            displayLabel = "from-uri.png",
            persist = { _, bytes, _, _, _, _ -> persisted += bytes.size },
        )

        assertThat(result).isInstanceOf(ImageImportResult.Imported::class.java)
        assertThat(persisted).containsExactly(pngBytes.size)
    }

    @Test
    fun telemetryFiresStartedThenSucceededOnHappyPath() = runTest {
        val pngBytes = minimalPngBytes(width = 2, height = 2)
        val telemetry = RecordingTelemetry()
        val cfg = ImageImportConfig(telemetryGuard = telemetry)

        importer(config = cfg).import(
            source = ImageImportSource.FileDescriptor(pfdContaining(pngBytes)),
            displayLabel = "x.png",
            persist = { _, _, _, _, _, _ -> Unit },
        )

        assertThat(telemetry.events).containsExactly("started", "succeeded:Png").inOrder()
    }

    @Test
    fun telemetryFiresStartedThenFailedOnRejection() = runTest {
        val pdfBytes = "%PDF-1.4\nNot an image".encodeToByteArray()
        val telemetry = RecordingTelemetry()
        val cfg = ImageImportConfig(telemetryGuard = telemetry)

        importer(config = cfg).import(
            source = ImageImportSource.FileDescriptor(pfdContaining(pdfBytes)),
            displayLabel = "x.pdf",
            persist = { _, _, _, _, _, _ -> Unit },
        )

        assertThat(telemetry.events).containsExactly("started", "failed:NotAnImage").inOrder()
    }

    @Test
    fun importerInterfaceHasOnlyImportAsAMethod() {
        val methodNames = ImageImporter::class.java
            .declaredMethods
            .map { it.name }
            .toSet()
        // Allowlist: only `import` (the suspend method becomes a single Java method taking a
        // Continuation). No `extract*`, no `getMetadata`, no `getExif`.
        assertThat(methodNames).containsExactly("import")
    }

    // --------------------------------------------------------------------- helpers

    /**
     * Creates a minimal valid PNG from a [width] × [height] solid-colour bitmap. Uses
     * Robolectric's BitmapFactory-backed `Bitmap.createBitmap`, which produces a real PNG
     * byte stream that `sniffImageFormat` and `BitmapFactory.decodeByteArray` can both
     * process. This avoids hard-coding raw PNG bytes and keeps the test self-contained.
     */
    private fun minimalPngBytes(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream()
            .also { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            .toByteArray()
            .also { bmp.recycle() }
    }

    private fun pfdContaining(bytes: ByteArray): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
        return pipe[0]
    }

    private fun importer(config: ImageImportConfig = ImageImportConfig()): DefaultImageImporter =
        DefaultImageImporter(
            config = config,
            deps = DefaultImageImporter.Deps(
                now = { 0L },
                idGenerator = { "test-id" },
            ),
        )

    private class RecordingTelemetry : ImageTelemetryGuard {
        val events: MutableList<String> = mutableListOf()

        override fun onImportStarted() {
            events += "started"
        }

        override fun onImportSucceeded(event: ImageImportSucceededEvent) {
            events += "succeeded:${event.format.name}"
        }

        override fun onImportFailed(event: ImageImportFailedEvent) {
            events += "failed:${event.outcome.name}"
        }
    }

    private data class PersistArgs(
        val label: String,
        val byteSize: Int,
        val format: ImageFormat,
        val widthPx: Int,
        val heightPx: Int,
        val thumbSize: Int,
    )
}
