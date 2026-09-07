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
 *  - **Path traversal (zip-slip).** [isUnsafeEntryName] rejects entry names containing
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
    // would silently let the first 4 sniffed bytes — and any others the buffer prefetched
    // — escape the maxArchiveBytes accounting.
    val sniffer = BufferedInputStream(rawStream)
    if (!hasZipMagic(sniffer)) return ExtractResult.Failure(MalformedReason.NotAZipArchive)
    val bounded = BoundedInputStream(sniffer, config.maxArchiveBytes)
    return try {
        // [BoundedInputStream.bytesRead] is read post-`use`: it accumulates as
        // [ZipInputStream] pulls bytes through and is final once the stream closes.
        // Plumbed into [ExtractResult.Success.archiveBytes] for telemetry on stream
        // sources whose size hint was absent.
        val outcome = ZipInputStream(bounded).use { zis -> extractAllEntries(zis, config) }
        when (outcome) {
            is ExtractResult.Success -> outcome.copy(archiveBytes = bounded.bytesRead)
            is ExtractResult.Failure -> outcome
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
        entry.isDirectory -> {
            zis.closeEntry()
            null
        }
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

private val ALLOWED_EXTENSIONS = setOf("json", "png", "strings")
