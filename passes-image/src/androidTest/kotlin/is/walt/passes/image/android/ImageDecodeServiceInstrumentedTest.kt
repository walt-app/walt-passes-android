package `is`.walt.passes.image.android

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
 * On-device half of the wpass-6yp security suite: what only a real device can prove — that the
 * real platform codec, driven across the real bind into the real isolated process, upholds the
 * contracts the JVM suites pin (bounded raster out, caps enforced, surface lock, sandbox
 * isolation). Drivable scenarios go through the public [BoundedImageDecoder] facade end-to-end
 * with no test seam; fixtures are generated in-process (Bitmap → PNG fd, or crafted raw PNG
 * bytes for the canvas bomb) so the corpus is auditable in source. Mirrors
 * `passes-barcode`'s `BarcodeDecodeServiceInstrumentedTest`.
 *
 * The two runtime-isolation scenarios the facade gives no handle for —
 * [decodeProcessCannotReachAppDataOrKeystore] and
 * [decodeServiceReturnsBoundedRasterNotSourceBytesOverBinder] — drop below the facade: the
 * first binds a test-only `isolatedProcess` probe ([ImageSandboxProbeService]); the second
 * raw-`transact`s the real service and inspects the reply parcel. They are the runtime
 * companions to the static pins in [ManifestPermissionsTest] and [ImageDecodeBinderSurfaceTest].
 *
 * These run in CI's connected-tests matrix (API 28/31/34/36). None are `@Ignore`'d: `./gradlew
 * check` does not run androidTest, so a workstation with no device stays green regardless; only
 * the managed-device / `connectedAndroidTest` tasks execute them.
 */
@RunWith(AndroidJUnit4::class)
class ImageDecodeServiceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun benignImageDecodesToABoundedRaster() {
        // A 200x100 (2:1) source requested into a 64x64 bound fits to 64x32, aspect preserved,
        // and the raster is returned over SharedMemory sized exactly to the output pixels.
        val pfd = pngFd("benign", solidBitmap(200, 100, Color.BLUE))
        val result = pfd.use { decode(it, maxWidthPx = 64, maxHeightPx = 64) }

        assertThat(result).isInstanceOf(ImageDecodeResult.Ok::class.java)
        val ok = result as ImageDecodeResult.Ok
        assertThat(ok.widthPx).isEqualTo(64)
        assertThat(ok.heightPx).isEqualTo(32)
        assertThat(ok.sourceAspect).isWithin(0.01f).of(2.0f)
        assertThat(ok.sharedMemory.size).isEqualTo(64 * 32 * 4)
        ok.sharedMemory.close()
    }

    @Test
    fun smallSourceIsNotUpscaled() {
        // A 10x10 source into a 256x256 bound stays 10x10 — the host scales up for display.
        val pfd = pngFd("tiny", solidBitmap(10, 10, Color.RED))
        val result = pfd.use { decode(it, maxWidthPx = 256, maxHeightPx = 256) }

        val ok = result as ImageDecodeResult.Ok
        assertThat(ok.widthPx).isEqualTo(10)
        assertThat(ok.heightPx).isEqualTo(10)
        ok.sharedMemory.close()
    }

    @Test
    fun overDimensionImageRejectsWithDimensionsTooLarge() {
        val overSide = ImageDecodeConfig.DEFAULT_MAX_DIMENSION_PX + 1
        val pfd = pngFd("over-dimension", solidBitmap(overSide, 8, Color.WHITE))
        val result = pfd.use { decode(it, maxWidthPx = 64, maxHeightPx = 64) }

        assertThat(result)
            .isEqualTo(ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DimensionsTooLarge))
    }

    @Test
    fun overAreaImageRejectsWithDimensionsTooLarge() {
        // Each side under the per-side cap but area over the megapixel cap; crafted as raw PNG
        // so the header rejects before the ~200 MB allocation a real bitmap would need.
        val side = 8_000 // 8000*8000 = 64 MP > 50 MP area cap; 8000 < 12000 per-side cap
        val png = hugeCanvasPng(side, side)
        assertThat(png.size).isLessThan(1_024)
        val file = File.createTempFile("area-bomb", ".png", context.cacheDir).apply { writeBytes(png) }
        val result =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                .use { decode(it, maxWidthPx = 64, maxHeightPx = 64) }

        assertThat(result)
            .isEqualTo(ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DimensionsTooLarge))
    }

    @Test
    fun malformedContainerFailsClosedAndNextDecodeSucceeds() {
        val junk = File.createTempFile("malformed", ".img", context.cacheDir)
            .apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        val malformed =
            ParcelFileDescriptor.open(junk, ParcelFileDescriptor.MODE_READ_ONLY)
                .use { decode(it, maxWidthPx = 64, maxHeightPx = 64) }
        assertThat(malformed).isInstanceOf(ImageDecodeResult.Rejected::class.java)

        val benign = pngFd("recovery", solidBitmap(40, 40, Color.GREEN))
        val recovered = benign.use { decode(it, maxWidthPx = 64, maxHeightPx = 64) }
        assertThat(recovered).isInstanceOf(ImageDecodeResult.Ok::class.java)
        (recovered as ImageDecodeResult.Ok).sharedMemory.close()
    }

    @Test
    fun zeroByteSourceFailsClosed() {
        val empty = File.createTempFile("empty", ".png", context.cacheDir)
        val result =
            ParcelFileDescriptor.open(empty, ParcelFileDescriptor.MODE_READ_ONLY)
                .use { decode(it, maxWidthPx = 64, maxHeightPx = 64) }

        assertThat(result).isInstanceOf(ImageDecodeResult.Rejected::class.java)
    }

    @Test
    fun truncatedPngFailsClosed() {
        val full = ByteArrayOutputStream()
            .also { solidBitmap(80, 80, Color.MAGENTA).compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
        val truncated = full.copyOf(full.size / 2)
        val file = File.createTempFile("truncated", ".png", context.cacheDir).apply { writeBytes(truncated) }
        val result =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                .use { decode(it, maxWidthPx = 64, maxHeightPx = 64) }

        assertThat(result).isInstanceOf(ImageDecodeResult.Rejected::class.java)
    }

    @Test
    fun decodeProcessCannotReachAppDataOrKeystore() {
        // Headline runtime go/no-go: from inside the isolated probe, app data, Keystore, and
        // network are all blocked and the checks ran in an isolated UID — the runtime proof of
        // what ManifestPermissionsTest pins statically.
        val sentinel = File(context.filesDir, "image-sandbox-sentinel.txt").apply { writeText("app-only-secret") }
        val (binder, conn) = bind(ImageSandboxProbeService::class.java)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeString(sentinel.absolutePath)
            check(binder.transact(ImageSandboxProbeService.CODE_PROBE, data, reply, 0)) {
                "probe transact not handled"
            }
            val probeUid = reply.readInt()
            val appDataBlocked = reply.readInt() == 1
            val keystoreBlocked = reply.readInt() == 1
            val networkBlocked = reply.readInt() == 1

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
    fun decodeServiceReturnsBoundedRasterNotSourceBytesOverBinder() {
        // Raw transact against the real service: the reply carries the {tag, SharedMemory fd,
        // dims, aspect} shape and nothing trailing. The raster crosses as an fd, never as inline
        // source bytes — a smuggled Bitmap or the original bytes would balloon dataSize. Runtime
        // companion to the surface lock in ImageDecodeBinderSurfaceTest.
        val pfd = pngFd("raw-transact", solidBitmap(120, 60, Color.CYAN))
        val (binder, conn) = bind(ImageDecodeService::class.java)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeTypedObject(pfd, 0)
            data.writeInt(64)
            data.writeInt(64)
            check(binder.transact(ImageDecodeBinderProxy.CODE_DECODE, data, reply, 0)) {
                "decode transact not handled"
            }

            assertThat(reply.readInt()).isEqualTo(ImageDecodeBinderProxy.TAG_OK)
            val sm = reply.readTypedObject(android.os.SharedMemory.CREATOR)
            assertThat(sm).isNotNull()
            val widthPx = reply.readInt()
            val heightPx = reply.readInt()
            reply.readFloat() // sourceAspect
            // The raster size is the output pixel buffer, carried out-of-line via the fd; the
            // parcel's own payload stays tiny and nothing is smuggled after the documented shape.
            assertThat(sm!!.size).isEqualTo(widthPx * heightPx * 4)
            assertThat(reply.dataAvail()).isEqualTo(0)
            assertThat(reply.dataSize()).isLessThan(MAX_PURE_REPLY_BYTES)
            sm.close()
        } finally {
            data.recycle()
            reply.recycle()
            context.unbindService(conn)
            pfd.close()
        }
    }

    private fun decode(pfd: ParcelFileDescriptor, maxWidthPx: Int, maxHeightPx: Int): ImageDecodeResult =
        runBlocking {
            BoundedImageDecoder.create(context).decode(ImageSource.FileDescriptor(pfd), maxWidthPx, maxHeightPx)
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

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

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
     * has to be well-formed enough to reach that header callback — which keeps the fixture a few
     * hundred bytes instead of a multi-hundred-MB allocation.
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

        const val PER_USER_UID_RANGE = 100_000
        const val FIRST_ISOLATED_APP_ID = 90_000

        val PNG_SIGNATURE =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
