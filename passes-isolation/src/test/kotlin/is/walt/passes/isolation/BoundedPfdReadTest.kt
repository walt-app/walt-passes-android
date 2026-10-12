package `is`.walt.passes.isolation

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * JVM coverage for [readBoundedBytes]. Robolectric backs `Os.pread` with a real positioned
 * `FileChannel` read, so the offset tests run against real file descriptors. Its pipes are
 * seekable, so the `ESPIPE` fallback is driven through an injected read that raises it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BoundedPfdReadTest {
    private val opened = mutableListOf<ParcelFileDescriptor>()
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        files.forEach { it.delete() }
    }

    // ------------------------------------------------------------------ size cap

    @Test
    fun returnsAllBytesWhenUnderCap() {
        val bytes = pattern(1000)
        assertThat(readBoundedBytes(fileWith(bytes), maxBytes = 4096)?.toList()).isEqualTo(bytes.toList())
    }

    @Test
    fun returnsExactlyAtCapBoundary() {
        assertThat(readBoundedBytes(fileWith(pattern(4096)), maxBytes = 4096)?.size).isEqualTo(4096)
    }

    @Test
    fun rejectsOneByteOverCap() {
        assertThat(readBoundedBytes(fileWith(pattern(4097)), maxBytes = 4096)).isNull()
    }

    @Test
    fun emptySourceReadsAsEmpty() {
        assertThat(readBoundedBytes(fileWith(ByteArray(0)), maxBytes = 4096)?.size).isEqualTo(0)
    }

    @Test
    fun readsASourceSpanningManyChunks() {
        val bytes = pattern(10_000)
        val read = readBoundedBytes(fileWith(bytes), maxBytes = 10_000, chunkSize = 64, pread = osPread(opened.last()))
        assertThat(read?.toList()).isEqualTo(bytes.toList())
    }

    // ------------------------------------------------------------ read loop shape

    @Test
    fun shortReadsAreNotMistakenForTheEnd() {
        val bytes = pattern(100)
        val positions = mutableListOf<Long>()
        val read =
            readBoundedChunks(maxBytes = 100, chunkSize = 16) { buffer, byteCount, position ->
                positions += position
                arraySource(bytes, maxPerCall = 3).read(buffer, byteCount, position)
            }
        assertThat(read?.toList()).isEqualTo(bytes.toList())
        assertThat(positions).isEqualTo((0L..99L step 3).toList() + 100L)
    }

    @Test
    fun eitherEndMarkerStopsTheRead() {
        val bytes = pattern(10)
        for (end in listOf(0, -1)) {
            val read =
                readBoundedChunks(maxBytes = 100, chunkSize = 4) { buffer, byteCount, position ->
                    if (position >= bytes.size) end else arraySource(bytes).read(buffer, byteCount, position)
                }
            assertThat(read?.toList()).isEqualTo(bytes.toList())
        }
    }

    @Test
    fun stopsReadingOnceTheCapIsExceeded() {
        var calls = 0
        val read =
            readBoundedChunks(maxBytes = 10, chunkSize = 4) { _, byteCount, _ ->
                calls++
                byteCount
            }
        assertThat(read).isNull()
        assertThat(calls).isEqualTo(3)
    }

    // ------------------------------------------------------------- offset independence

    @Test
    fun ignoresTheInheritedOffsetAndLeavesItUnchanged() {
        val bytes = pattern(64)
        val pfd = fileWith(bytes)
        Os.lseek(pfd.fileDescriptor, 40, OsConstants.SEEK_SET)

        assertThat(readBoundedBytes(pfd, maxBytes = 64)?.toList()).isEqualTo(bytes.toList())
        assertThat(Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR)).isEqualTo(40L)
        assertThat(pfd.fileDescriptor.valid()).isTrue()
    }

    @Test
    fun aReadInterleavedWithAnotherReadOfTheSameFdGetsItsOwnBytes() {
        // Reader B runs to completion, and the shared offset is moved, between reader A's chunks.
        val bytes = pattern(256)
        val pfd = fileWith(bytes)
        var readB: ByteArray? = null
        val readA =
            readBoundedBytes(pfd, maxBytes = 256, chunkSize = 32) { buffer, byteCount, position ->
                if (position == 32L) {
                    readB = readBoundedBytes(pfd, maxBytes = 256, chunkSize = 32, pread = osPread(pfd))
                    Os.lseek(pfd.fileDescriptor, 200, OsConstants.SEEK_SET)
                }
                osPread(pfd).read(buffer, byteCount, position)
            }

        assertThat(readA?.toList()).isEqualTo(bytes.toList())
        assertThat(readB?.toList()).isEqualTo(bytes.toList())
    }

    @Test
    fun concurrentReadsOfOneFdEachSeeTheWholeSource() {
        val bytes = pattern(64 * 1024)
        val pfd = fileWith(bytes)
        val max = bytes.size.toLong()
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val results =
                (1..threads).map {
                    pool.submit<List<ByteArray?>> {
                        barrier.await()
                        List(25) { readBoundedBytes(pfd, max, chunkSize = 512, pread = osPread(pfd)) }
                    }
                }
            results.flatMap { it.get(30, TimeUnit.SECONDS) }.forEach { assertThat(it).isEqualTo(bytes) }
        } finally {
            pool.shutdownNow()
        }
    }

    // ------------------------------------------------------------ non-seekable source

    @Test
    fun nonSeekableSourceFallsBackToAStreamReadAndKeepsTheSourceOpen() {
        val bytes = pattern(100)
        val readEnd = pipeWith(bytes)

        val read = readBoundedBytes(readEnd, maxBytes = 100, chunkSize = 16, pread = throwing(OsConstants.ESPIPE))

        assertThat(read?.toList()).isEqualTo(bytes.toList())
        assertThat(readEnd.fileDescriptor.valid()).isTrue()
    }

    @Test
    fun nonSeekableFallbackStillEnforcesTheCap() {
        val readEnd = pipeWith(pattern(100))
        val read = readBoundedBytes(readEnd, maxBytes = 99, chunkSize = 16, pread = throwing(OsConstants.ESPIPE))
        assertThat(read).isNull()
    }

    @Test
    fun otherReadErrorsPropagate() {
        val pfd = fileWith(pattern(10))
        val thrown = assertThrows(ErrnoException::class.java) {
            readBoundedBytes(pfd, maxBytes = 10, chunkSize = 16, pread = throwing(OsConstants.EIO))
        }
        assertThat(thrown.errno).isEqualTo(OsConstants.EIO)
    }

    // --------------------------------------------------------------------- helpers

    private fun pattern(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun fileWith(bytes: ByteArray): ParcelFileDescriptor {
        val file = File.createTempFile("bounded-pfd-read", ".bin").apply { writeBytes(bytes) }
        files += file
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { opened += it }
    }

    private fun pipeWith(bytes: ByteArray): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
        return pipe[0].also { opened += it }
    }

    private fun osPread(pfd: ParcelFileDescriptor) = PositionedRead { buffer, byteCount, position ->
        Os.pread(pfd.fileDescriptor, buffer, 0, byteCount, position)
    }

    private fun arraySource(bytes: ByteArray, maxPerCall: Int = Int.MAX_VALUE) =
        PositionedRead { buffer, byteCount, position ->
            val n = minOf(byteCount, maxPerCall, bytes.size - position.toInt())
            if (n <= 0) 0 else n.also { bytes.copyInto(buffer, 0, position.toInt(), position.toInt() + n) }
        }

    private fun throwing(errno: Int) = PositionedRead { _, _, _ -> throw ErrnoException("pread", errno) }
}
