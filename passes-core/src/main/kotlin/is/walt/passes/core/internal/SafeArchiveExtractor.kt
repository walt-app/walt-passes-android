package `is`.walt.passes.core.internal

import `is`.walt.passes.core.MalformedReason
import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.core.PassSource
import `is`.walt.passes.core.ResourceLimit
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * The single hardened ZIP-extraction entry point shared by passes-core. Every guard the
 * threat model lists for untrusted PKPASS input is centralized here:
 *
 *  - **Magic-byte preflight.** [ZipInputStream] silently treats unrecognized leading bytes
 *    as "no entries", which would let raw garbage and 0-byte input round-trip as
 *    [ExtractResult.Success] with an empty map. The first 4 bytes are sniffed and
 *    anything that isn't a local-file-header (`PK\x03\x04`) or end-of-central-directory
 *    (`PK\x05\x06` — a legitimate empty archive) signature is rejected up front.
 *  - **Archive size** (compressed). Checked twice. Once up-front against the declared size
 *    ([PassSource.Bytes.bytes].size or [PassSource.Stream.sizeHintBytes]). Then again at
 *    streaming time via [BoundedInputStream], which throws as soon as a read pushes the
 *    cumulative byte count past [ParserConfig.maxArchiveBytes]. The streaming check is
 *    load-bearing: a hostile [PassSource.Stream] can lie about its
 *    [PassSource.Stream.sizeHintBytes].
 *  - **Entry count.** [ParserConfig.maxEntries] caps the number of file entries surfaced
 *    to the caller. Directory entries are skipped before the count, so a bag of nested
 *    `.lproj/` directories cannot push a real archive past the cap.
 *  - **Per-entry decompressed size.** [readEntryBytes] caps each entry at
 *    [ParserConfig.maxEntryBytes]. This is the zip-bomb guard: a 10 KB compressed entry
 *    that decompresses to 10 GB hits the cap and aborts before the buffer materializes.
 *  - **Path traversal (zip-slip).** [pathTraversalReason] rejects entry names containing
 *    `..` or `.` segments, leading `/` (absolute path), backslashes (Windows-flavored
 *    separator), Windows drive-letter prefixes, or empty segments. Structural — no
 *    file-system canonicalization, because we never touch the file system.
 *  - **Symlink-shaped entries.** With JDK-only zip APIs (no Apache Commons Compress in
 *    this module's deps; see `gradle/libs.versions.toml`) the file-mode bits used to
 *    detect Info-ZIP symlink entries live in the central directory's external file
 *    attributes, which [ZipInputStream] does not expose. Mitigation: extraction is
 *    in-memory only; [readEntryBytes] writes into a [ByteArrayOutputStream] and we never
 *    invoke any file system operation that could resolve a symlink. Combined with the
 *    path-traversal check and extension allowlist, this is sufficient for the trust
 *    claim. A follow-up bead may swap in a parser that exposes external attributes if
 *    true symlink rejection is wanted.
 *  - **Extension allowlist.** Entry names must end in `.json`, `.png`, or `.strings`, OR
 *    be exactly `signature` at the archive root (the PKCS#7 detached-signature blob has
 *    no extension by PKPASS convention; the exemption is intentionally root-only so an
 *    attacker can't smuggle arbitrary content under a nested `signature` name). Anything
 *    else is rejected before any bytes are decompressed.
 *  - **Duplicate entry names.** Two entries with the same name are rejected. PKPASS does
 *    not permit duplicates and JDK [ZipInputStream] would silently let the second one
 *    win, which would let an attacker shadow a legitimate `manifest.json` with a
 *    tampered second copy.
 *  - **In-memory only.** No [java.io.FileOutputStream] is ever opened on an entry name.
 *    The entire archive is materialized into a [Map] of [ByteArray] values bounded by
 *    the per-entry cap, so a hostile name has no path on which to land even if the
 *    path-traversal check is somehow bypassed.
 *
 * Limit hits surface as [MalformedReason.ResourceLimitExceeded] with the relevant
 * [ResourceLimit] value. Structural rejections (path traversal, disallowed extension,
 * duplicate name) currently surface as [MalformedReason.NotAZipArchive] because the
 * public [MalformedReason] surface is frozen for this slice; a follow-up bead adds a
 * dedicated [MalformedReason] arm.
 */
internal fun extractSafely(
    source: PassSource,
    config: ParserConfig,
): ExtractResult {
    val declaredSize: Long? =
        when (source) {
            is PassSource.Bytes -> source.bytes.size.toLong()
            is PassSource.Stream -> source.sizeHintBytes
        }
    return if (declaredSize != null && declaredSize > config.maxArchiveBytes) {
        ExtractResult.Failure(MalformedReason.ResourceLimitExceeded(ResourceLimit.ArchiveSize))
    } else {
        runZipPipeline(openSource(source), config)
    }
}

private fun openSource(source: PassSource): InputStream =
    when (source) {
        is PassSource.Bytes -> ByteArrayInputStream(source.bytes)
        is PassSource.Stream -> NonClosingInputStream(source.stream)
    }

private fun runZipPipeline(
    rawStream: InputStream,
    config: ParserConfig,
): ExtractResult {
    val sniffer = BufferedInputStream(rawStream)
    val magicCheck = looksLikeZip(sniffer)
    if (magicCheck != null) return ExtractResult.Failure(magicCheck)
    val bounded = BoundedInputStream(sniffer, config.maxArchiveBytes)
    return try {
        ZipInputStream(bounded).use { zis -> extractAllEntries(zis, config) }
    } catch (_: ArchiveSizeExceededException) {
        ExtractResult.Failure(MalformedReason.ResourceLimitExceeded(ResourceLimit.ArchiveSize))
    } catch (_: ZipException) {
        ExtractResult.Failure(MalformedReason.NotAZipArchive)
    } catch (_: IOException) {
        ExtractResult.Failure(MalformedReason.NotAZipArchive)
    }
}

/**
 * Returns [MalformedReason.NotAZipArchive] if the next 4 bytes of [stream] are neither a
 * local-file-header signature (`PK\x03\x04`) nor an end-of-central-directory signature
 * (`PK\x05\x06`). Leaves the stream re-positioned at byte 0 so the subsequent
 * [ZipInputStream] reads the same bytes the sniff observed.
 */
private fun looksLikeZip(stream: BufferedInputStream): MalformedReason? {
    stream.mark(MAGIC_PREFIX_LENGTH)
    val head = ByteArray(MAGIC_PREFIX_LENGTH)
    var read = 0
    while (read < head.size) {
        val n = stream.read(head, read, head.size - read)
        if (n == -1) break
        read += n
    }
    stream.reset()
    val matches =
        read == MAGIC_PREFIX_LENGTH &&
            (head.contentEquals(LOCAL_FILE_HEADER_MAGIC) || head.contentEquals(END_OF_CENTRAL_DIR_MAGIC))
    return if (matches) null else MalformedReason.NotAZipArchive
}

private fun extractAllEntries(
    zis: ZipInputStream,
    config: ParserConfig,
): ExtractResult {
    val entries = LinkedHashMap<String, ByteArray>()
    var failure: ExtractResult.Failure? = null
    while (failure == null) {
        val entry = zis.nextEntry ?: break
        failure = processEntry(zis, entry, entries, config)
    }
    return failure ?: ExtractResult.Success(entries.toMap())
}

private fun processEntry(
    zis: ZipInputStream,
    entry: ZipEntry,
    entries: MutableMap<String, ByteArray>,
    config: ParserConfig,
): ExtractResult.Failure? {
    if (entry.isDirectory) {
        zis.closeEntry()
        return null
    }
    return validateAndRead(zis, entry.name, entries, config)
}

private fun validateAndRead(
    zis: ZipInputStream,
    name: String,
    entries: MutableMap<String, ByteArray>,
    config: ParserConfig,
): ExtractResult.Failure? {
    val rejection =
        pathTraversalReason(name)
            ?: extensionReason(name)
            ?: duplicateEntryReason(entries, name)
            ?: entryCountReason(entries, config)
    return rejection?.let { ExtractResult.Failure(it) }
        ?: readEntryAndStore(zis, name, entries, config.maxEntryBytes)
}

private fun extensionReason(name: String): MalformedReason? {
    return if (hasAllowedName(name)) null else MalformedReason.NotAZipArchive
}

private fun duplicateEntryReason(
    entries: Map<String, ByteArray>,
    name: String,
): MalformedReason? {
    return if (entries.containsKey(name)) MalformedReason.NotAZipArchive else null
}

private fun entryCountReason(
    entries: Map<String, ByteArray>,
    config: ParserConfig,
): MalformedReason? {
    return if (entries.size >= config.maxEntries) {
        MalformedReason.ResourceLimitExceeded(ResourceLimit.EntryCount)
    } else {
        null
    }
}

private fun pathTraversalReason(name: String): MalformedReason? {
    val isWindowsAbsolute = name.length >= 2 && name[1] == ':'
    val unsafe =
        name.isEmpty() ||
            name.startsWith('/') ||
            name.contains('\\') ||
            isWindowsAbsolute ||
            name.split('/').any { it == ".." || it == "." || it.isEmpty() }
    return if (unsafe) MalformedReason.NotAZipArchive else null
}

private fun hasAllowedName(name: String): Boolean {
    // The PKCS#7 signature file ("signature") is the only PKPASS member with no
    // extension. Allow it only at the archive root: a nested `nested/signature` is
    // treated as a disallowed-extension entry, not a smuggled signature exemption.
    if (name == SIGNATURE_FILE_NAME) return true
    val baseName = name.substringAfterLast('/')
    val lastDot = baseName.lastIndexOf('.')
    return lastDot >= 0 && baseName.substring(lastDot + 1).lowercase() in ALLOWED_EXTENSIONS
}

private fun readEntryAndStore(
    zis: ZipInputStream,
    name: String,
    entries: MutableMap<String, ByteArray>,
    maxEntryBytes: Long,
): ExtractResult.Failure? {
    val bytes =
        readEntryBytes(zis, maxEntryBytes)
            ?: return ExtractResult.Failure(MalformedReason.ResourceLimitExceeded(ResourceLimit.EntrySize))
    entries[name] = bytes
    return null
}

private fun readEntryBytes(
    zis: ZipInputStream,
    maxEntryBytes: Long,
): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_SIZE)
    var totalRead = 0L
    while (true) {
        val n = zis.read(buffer)
        if (n == -1) return output.toByteArray()
        totalRead += n
        if (totalRead > maxEntryBytes) return null
        output.write(buffer, 0, n)
    }
}

/**
 * Tracks bytes pulled from the underlying compressed stream and short-circuits with
 * [ArchiveSizeExceededException] the moment the cumulative count crosses the budget.
 * Throwing instead of returning -1 keeps the failure mode unambiguous: a normal
 * end-of-stream is still distinguishable from "hostile archive went past the cap".
 */
private class BoundedInputStream(
    delegate: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(delegate) {
    private var count: Long = 0

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
 * A passthrough that ignores [close]. [`is`.walt.passes.core.PassParser]'s contract leaves
 * the caller-owned [PassSource.Stream.stream] open; [ZipInputStream.use] would otherwise
 * close it for us.
 */
private class NonClosingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
    override fun close() {
        // No-op. Caller owns the underlying stream's lifecycle.
    }
}

private class ArchiveSizeExceededException : IOException()

private const val READ_BUFFER_SIZE = 8 * 1024
private const val SIGNATURE_FILE_NAME = "signature"
private const val MAGIC_PREFIX_LENGTH = 4
private val LOCAL_FILE_HEADER_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
private val END_OF_CENTRAL_DIR_MAGIC = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
private val ALLOWED_EXTENSIONS = setOf("json", "png", "strings")
