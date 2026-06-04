package `is`.walt.passes.barcode.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for the isolated decode service. Each scenario is the on-device half
 * of an assertion the unit suite cannot make: the actual isolated process, the actual
 * cross-process binder, the actual permission sandbox. The wire format and orchestration are
 * covered on the JVM by [BarcodeDecodeBinderRoundTripTest] and [DefaultBarcodeImageDecoderTest];
 * what only a device can prove is that the decode process genuinely holds no capabilities.
 *
 * The headline test is [decodeProcessCannotReachAppDataOrKeystore]: it is the runtime
 * counterpart to the manifest pin in [ManifestPermissionsTest]. A manifest can declare
 * `isolatedProcess="true"`, but only running code inside that process and watching a
 * privileged call fail with `SecurityException` proves the sandbox is real
 * (leakcanary#948-style). This is the contractual go/no-go boundary from walt-android
 * wlt-58a.1: nothing in this process may reach Keystore / DPAN / card material.
 *
 * The tests are @Ignore'd at check-in so `./gradlew check` stays green on a workstation with
 * no emulator. Note that CI's connected-tests matrix currently covers only `:passes-storage`
 * (see `.github/workflows/ci.yml`); wiring a `:passes-barcode` device matrix, filling these
 * bodies, and un-ignoring them is owned by wpass-zrt.5 (the security suite). Until that lands,
 * the runtime isolation proof is NOT exercised in CI — only the static
 * [ManifestPermissionsTest] half is — so wpass-zrt.5 must gate the epic ship. Image fixtures
 * (benign QR, malformed container, decompression bomb, format-outside-roster) land with the
 * decode beads (wpass-zrt.3–.5).
 */
@RunWith(AndroidJUnit4::class)
class BarcodeDecodeServiceInstrumentedTest {
    @Test
    @Ignore("Pending on-device CI wiring (wpass-zrt.5 follow-up)")
    fun decodeProcessCannotReachAppDataOrKeystore() {
        // bind BarcodeDecodeService; from inside the isolated process attempt a privileged
        // call (open the app's files dir / load an AndroidKeyStore entry / open the
        // SQLCipher DB file) and assert it fails with SecurityException (or FileNotFound for
        // the unreachable app-data path). The decode UID must reach none of them.
    }

    @Test
    @Ignore("Pending fixture set + on-device CI wiring (wpass-zrt.4/.5 follow-up)")
    fun benignQrDecodesToPayloadAndFormat() {
        // bind, hand a benign QR image fd across, assert DecodedBarcode(payload, Qr). Lands
        // with the real ZXing decode (wpass-zrt.4); until then the service returns the empty
        // arm and this scenario stays ignored.
    }

    @Test
    @Ignore("Pending fixture set + on-device CI wiring (wpass-zrt.3/.5 follow-up)")
    fun decompressionBombRejectsWithImageTooLarge() {
        // bind, hand a decompression-bomb image fd across, assert DecodeFailed(ImageTooLarge).
        // The bounded-decode caps land in wpass-zrt.3.
    }

    @Test
    @Ignore("Pending fixture set + on-device CI wiring (wpass-zrt.5 follow-up)")
    fun malformedContainerCrashesDecoderButMainProcessSurvives() {
        // bind, hand a container crafted to trip the codec, expect RemoteException folded to
        // DecoderUnavailable; rebind and decode a benign image to assert recovery.
    }

    @Test
    @Ignore("Pending on-device CI wiring (wpass-zrt.5 follow-up)")
    fun decodeServiceReturnsNoBitmapOrSourceBytesOverBinder() {
        // bind, decode, and reflect over the reply parcel / result type to assert no Bitmap
        // and no byte[] cross the binder — the runtime companion to the surface lock in
        // BarcodeDecodeBinderSurfaceTest.
    }
}
