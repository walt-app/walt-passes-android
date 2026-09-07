package `is`.walt.passes.core.internal

import `is`.walt.passes.core.MalformedReason
import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.core.PassSource
import `is`.walt.passes.core.ResourceLimit
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * The single hardened ZIP-extraction entry point for one PKPASS archive. Guards: magic-byte
 * preflight, compressed-size cap (declared size, then a streaming bound), entry count,
 * per-entry decompressed cap (directory payloads drained through it too), zip-slip names,
 * a root-only extension allowlist plus the bare `signature` file, duplicate names, and
 * in-memory-only extraction. The stream chain and the shared checks live in
 * `ArchiveStreams.kt`. Symlink attributes are invisible to `java.util.zip`; nothing here
 * touches the file system, so a symlink-shaped entry is plain bytes.
 *
 * Limit hits surface as [MalformedReason.ResourceLimitExceeded]; structural rejections
 * (path traversal, disallowed extension, duplicate name) surface as
 * [MalformedReason.NotAZipArchive] because the public [MalformedReason] surface is frozen
 * (ADR 0001).
 */
internal fun extractSafely(
    source: PassSource,
    config: ParserConfig,
): ExtractResult {
    val declaredSize = source.declaredSizeBytes()
    return if (declaredSize != null && declaredSize > config.maxArchiveBytes) {
        ExtractResult.Failure(MalformedReason.ResourceLimitExceeded(ResourceLimit.ArchiveSize))
    } else {
        runZipPipeline(source.openStream(), config)
    }
}

private fun runZipPipeline(
    rawStream: InputStream,
    config: ParserConfig,
): ExtractResult {
    // Wrapper order is load-bearing. The chain is:
    //   userStream -> NonClosingInputStream -> BufferedInputStream -> BoundedInputStream -> ZipInputStream
    // BoundedInputStream MUST sit *outside* BufferedInputStream so that bytes the sniff
    // pulled into the buffer still flow through the counter when ZipInputStream reads
    // them back. Reordering (e.g. moving BufferedInputStream above BoundedInputStream)
    // would silently let the first 4 sniffed bytes, and any others the buffer prefetched,
    // escape the maxArchiveBytes accounting.
    val sniffer = BufferedInputStream(rawStream)
    val bounded = BoundedInputStream(sniffer, config.maxArchiveBytes)
    return try {
        if (hasZipMagic(sniffer)) {
            extractBounded(bounded, config)
        } else {
            ExtractResult.Failure(MalformedReason.NotAZipArchive)
        }
    } catch (_: ArchiveSizeExceededException) {
        ExtractResult.Failure(MalformedReason.ResourceLimitExceeded(ResourceLimit.ArchiveSize))
    } catch (_: IOException) {
        ExtractResult.Failure(MalformedReason.NotAZipArchive)
    } catch (_: IllegalArgumentException) {
        // ZipInputStream decodes EFS-flagged names as strict UTF-8 and throws this on a bad byte.
        ExtractResult.Failure(MalformedReason.NotAZipArchive)
    }
}

private fun extractBounded(
    bounded: BoundedInputStream,
    config: ParserConfig,
): ExtractResult {
    // [BoundedInputStream.bytesRead] is read post-`use`: it accumulates as
    // [ZipInputStream] pulls bytes through and is final once the stream closes.
    // Plumbed into [ExtractResult.Success.archiveBytes] for telemetry on stream
    // sources whose size hint was absent.
    val outcome = ZipInputStream(bounded).use { zis -> extractAllEntries(zis, config) }
    return when (outcome) {
        is ExtractResult.Success -> outcome.copy(archiveBytes = bounded.bytesRead)
        is ExtractResult.Failure -> outcome
    }
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
    // archiveBytes is filled in by the caller from the BoundedInputStream counter
    // after the ZipInputStream closes; the value here is a placeholder.
    return failure ?: ExtractResult.Success(entries.toMap(), archiveBytes = 0L)
}

private fun processEntry(
    zis: ZipInputStream,
    entry: ZipEntry,
    entries: MutableMap<String, ByteArray>,
    config: ParserConfig,
): ExtractResult.Failure? {
    // Validate the name unconditionally, even for directory entries we'd otherwise
    // skip. A `../foo/` directory is harmless today (nothing acts on directory
    // entries), but checking up-front means a future change that does act on them
    // can't accidentally bypass the path-traversal guard.
    return when {
        isUnsafeEntryName(entry.name) -> ExtractResult.Failure(MalformedReason.NotAZipArchive)
        entry.isDirectory -> drainOrStore(zis, entry.name, entries = null, config.maxEntryBytes)
        else -> validateAndRead(zis, entry.name, entries, config)
    }
}

private fun validateAndRead(
    zis: ZipInputStream,
    name: String,
    entries: MutableMap<String, ByteArray>,
    config: ParserConfig,
): ExtractResult.Failure? {
    // isUnsafeEntryName already ran in processEntry; the chain picks up here.
    val rejection =
        extensionReason(name)
            ?: duplicateEntryReason(entries, name)
            ?: entryCountReason(entries, config)
    return rejection?.let { ExtractResult.Failure(it) }
        ?: drainOrStore(zis, name, entries, config.maxEntryBytes)
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

private fun hasAllowedName(name: String): Boolean {
    // The PKCS#7 signature file ("signature") is the only PKPASS member with no
    // extension. Allow it only at the archive root: a nested `nested/signature` is
    // treated as a disallowed-extension entry, not a smuggled signature exemption.
    if (name == SIGNATURE_FILE_NAME) return true
    val baseName = name.substringAfterLast('/')
    val lastDot = baseName.lastIndexOf('.')
    return lastDot >= 0 && baseName.substring(lastDot + 1).lowercase() in ALLOWED_EXTENSIONS
}

/**
 * Inflates the current entry under [maxEntryBytes], storing it under [name] when
 * [entries] is given and draining it otherwise (directory entries).
 */
private fun drainOrStore(
    zis: ZipInputStream,
    name: String,
    entries: MutableMap<String, ByteArray>?,
    maxEntryBytes: Long,
): ExtractResult.Failure? {
    val buffer = if (entries != null) ByteArrayOutputStream() else null
    return when (inflateBounded(zis, buffer, maxEntryBytes)) {
        null -> {
            if (entries != null && buffer != null) entries[name] = buffer.toByteArray()
            null
        }
        // No cumulative budget in single-archive mode, so CumulativeSize is unreachable; both are the entry cap.
        InflateLimit.EntrySize, InflateLimit.CumulativeSize ->
            ExtractResult.Failure(MalformedReason.ResourceLimitExceeded(ResourceLimit.EntrySize))
    }
}

private val ALLOWED_EXTENSIONS = setOf("json", "png", "strings")
