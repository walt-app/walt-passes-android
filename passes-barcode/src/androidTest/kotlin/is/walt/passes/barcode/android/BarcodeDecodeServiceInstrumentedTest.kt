package `is`.walt.passes.barcode.android

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import `is`.walt.passes.core.BarcodeDecodeResult
import `is`.walt.passes.core.DecodeFailureReason
import `is`.walt.passes.core.ScannableFormat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * On-device half of the wpass-zrt.5 security suite: what only a real device can prove — that
 * the real codec, driven across the real bind into the real isolated process, upholds the
 * contracts the JVM suites pin (faithfulness, caps, surface lock, allowlist). Drivable
 * scenarios go through the public [BarcodeImageDecoder] facade end-to-end with no test seam;
 * fixtures are generated in-process (ZXing writer → [Bitmap] → PNG fd, or crafted raw PNG
 * bytes for the canvas bomb) so the corpus is auditable in source.
 *
 * The two runtime-isolation scenarios that the facade deliberately gives the caller no handle
 * for — [decodeProcessCannotReachAppDataOrKeystore] and
 * [decodeServiceReturnsNoBitmapOrSourceBytesOverBinder] — drop below the facade: the first
 * binds a test-only `isolatedProcess` probe ([BarcodeSandboxProbeService]) to run privilege
 * checks from inside the sandbox; the second raw-`transact`s the real service and inspects the
 * reply parcel. They are the runtime companions to the static pins in [ManifestPermissionsTest]
 * and [BarcodeDecodeBinderSurfaceTest].
 *
 * These run in CI's connected-tests matrix (API 28/31/34/36) and are verified on a physical
 * device. None are `@Ignore`'d: `./gradlew check` does not run androidTest, so a workstation
 * with no device stays green regardless; only the managed-device / `connectedAndroidTest`
 * tasks execute them.
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
        // before the full bitmap is allocated — the decompression-bomb bucket. The companion
        // under-per-side-cap area bomb is [overAreaImageRejectsWithImageTooLarge].
        val overSide = BarcodeDecodeConfig.DEFAULT_MAX_DIMENSION_PX + 1
        val pfd = pngFd("over-dimension", solidBitmap(overSide, 8))
        val result = pfd.use { decode(it) }

        assertThat(result)
            .isEqualTo(BarcodeDecodeResult.DecodeFailed(DecodeFailureReason.ImageTooLarge))
    }

    @Test
    fun overAreaImageRejectsWithImageTooLarge() {
        // Each side stays under the per-side cap but area exceeds the megapixel cap; crafted as
        // raw PNG so the header rejects before the ~200 MB allocation a real bitmap would need.
        val side = 8_000 // 8000 * 8000 = 64 MP > 50 MP area cap; 8000 < 12000 per-side cap
        val png = hugeCanvasPng(side, side)
        assertThat(png.size).isLessThan(1_024) // genuinely a small file
        val file = File.createTempFile("area-bomb", ".png", context.cacheDir).apply { writeBytes(png) }
        val result =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { decode(it) }

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
    fun zeroByteSourceFailsClosed() {
        // The 0-byte corpus case: an empty descriptor has no decodable image and must fail
        // closed, never crash the sandbox or the caller.
        val empty = File.createTempFile("empty", ".png", context.cacheDir)
        val result =
            ParcelFileDescriptor.open(empty, ParcelFileDescriptor.MODE_READ_ONLY).use { decode(it) }

        assertThat(result).isInstanceOf(BarcodeDecodeResult.DecodeFailed::class.java)
    }

    @Test
    fun truncatedPngFailsClosed() {
        // A truncated container (valid PNG header, body cut off mid-stream) is the corrupt-image
        // corpus case: the decoder must fail closed rather than read past the bytes it has.
        val full = ByteArrayOutputStream()
            .also { qrBitmap("TRUNCATED").compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
        val truncated = full.copyOf(full.size / 2)
        val file = File.createTempFile("truncated", ".png", context.cacheDir).apply { writeBytes(truncated) }
        val result =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { decode(it) }

        assertThat(result).isInstanceOf(BarcodeDecodeResult.DecodeFailed::class.java)
    }

    @Test
    fun decodeProcessCannotReachAppDataOrKeystore() {
        // Headline runtime go/no-go (walt-android wlt-58a.1): from inside the isolated probe,
        // app data, Keystore, and network are all blocked and the checks ran in an isolated UID
        // — the runtime proof of what ManifestPermissionsTest pins statically.
        val sentinel = File(context.filesDir, "sandbox-sentinel.txt").apply { writeText("app-only-secret") }
        val (binder, conn) = bind(BarcodeSandboxProbeService::class.java)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeString(sentinel.absolutePath)
            check(binder.transact(BarcodeSandboxProbeService.CODE_PROBE, data, reply, 0)) {
                "probe transact not handled"
            }
            val probeUid = reply.readInt()
            val appDataBlocked = reply.readInt() == 1
            val keystoreBlocked = reply.readInt() == 1
            val networkBlocked = reply.readInt() == 1

            // Ran in an isolated UID, not the app's: app data, Keystore, and network are all out
            // of reach. (App IDs at/above 90000 are the isolated ranges — app-zygote 90000-98999
            // and regular isolated 99000-99999.)
            assertThat(probeUid).isNotEqualTo(Process.myUid())
            assertThat(probeUid % PER_USER_UID_RANGE).isAtLeast(FIRST_ISOLATED_APP_ID)
            assertThat(appDataBlocked).isTrue()
            assertThat(keystoreBlocked).isTrue()
            assertThat(networkBlocked).isTrue()
        } finally {
            data.recycle()
            reply.recycle()
            context.unbindService(conn)
            sentinel.delete()
        }
    }

    @Test
    fun decodeServiceReturnsNoBitmapOrSourceBytesOverBinder() {
        // Raw transact against the real service: only the pure {tag, payload, format} crosses
        // back with no trailing data — a Bitmap or source bytes would be orders larger. Runtime
        // companion to the surface lock in BarcodeDecodeBinderSurfaceTest.
        val pfd = pngFd("raw-transact", qrBitmap("RAW-TRANSACT-OK"))
        val (binder, conn) = bind(BarcodeDecodeService::class.java)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeTypedObject(pfd, 0)
            check(binder.transact(BarcodeDecodeBinderProxy.CODE_DECODE, data, reply, 0)) {
                "decode transact not handled"
            }

            assertThat(reply.readInt()).isEqualTo(BarcodeDecodeBinderProxy.TAG_DECODED)
            assertThat(reply.readString()).isEqualTo("RAW-TRANSACT-OK")
            assertThat(ScannableFormatWire.decode(reply.readInt())).isEqualTo(ScannableFormat.Qr)
            // Nothing smuggled after the documented result shape.
            assertThat(reply.dataAvail()).isEqualTo(0)
            assertThat(reply.dataSize()).isLessThan(MAX_PURE_REPLY_BYTES)
        } finally {
            data.recycle()
            reply.recycle()
            context.unbindService(conn)
            pfd.close()
        }
    }

    private fun decode(pfd: ParcelFileDescriptor): BarcodeDecodeResult =
        runBlocking {
            BarcodeImageDecoder.create(context).decode(BarcodeImageSource.FileDescriptor(pfd))
        }

    /** Bind [serviceClass] and block until connected, returning the binder and its connection. */
    private fun bind(serviceClass: Class<out Service>): Pair<IBinder, ServiceConnection> {
        val latch = CountDownLatch(1)
        var binder: IBinder? = null
        val conn =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                    binder = service
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
        check(context.bindService(Intent(context, serviceClass), conn, Context.BIND_AUTO_CREATE)) {
            "bindService failed for ${serviceClass.name}"
        }
        check(latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "service did not connect: ${serviceClass.name}"
        }
        return binder!! to conn
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

    /**
     * Craft raw PNG bytes whose IHDR declares a [width] x [height] canvas with a minimal IDAT.
     * The header listener rejects on dimensions before the IDAT is consumed, so the body only
     * has to be well-formed enough to reach that header callback — which is what keeps the
     * fixture a few hundred bytes instead of a multi-hundred-MB allocation.
     */
    private fun hugeCanvasPng(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        val ihdr =
            ByteArrayOutputStream().apply {
                writeIntBe(width)
                writeIntBe(height)
                write(8) // bit depth
                write(6) // color type: RGBA
                write(0) // compression
                write(0) // filter
                write(0) // interlace
            }.toByteArray()
        writeChunk(out, "IHDR", ihdr)
        writeChunk(out, "IDAT", deflate(byteArrayOf(0, 0, 0, 0)))
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.writeIntBe(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        out.writeIntBe(crc.value.toInt())
    }

    private fun ByteArrayOutputStream.writeIntBe(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater().apply { setInput(input); finish() }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }

    private companion object {
        const val BIND_TIMEOUT_SECONDS = 10L
        const val MAX_PURE_REPLY_BYTES = 256

        // Android packs (userId * 100000 + appId); an appId at/above the isolated floor proves
        // the UID is an isolated one rather than the app's own.
        const val PER_USER_UID_RANGE = 100_000
        const val FIRST_ISOLATED_APP_ID = 90_000

        val PNG_SIGNATURE =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
