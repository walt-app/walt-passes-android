package `is`.walt.passes.pdf.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for the renderer service. Each scenario is the on-device half
 * of an assertion the unit suite cannot make: the actual PDFium decoder, the actual
 * isolated-process binder, the actual SharedMemory handoff. Test fixtures (benign PDF,
 * JS-laden PDF, malformed PDF, encrypted PDF, 11-page PDF) are tracked separately under
 * `wpass-5v9` and land with the on-device CI configuration.
 *
 * The tests are @Ignore'd at check-in so `./gradlew check` stays green on a workstation
 * that has no emulator. CI flips them on by overriding the Ignore via a test filter
 * and runs them on AGP managed devices, the same way `passes-storage` runs its
 * Robolectric-incompatible scenarios.
 */
@RunWith(AndroidJUnit4::class)
class PdfRendererServiceInstrumentedTest {
    @Test
    @Ignore("Pending fixture set + on-device CI wiring (wpass-5v9 follow-up)")
    fun benignPdfRenderProducesRequestedDimensions() {
        // bind PdfRendererService, send benign 1-page fixture, render(0, 800, 1200),
        // assert SharedMemory size == 800 * 1200 * 4 bytes.
    }

    @Test
    @Ignore("Pending fixture set + on-device CI wiring (wpass-5v9 follow-up)")
    fun jsLadenPdfHasNoObservableSideEffect() {
        // bind, send PDF carrying /OpenAction /JavaScript, render, assert no file write
        // probe fired and no network probe via VPNService stub. Documents the no-JS
        // assumption of Android's PdfRenderer (which delegates to PDFium with JS off).
    }

    @Test
    @Ignore("Pending fixture set + on-device CI wiring (wpass-5v9 follow-up)")
    fun malformedPdfCrashesRendererButMainProcessSurvives() {
        // bind, send PDF crafted to trip a PDFium bug, expect RemoteException; rebind
        // and render a benign PDF to assert recovery.
    }

    @Test
    @Ignore("Pending fixture set + on-device CI wiring (wpass-5v9 follow-up)")
    fun encryptedPdfRejectsAtProbe() {
        // bind, probe encrypted PDF, expect ProbeResult.Rejected(Encrypted).
    }

    @Test
    @Ignore("Pending fixture set + on-device CI wiring (wpass-5v9 follow-up)")
    fun elevenPagePdfRejectsAtProbe() {
        // bind, probe 11-page PDF, expect ProbeResult.Rejected(TooManyPages).
    }

    @Test
    @Ignore("Pending fixture set + on-device CI wiring (wpass-5v9 follow-up)")
    fun pageBackgroundRasterisesWhiteNotTransparent() {
        // bind, send a PDF whose page draws only a small centred glyph on the implicit
        // white background, render(0, 800, 1200), reconstruct ARGB_8888 bitmap from
        // SharedMemory, assert corner pixels == Color.WHITE (not transparent / not
        // ColorScheme.background). Regression for GitHub #92: QR codes were invisible
        // in dark mode because the renderer left the page background transparent.
    }
}
