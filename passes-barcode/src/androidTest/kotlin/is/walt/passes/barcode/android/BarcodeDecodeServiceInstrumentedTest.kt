package `is`.walt.passes.barcode.android

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.DecodeFailureReason
import `is`.walt.passes.core.ScannableFormat
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device half of the wpass-zrt.5 security suite: what only a real device can prove — that
 * the real codec, driven across the real bind into the real isolated process, upholds the
 * contracts the JVM suites pin (faithfulness, caps, surface lock, allowlist). Drivable
 * scenarios go through the public [BarcodeImageDecoder] facade end-to-end with no test seam;
 * fixtures are generated in-process (ZXing writer → [Bitmap] → PNG fd) so the corpus is
 * auditable in source.
 *
 * Two scenarios stay documented skeletons because the facade deliberately gives the caller no
 * handle inside the sandbox: [decodeProcessCannotReachAppDataOrKeystore] needs code running in
 * the isolated process (a test-only `isolatedProcess` probe service), and
 * [decodeServiceReturnsNoBitmapOrSourceBytesOverBinder] needs a raw `transact` — both already
 * guaranteed statically by [ManifestPermissionsTest] / [BarcodeDecodeBinderSurfaceTest].
 *
 * The three drivable scenarios run in CI's connected-tests matrix (API 28/31/34/36) and
 * verified green on a physical device. They are not `@Ignore`'d: `./gradlew check` does not
 * run androidTest, so a workstation with no device stays green regardless; only the
 * managed-device / `connectedAndroidTest` tasks execute them. The two skeleton scenarios stay
 * `@Ignore`'d until their probe infrastructure lands (wpass-6mg).
 */
@RunWith(AndroidJUnit4::class)
class BarcodeDecodeServiceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun benignQrDecodesToPayloadAndFormat() {
        val pfd = pngFd("benign-qr", qrBitmap("WALT-PASS-9"))
        val result = pfd.use { decode(it) }

        assertThat(result)
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode("WALT-PASS-9", ScannableFormat.Qr))
    }

    @Test
    fun overDimensionImageRejectsWithImageTooLarge() {
        // A canvas past the per-side dimension cap must be rejected by the header listener
        // before the full bitmap is allocated — the decompression-bomb bucket. (The
        // small-file-huge-canvas PNG bomb that stays under the per-side cap needs a crafted
        // fixture; tracked with the corpus follow-up.)
        val overSide = BarcodeDecodeConfig.DEFAULT_MAX_DIMENSION_PX + 1
        val pfd = pngFd("over-dimension", solidBitmap(overSide, 8))
        val result = pfd.use { decode(it) }

        assertThat(result)
            .isEqualTo(BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.ImageTooLarge))
    }

    @Test
    fun malformedContainerFailsClosedAndNextDecodeSucceeds() {
        // Bytes that are not a decodable image must fail closed, not crash the caller. Then a
        // benign decode must succeed, proving the path recovers (per-call teardown + re-bind),
        // so one hostile image cannot wedge the decoder for the next one.
        val junk = File.createTempFile("malformed", ".img", context.cacheDir)
            .apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        val malformedResult =
            ParcelFileDescriptor.open(junk, ParcelFileDescriptor.MODE_READ_ONLY).use { decode(it) }
        assertThat(malformedResult).isInstanceOf(BarcodeDecodeResult.DecodeFailed::class.java)

        val benign = pngFd("recovery-qr", qrBitmap("RECOVERY-OK"))
        val benignResult = benign.use { decode(it) }
        assertThat(benignResult)
            .isEqualTo(BarcodeDecodeResult.DecodedBarcode("RECOVERY-OK", ScannableFormat.Qr))
    }

    @Test
    @Ignore("Needs a test-only isolatedProcess probe service (wpass-zrt.5 follow-up)")
    fun decodeProcessCannotReachAppDataOrKeystore() {
        // Bind a test-only probe service declared android:isolatedProcess="true" in the
        // androidTest manifest; from inside that process attempt a privileged call (open the
        // app's files dir / load an AndroidKeyStore entry / open the SQLCipher DB file) and
        // assert it fails with SecurityException (or FileNotFound for the unreachable
        // app-data path). The decode UID must reach none of them. This is the runtime
        // companion to the static manifest pin in ManifestPermissionsTest and the contractual
        // go/no-go boundary from walt-android wlt-58a.1.
    }

    @Test
    @Ignore("Structurally covered by BarcodeDecodeBinderSurfaceTest; on-device parcel probe is a follow-up")
    fun decodeServiceReturnsNoBitmapOrSourceBytesOverBinder() {
        // A raw transact (not the facade) against BarcodeDecodeService, reflecting over the
        // reply parcel to assert no Bitmap and no byte[] cross the binder — the runtime
        // companion to the surface lock in BarcodeDecodeBinderSurfaceTest.
    }

    private fun decode(pfd: ParcelFileDescriptor): BarcodeDecodeResult =
        runBlocking {
            BarcodeImageDecoder.create(context).decode(BarcodeImageSource.FileDescriptor(pfd))
        }

    private fun qrBitmap(payload: String): Bitmap {
        val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 480, 480)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun solidBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    /** Compress [bitmap] to a PNG temp file and open it read-only as a descriptor. */
    private fun pngFd(name: String, bitmap: Bitmap): ParcelFileDescriptor {
        val file = File.createTempFile(name, ".png", context.cacheDir)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
