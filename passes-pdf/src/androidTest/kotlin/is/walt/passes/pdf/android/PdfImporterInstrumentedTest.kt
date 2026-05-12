package `is`.walt.passes.pdf.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device coverage for the [PdfImporter] orchestration. Each scenario exercises the
 * pieces the JVM-side suite cannot: the actual `Os.memfd_create` syscall, the actual
 * isolated-process renderer service, the actual SharedMemory→Bitmap→PNG round trip.
 *
 * Test fixtures (benign single-page PDF, malformed PDF) are tracked under the same
 * follow-up that hosts [PdfRendererServiceInstrumentedTest]'s fixtures (`wpass-5v9`)
 * and land with the on-device CI configuration. The tests are `@Ignore`d at check-in
 * so `./gradlew check` stays green on workstations without an emulator; CI flips them
 * on by overriding the Ignore via a test filter and runs them on AGP managed devices,
 * the same way `passes-storage` runs its Robolectric-incompatible scenarios.
 */
@RunWith(AndroidJUnit4::class)
class PdfImporterInstrumentedTest {
    @Test
    @Ignore("Pending fixture set + on-device CI wiring (wpass-5v9 follow-up)")
    fun benignPdfImportEndToEndProducesImportedWithThumbnail() {
        // Build a PdfImporter via PdfImporter.create(context), import a 1-page benign
        // fixture from a content URI, assert Imported(doc) with pageCount == 1 and a
        // non-empty thumbnail BLOB persisted via the lambda.
    }

    @Test
    @Ignore("Pending fixture set + on-device CI wiring (wpass-5v9 follow-up)")
    fun malformedPdfReturnsRendererFailedAndNextImportSucceeds() {
        // Import a PDF crafted to crash PDFium → expect Rejected(RendererFailed). Then
        // import a benign fixture → expect Imported. Documents the renderer-rebind path
        // the binder layer's RemoteException → RendererFailed promise depends on.
    }
}
