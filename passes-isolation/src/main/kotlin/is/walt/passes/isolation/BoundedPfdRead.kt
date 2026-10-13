package `is`.walt.passes.isolation

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream

/**
 * Reads [pfd] from offset 0 with `pread`, never moving its shared offset; null once over [maxBytes].
 * On `ESPIPE` it streams a `dup()` from the current position instead; other errnos propagate.
 */
public fun readBoundedBytes(pfd: ParcelFileDescriptor, maxBytes: Long): ByteArray? =
    readBoundedBytes(pfd, maxBytes, DEFAULT_READ_CHUNK) { buffer, byteCount, position ->
        Os.pread(pfd.fileDescriptor, buffer, 0, byteCount, position)
    }

/** [readBoundedBytes] with the positioned-read primitive and chunk size injectable for tests. */
internal fun readBoundedBytes(
    pfd: ParcelFileDescriptor,
    maxBytes: Long,
    chunkSize: Int,
    pread: PositionedRead,
): ByteArray? =
    try {
        readBoundedChunks(maxBytes, chunkSize, pread)
    } catch (e: ErrnoException) {
        if (e.errno != OsConstants.ESPIPE) throw e
        ParcelFileDescriptor.AutoCloseInputStream(pfd.dup()).use { stream ->
            readBoundedChunks(maxBytes, chunkSize) { buffer, byteCount, _ -> stream.read(buffer, 0, byteCount) }
        }
    }

/** Reads up to [byteCount] bytes at [position] into the start of [buffer]; 0 or -1 at end of input. */
internal fun interface PositionedRead {
    fun read(buffer: ByteArray, byteCount: Int, position: Long): Int
}

internal fun readBoundedChunks(maxBytes: Long, chunkSize: Int, pread: PositionedRead): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(chunkSize)
    var total = 0L
    while (true) {
        // A short read is not the end; only 0 (pread) or -1 (a stream or JVM channel) is.
        val n = pread.read(buffer, buffer.size, total)
        if (n <= 0) break
        total += n
        if (total > maxBytes) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

private const val DEFAULT_READ_CHUNK = 64 * 1024
