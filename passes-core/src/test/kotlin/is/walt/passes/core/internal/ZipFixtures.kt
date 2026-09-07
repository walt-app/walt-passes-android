package `is`.walt.passes.core.internal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * In-memory ZIP fixtures shared by the extractor and bundle-reader tests. Archives are
 * synthesized with ZipOutputStream so every hostile shape is readable source, not a
 * checked-in binary.
 */

internal fun buildArchive(block: ArchiveBuilder.() -> Unit): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos -> ArchiveBuilder(zos).block() }
    return baos.toByteArray()
}

internal class ArchiveBuilder(private val zos: ZipOutputStream) {
    fun entry(
        name: String,
        content: ByteArray,
    ) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content)
        zos.closeEntry()
    }

    fun directory(name: String) {
        require(name.endsWith('/')) { "directory entries must end with '/'" }
        zos.putNextEntry(ZipEntry(name))
        zos.closeEntry()
    }
}

/**
 * Splices the local-file-header bodies of two single-entry archives ahead of the first
 * archive's central directory, since [ZipOutputStream] refuses duplicate names itself.
 * The JDK's ZipInputStream reads local headers sequentially without consulting the
 * central directory, so without an explicit duplicate check the second entry would win.
 */
internal fun buildArchiveWithDuplicateEntry(
    name: String,
    first: ByteArray,
    second: ByteArray,
): ByteArray {
    val archiveA = buildArchive { entry(name, first) }
    val archiveB = buildArchive { entry(name, second) }
    val cdA = findCentralDirectoryOffset(archiveA)
    val cdB = findCentralDirectoryOffset(archiveB)
    val out = ByteArrayOutputStream()
    out.write(archiveA, 0, cdA)
    out.write(archiveB, 0, cdB)
    out.write(archiveA, cdA, archiveA.size - cdA)
    return out.toByteArray()
}

/** Byte offset of the [n]-th (1-based) local file header signature. */
internal fun findNthLocalHeaderOffset(
    bytes: ByteArray,
    n: Int,
): Int = findNthSignature(bytes, LOCAL_HEADER_SIGNATURE, n) ?: error("fewer than $n local headers in synthetic archive")

internal fun findCentralDirectoryOffset(bytes: ByteArray): Int =
    findNthSignature(bytes, CENTRAL_DIRECTORY_SIGNATURE, 1) ?: error("no central directory in synthetic archive")

private fun findNthSignature(
    bytes: ByteArray,
    sig: ByteArray,
    n: Int,
): Int? {
    var remaining = n
    for (i in 0..bytes.size - sig.size) {
        if (sig.indices.all { k -> bytes[i + k] == sig[k] } && --remaining == 0) return i
    }
    return null
}

private val LOCAL_HEADER_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
private val CENTRAL_DIRECTORY_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x01, 0x02)

/** Tracks bytes pulled and whether [close] was called, to verify "caller owns the stream". */
internal class OpenTrackingInputStream(private val delegate: InputStream) : InputStream() {
    var closed: Boolean = false
        private set
    var bytesRead: Long = 0
        private set

    override fun read(): Int {
        val b = delegate.read()
        if (b != -1) bytesRead += 1
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) bytesRead += n
        return n
    }

    override fun close() {
        closed = true
        delegate.close()
    }
}

/** A caller stream that fails on its first read, e.g. a content provider that went away. */
internal class ThrowingInputStream : InputStream() {
    override fun read(): Int = throw IOException("stream unavailable")
}
