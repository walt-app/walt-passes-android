package `is`.walt.passes.core.internal

import `is`.walt.passes.core.PassSource
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/*
 * ZIP stream plumbing shared by the single-archive extractor ([extractSafely]) and the
 * outer-layer bundle reader ([DefaultPassBundleReader]). Mechanism only: every cap and
 * rejection policy stays with the caller.
 */

/** The size the source claims up front, or null for a hint-less stream. Never trusted alone. */
internal fun PassSource.declaredSizeBytes(): Long? =
    when (this) {
        is PassSource.Bytes -> bytes.size.toLong()
        is PassSource.Stream -> sizeHintBytes
    }

/** Opens the source without taking ownership of a caller-supplied stream. */
internal fun PassSource.openStream(): InputStream =
    when (this) {
        is PassSource.Bytes -> ByteArrayInputStream(bytes)
        is PassSource.Stream -> NonClosingInputStream(stream)
    }

/**
 * True if the next 4 bytes of [stream] are a local-file-header signature (`PK\x03\x04`)
 * or an end-of-central-directory signature (`PK\x05\x06`). The EOCD prefix is legal only
 * for a structurally valid empty archive (no local file headers, just the EOCD record); a
 * non-empty zip starts with a local file header. Anything else with `PK\x05\x06` at the
 * front is rejected by [ZipInputStream] on the next read. Leaves the stream re-positioned
 * at byte 0 so the subsequent [ZipInputStream] reads the same bytes the sniff observed.
 * A stream that cannot be read has no ZIP magic: an [IOException] here is `false`, so
 * neither reader lets a caller stream's failure escape its never-throws contract.
 */
internal fun hasZipMagic(stream: BufferedInputStream): Boolean =
    try {
        readsZipMagic(stream)
    } catch (_: IOException) {
        false
    }

private fun readsZipMagic(stream: BufferedInputStream): Boolean {
    stream.mark(MAGIC_PREFIX_LENGTH)
    val head = ByteArray(MAGIC_PREFIX_LENGTH)
    var read = 0
    while (read < head.size) {
        val n = stream.read(head, read, head.size - read)
        if (n == -1) break
        read += n
    }
    stream.reset()
    return read == MAGIC_PREFIX_LENGTH &&
        (head.contentEquals(LOCAL_FILE_HEADER_MAGIC) || head.contentEquals(END_OF_CENTRAL_DIR_MAGIC))
}

/**
 * Structural zip-slip check shared with the bundle reader: `..` / `.` segments, absolute
 * paths, backslashes, Windows drive letters, empty names or segments. No file-system
 * canonicalization, because nothing here ever touches the file system.
 */
internal fun isUnsafeEntryName(name: String): Boolean {
    val isWindowsAbsolute = name.length >= 2 && name[1] == ':'
    // Strip a single trailing `/` so a legitimate directory entry name like
    // "en.lproj/" doesn't trip the empty-segment check on the trailing split slot.
    // Empty intermediate segments ("foo//bar") and a bare "/" still fail.
    val canonical = name.trimEnd('/')
    return name.isEmpty() ||
        canonical.startsWith('/') ||
        canonical.contains('\\') ||
        isWindowsAbsolute ||
        canonical.split('/').any { it == ".." || it == "." || it.isEmpty() }
}

/**
 * Tracks bytes pulled from the underlying compressed stream and short-circuits with
 * [ArchiveSizeExceededException] the moment the cumulative count crosses the budget.
 * Throwing instead of returning -1 keeps the failure mode unambiguous: a normal
 * end-of-stream is still distinguishable from "hostile archive went past the cap".
 */
internal class BoundedInputStream(
    delegate: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(delegate) {
    private var count: Long = 0

    /**
     * Cumulative bytes pulled through this wrapper. Read after the wrapping
     * [ZipInputStream] closes to recover an honest "bytes consumed" count for
     * telemetry on stream sources without a caller-supplied size hint.
     */
    val bytesRead: Long get() = count

    override fun read(): Int {
        val b = `in`.read()
        if (b != -1) {
            count += 1
            if (count > maxBytes) throw ArchiveSizeExceededException()
        }
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val n = `in`.read(b, off, len)
        if (n > 0) {
            count += n
            if (count > maxBytes) throw ArchiveSizeExceededException()
        }
        return n
    }
}

/**
 * A passthrough that ignores [close]. [`is`.walt.passes.core.PassParser]'s and
 * [`is`.walt.passes.core.PassBundleReader]'s contracts leave the caller-owned
 * [PassSource.Stream.stream] open; [ZipInputStream.use] would otherwise close it.
 */
internal class NonClosingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
    override fun close() {
        // No-op. Caller owns the underlying stream's lifecycle.
    }
}

internal class ArchiveSizeExceededException : IOException()

internal const val READ_BUFFER_SIZE: Int = 8 * 1024
private const val MAGIC_PREFIX_LENGTH = 4
internal val LOCAL_FILE_HEADER_MAGIC: ByteArray = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
private const val LOCAL_HEADER_FIXED_LENGTH = 30
private const val LOCAL_HEADER_NAME_LENGTH_OFFSET = 26
private val END_OF_CENTRAL_DIR_MAGIC = byteArrayOf(0x50, 0x4B, 0x05, 0x06)

/**
 * Name of the first local file header in [header], or null when the bytes do not start
 * with `PK\x03\x04`, declare an empty name, or are too short to hold the whole name.
 * Decoded as ISO-8859-1: the caller only inspects an ASCII suffix, and a byte-preserving
 * decode cannot throw on a hostile name.
 */
internal fun sniffFirstLocalHeaderName(header: ByteArray): String? {
    if (header.size < LOCAL_HEADER_FIXED_LENGTH || !hasLocalFileHeaderMagic(header)) return null
    val low = header[LOCAL_HEADER_NAME_LENGTH_OFFSET].toInt() and 0xFF
    val high = header[LOCAL_HEADER_NAME_LENGTH_OFFSET + 1].toInt() and 0xFF
    val nameLength = low or (high shl 8)
    val end = LOCAL_HEADER_FIXED_LENGTH + nameLength
    return if (nameLength == 0 || header.size < end) {
        null
    } else {
        String(header, LOCAL_HEADER_FIXED_LENGTH, nameLength, Charsets.ISO_8859_1)
    }
}

private fun hasLocalFileHeaderMagic(header: ByteArray): Boolean =
    LOCAL_FILE_HEADER_MAGIC.indices.all { header[it] == LOCAL_FILE_HEADER_MAGIC[it] }
