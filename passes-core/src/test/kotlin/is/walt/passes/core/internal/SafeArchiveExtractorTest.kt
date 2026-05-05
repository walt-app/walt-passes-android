package `is`.walt.passes.core.internal

import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.MalformedReason
import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.core.PassSource
import `is`.walt.passes.core.ResourceLimit
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Behavior tests for the hardened ZIP extractor. Every malicious archive shape the
 * threat-model bead lists has a row here. Archives are synthesized in-memory via
 * [java.util.zip.ZipOutputStream] so the test corpus is checked-in source rather than
 * opaque binary fixtures: a reviewer can read each test and see exactly which guard is
 * being exercised.
 *
 * The current public-API freeze (ADR 0001) lacks dedicated [MalformedReason] arms for
 * path traversal / disallowed extension / duplicate entries, so the extractor maps those
 * structural rejections onto [MalformedReason.NotAZipArchive]. Each test that depends on
 * that mapping calls it out so a future bead that adds a `PathTraversal` arm can update
 * the assertion in lockstep with the surface change.
 */
class SafeArchiveExtractorTest {
    @Test
    fun happyPathExtractsAllAllowedEntries() {
        val zip =
            buildArchive {
                entry("pass.json", "{}".toByteArray())
                entry("manifest.json", "{}".toByteArray())
                entry("signature", byteArrayOf(0x30, 0x82.toByte()))
                entry("icon.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
                entry("en.lproj/pass.strings", "\"k\" = \"v\";".toByteArray())
            }
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        assertThat(result).isInstanceOf(ExtractResult.Success::class.java)
        val entries = (result as ExtractResult.Success).entries
        assertThat(entries.keys)
            .containsExactly("pass.json", "manifest.json", "signature", "icon.png", "en.lproj/pass.strings")
            .inOrder()
        assertThat(entries["pass.json"]).isEqualTo("{}".toByteArray())
    }

    @Test
    fun directoryEntriesAreSkippedAndDoNotCountTowardsCap() {
        val zip =
            buildArchive {
                directory("en.lproj/")
                entry("pass.json", "{}".toByteArray())
                entry("manifest.json", "{}".toByteArray())
                entry("signature", byteArrayOf(1))
                entry("en.lproj/pass.strings", "\"k\" = \"v\";".toByteArray())
            }
        // Cap exactly at the 4 file entries; the directory entry must not occupy a slot.
        val config = ParserConfig().copy(maxEntries = 4)
        val result = extractSafely(PassSource.Bytes(zip), config)
        assertThat(result).isInstanceOf(ExtractResult.Success::class.java)
        val entries = (result as ExtractResult.Success).entries
        assertThat(entries).hasSize(4)
        assertThat(entries.keys).doesNotContain("en.lproj/")
    }

    @Test
    fun streamSourceHappyPathLeavesUnderlyingStreamOpen() {
        val zip = buildArchive { entry("pass.json", "{}".toByteArray()) }
        val tracker = OpenTrackingInputStream(ByteArrayInputStream(zip))
        val result = extractSafely(PassSource.Stream(tracker, sizeHintBytes = zip.size.toLong()), ParserConfig())
        assertThat(result).isInstanceOf(ExtractResult.Success::class.java)
        assertThat(tracker.closed).isFalse()
    }

    @Test
    fun streamSourceFailurePathLeavesUnderlyingStreamOpen() {
        // Mid-archive failure path: a path-traversal entry trips well after ZipInputStream
        // is already pulling bytes through the wrapper chain. The NonClosingInputStream
        // contract must hold here too — caller still owns the stream's lifecycle even when
        // extraction aborts.
        val zip = buildArchive { entry("../etc/passwd.json", "pwned".toByteArray()) }
        val tracker = OpenTrackingInputStream(ByteArrayInputStream(zip))
        val result = extractSafely(PassSource.Stream(tracker, sizeHintBytes = zip.size.toLong()), ParserConfig())
        assertMalformed(result, MalformedReason.NotAZipArchive)
        assertThat(tracker.closed).isFalse()
    }

    @Test
    fun declaredSizeOverArchiveCapFailsFast() {
        // sizeHint > maxArchiveBytes — the underlying stream must not even be touched.
        val tracker = OpenTrackingInputStream(ByteArrayInputStream(ByteArray(0)))
        val config = ParserConfig().copy(maxArchiveBytes = 1024)
        val result =
            extractSafely(
                PassSource.Stream(tracker, sizeHintBytes = config.maxArchiveBytes + 1),
                config,
            )
        assertExceeded(result, ResourceLimit.ArchiveSize)
        assertThat(tracker.bytesRead).isEqualTo(0)
    }

    @Test
    fun streamingArchiveSizeOverflowFailsEvenWhenSizeHintLies() {
        // Build a normal-looking archive then claim sizeHint = 0. The streaming bound
        // catches the overflow on the first read past the cap.
        val zip =
            buildArchive {
                entry("pass.json", ByteArray(2_048) { 0x20 })
            }
        val config = ParserConfig().copy(maxArchiveBytes = 64)
        val result =
            extractSafely(
                PassSource.Stream(ByteArrayInputStream(zip), sizeHintBytes = null),
                config,
            )
        assertExceeded(result, ResourceLimit.ArchiveSize)
    }

    @Test
    fun bytesArchiveOverCapFailsFast() {
        val zip = buildArchive { entry("pass.json", ByteArray(4_096) { 0x20 }) }
        val config = ParserConfig().copy(maxArchiveBytes = 64)
        val result = extractSafely(PassSource.Bytes(zip), config)
        assertExceeded(result, ResourceLimit.ArchiveSize)
    }

    @Test
    fun oversizedSingleEntryTripsEntrySizeLimit() {
        val zip = buildArchive { entry("icon.png", ByteArray(8_192) { 0x42 }) }
        val config = ParserConfig().copy(maxEntryBytes = 1_024)
        val result = extractSafely(PassSource.Bytes(zip), config)
        assertExceeded(result, ResourceLimit.EntrySize)
    }

    @Test
    fun zipBombStyleHighlyCompressibleEntryHitsEntrySizeLimit() {
        // 1 MB of zeros compresses to ~1 KB. With maxEntryBytes = 4 KB the decompressed
        // ceiling trips before the buffer materializes — the canonical zip-bomb guard.
        val zip = buildArchive { entry("icon.png", ByteArray(1_024 * 1_024)) }
        val config = ParserConfig().copy(maxArchiveBytes = 64 * 1_024, maxEntryBytes = 4_096)
        val result = extractSafely(PassSource.Bytes(zip), config)
        assertExceeded(result, ResourceLimit.EntrySize)
    }

    @Test
    fun tooManyEntriesHitsEntryCountLimit() {
        val zip =
            buildArchive {
                entry("pass.json", "{}".toByteArray())
                entry("manifest.json", "{}".toByteArray())
                entry("signature", byteArrayOf(1))
                entry("icon.png", byteArrayOf(1, 2, 3))
            }
        val config = ParserConfig().copy(maxEntries = 3)
        val result = extractSafely(PassSource.Bytes(zip), config)
        assertExceeded(result, ResourceLimit.EntryCount)
    }

    @Test
    fun zipSlipDirectoryEntryIsRejected() {
        // Path traversal must be rejected even for entries that are pure directories
        // (no payload). Today nothing acts on directory entries, but validating up
        // front means a future change can't bypass the guard by smuggling a `../`
        // through a directory name.
        val zip =
            buildArchive {
                directory("../escape/")
                entry("pass.json", "{}".toByteArray())
            }
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        assertMalformed(result, MalformedReason.NotAZipArchive)
    }

    @Test
    fun zipSlipParentTraversalIsRejected() {
        val zip = buildArchive { entry("../etc/passwd.json", "pwned".toByteArray()) }
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        // Maps to NotAZipArchive until a dedicated PathTraversal arm lands; see follow-up bead.
        assertMalformed(result, MalformedReason.NotAZipArchive)
    }

    @Test
    fun zipSlipMidPathTraversalIsRejected() {
        val zip = buildArchive { entry("en.lproj/../../etc/passwd.json", "pwned".toByteArray()) }
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        assertMalformed(result, MalformedReason.NotAZipArchive)
    }

    @Test
    fun absoluteUnixPathIsRejected() {
        val zip = buildArchive { entry("/etc/passwd.json", "pwned".toByteArray()) }
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        assertMalformed(result, MalformedReason.NotAZipArchive)
    }

    @Test
    fun backslashSeparatorIsRejected() {
        val zip = buildArchive { entry("..\\windows\\system32\\foo.json", "pwned".toByteArray()) }
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        assertMalformed(result, MalformedReason.NotAZipArchive)
    }

    @Test
    fun windowsDriveLetterIsRejected() {
        val zip = buildArchive { entry("C:/temp/pwn.json", "pwned".toByteArray()) }
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        assertMalformed(result, MalformedReason.NotAZipArchive)
    }

    @Test
    fun disallowedExtensionIsRejected() {
        val zip = buildArchive { entry("evil.html", "<script>".toByteArray()) }
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        assertMalformed(result, MalformedReason.NotAZipArchive)
    }

    @Test
    fun executableExtensionIsRejected() {
        val zip = buildArchive { entry("payload.so", byteArrayOf(0x7F, 0x45, 0x4C, 0x46)) }
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        assertMalformed(result, MalformedReason.NotAZipArchive)
    }

    @Test
    fun extensionMatchingIsCaseInsensitive() {
        val zip = buildArchive { entry("Icon.PNG", byteArrayOf(0x89.toByte())) }
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        assertThat(result).isInstanceOf(ExtractResult.Success::class.java)
    }

    @Test
    fun signatureFileWithoutExtensionIsAllowed() {
        val zip = buildArchive { entry("signature", byteArrayOf(0x30, 0x82.toByte(), 0x00)) }
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        assertThat(result).isInstanceOf(ExtractResult.Success::class.java)
        val entries = (result as ExtractResult.Success).entries
        assertThat(entries.keys).containsExactly("signature")
    }

    @Test
    fun nestedSignatureFileNameIsRejected() {
        // The signature exemption applies only to the top-level basename; an attacker
        // cannot smuggle in arbitrary content under a nested "signature" path.
        val zip = buildArchive { entry("nested/signature", byteArrayOf(0x00)) }
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        // Path is legal, but the file has no allowed extension — extension allowlist trips.
        assertMalformed(result, MalformedReason.NotAZipArchive)
    }

    @Test
    fun duplicateEntryNameIsRejected() {
        val zip =
            buildArchiveWithDuplicateEntry(
                name = "manifest.json",
                first = "first".toByteArray(),
                second = "second".toByteArray(),
            )
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        assertMalformed(result, MalformedReason.NotAZipArchive)
    }

    @Test
    fun nonZipBytesReturnNotAZipArchive() {
        val notAZip = "this is not a zip file at all".toByteArray()
        val result = extractSafely(PassSource.Bytes(notAZip), ParserConfig())
        assertMalformed(result, MalformedReason.NotAZipArchive)
    }

    @Test
    fun emptyBytesReturnNotAZipArchive() {
        val result = extractSafely(PassSource.Bytes(ByteArray(0)), ParserConfig())
        assertMalformed(result, MalformedReason.NotAZipArchive)
    }

    @Test
    fun emptyButValidArchiveSucceedsWithNoEntries() {
        // A structurally valid but empty zip. pass.json absence is the next slice's
        // concern; this layer just hands back an empty map.
        val zip = buildArchive { /* no entries */ }
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        assertThat(result).isInstanceOf(ExtractResult.Success::class.java)
        assertThat((result as ExtractResult.Success).entries).isEmpty()
    }

    @Test
    fun emptyEntryNameIsRejected() {
        // Some zip toolchains emit a stray "" header for buggy uploaders. Reject.
        val zip = buildArchive { entry("", byteArrayOf(0)) }
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        assertMalformed(result, MalformedReason.NotAZipArchive)
    }

    @Test
    fun emptyPathSegmentIsRejected() {
        val zip = buildArchive { entry("foo//bar.json", "{}".toByteArray()) }
        val result = extractSafely(PassSource.Bytes(zip), ParserConfig())
        assertMalformed(result, MalformedReason.NotAZipArchive)
    }

    private fun assertMalformed(
        actual: ExtractResult,
        expected: MalformedReason,
    ) {
        assertThat(actual).isInstanceOf(ExtractResult.Failure::class.java)
        val reason = (actual as ExtractResult.Failure).reason
        Truth.assertWithMessage("expected MalformedReason=$expected, got $reason")
            .that(reason)
            .isEqualTo(expected)
    }

    private fun assertExceeded(
        actual: ExtractResult,
        expected: ResourceLimit,
    ) {
        assertThat(actual).isInstanceOf(ExtractResult.Failure::class.java)
        val reason = (actual as ExtractResult.Failure).reason
        assertThat(reason).isInstanceOf(MalformedReason.ResourceLimitExceeded::class.java)
        val actualLimit = (reason as MalformedReason.ResourceLimitExceeded).limit
        Truth.assertWithMessage("expected ResourceLimit=$expected, got $actualLimit")
            .that(actualLimit)
            .isEqualTo(expected)
    }
}

private fun buildArchive(block: ArchiveBuilder.() -> Unit): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
        ArchiveBuilder(zos).block()
    }
    return baos.toByteArray()
}

private class ArchiveBuilder(private val zos: ZipOutputStream) {
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
 * Synthesizes a malformed archive where the same entry name appears twice in the local
 * file header stream. Approach: build two valid single-entry archives, splice their
 * local-file-header bodies before the first archive's central directory + EOCD. The JDK's
 * [ZipOutputStream] rejects duplicate names with a [java.util.zip.ZipException], and the
 * `--add-opens` configuration needed to clear its private `names` set via reflection is
 * heavier than the surgery here.
 *
 * This is the canonical attack archive for "shadow a legitimate `manifest.json` with a
 * tampered second copy" — the JDK's [java.util.zip.ZipInputStream] reads local headers
 * sequentially without consulting the central directory, so without an explicit duplicate
 * check the second entry would silently win.
 */
private fun buildArchiveWithDuplicateEntry(
    name: String,
    first: ByteArray,
    second: ByteArray,
): ByteArray {
    val archiveA = buildArchive { entry(name, first) }
    val archiveB = buildArchive { entry(name, second) }
    val cdAOffset = findCentralDirectoryOffset(archiveA)
    val cdBOffset = findCentralDirectoryOffset(archiveB)
    val out = ByteArrayOutputStream()
    out.write(archiveA, 0, cdAOffset)
    out.write(archiveB, 0, cdBOffset)
    out.write(archiveA, cdAOffset, archiveA.size - cdAOffset)
    return out.toByteArray()
}

private fun findCentralDirectoryOffset(bytes: ByteArray): Int {
    val sig = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
    for (i in 0..bytes.size - sig.size) {
        if (matchesAt(bytes, i, sig)) return i
    }
    error("no central directory found in synthetic archive — buildArchive output is malformed")
}

private fun matchesAt(
    haystack: ByteArray,
    offset: Int,
    needle: ByteArray,
): Boolean {
    for (k in needle.indices) {
        if (haystack[offset + k] != needle[k]) return false
    }
    return true
}

/**
 * Test seam to verify the extractor honors [PassSource]'s "caller owns the stream"
 * contract. Tracks both bytes pulled and whether [close] was ever called.
 */
private class OpenTrackingInputStream(private val delegate: InputStream) : InputStream() {
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
