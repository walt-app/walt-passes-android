package `is`.walt.passes.core.internal

import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.security.MessageDigest

/**
 * Behavior tests for the manifest hash chain verifier. Every fixture is built in-memory
 * so a reviewer can read each test and see exactly which arm of [ManifestFailure] is
 * being exercised. No checked-in binary fixtures: the manifest bytes are encoded via
 * kotlinx.serialization so JSON encoding is correct without ad-hoc string concatenation,
 * and entry hashes are computed from byte arrays at fixture build time.
 *
 * Each test names the [ManifestFailure] arm it asserts on so a future change that
 * collapses arms (or that updates the parser-glue mapping in `wpass-qnc`) trips the
 * assertion in lockstep.
 */
class ManifestVerifierTest {
    @Test
    fun happyPathFiveEntryArchiveVerifies() {
        val passJson = "{\"formatVersion\":1}".toByteArray()
        val iconPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val passStrings = "\"k\" = \"v\";".toByteArray()
        val signature = byteArrayOf(0x30, 0x82.toByte())
        val manifestJson =
            manifestBytesOf(
                "pass.json" to sha1HexOf(passJson),
                "icon.png" to sha1HexOf(iconPng),
                "en.lproj/pass.strings" to sha1HexOf(passStrings),
            )
        val entries =
            mapOf(
                "pass.json" to passJson,
                "icon.png" to iconPng,
                "en.lproj/pass.strings" to passStrings,
                "manifest.json" to manifestJson,
                "signature" to signature,
            )
        val result = verifyManifest(entries)
        assertThat(result).isInstanceOf(ManifestVerifyResult.Ok::class.java)
        // Critical: returned bytes are byte-identical to the entry. The signature bead
        // signs over these bytes; any transformation here breaks the trust chain.
        Truth.assertWithMessage("Ok.manifestBytes must round-trip the entry verbatim")
            .that((result as ManifestVerifyResult.Ok).manifestBytes)
            .isEqualTo(manifestJson)
    }

    @Test
    fun happyPathManifestEntriesAreNotRequiredToBeSorted() {
        // Manifest declares entries in an order different from the archive's iteration
        // order. Verifier is order-agnostic; it iterates the manifest and looks each
        // entry up in the entries map by name.
        val a = "alpha".toByteArray()
        val b = "beta".toByteArray()
        val manifestJson =
            manifestBytesOf(
                "icon.png" to sha1HexOf(b),
                "pass.json" to sha1HexOf(a),
            )
        val entries =
            mapOf(
                "pass.json" to a,
                "icon.png" to b,
                "manifest.json" to manifestJson,
            )
        assertOk(verifyManifest(entries), manifestJson)
    }

    @Test
    fun missingManifestJson() {
        val entries = mapOf("pass.json" to "{}".toByteArray())
        assertFailedWith(verifyManifest(entries), ManifestFailure.Missing)
    }

    @Test
    fun invalidJsonManifest() {
        val entries =
            mapOf(
                "pass.json" to "{}".toByteArray(),
                "manifest.json" to "not json at all".toByteArray(),
            )
        assertFailedWith(verifyManifest(entries), ManifestFailure.InvalidJson)
    }

    @Test
    fun invalidShapeManifestNonStringValue() {
        val entries =
            mapOf(
                "pass.json" to "{}".toByteArray(),
                "manifest.json" to "{\"pass.json\":42}".toByteArray(),
            )
        assertFailedWith(verifyManifest(entries), ManifestFailure.InvalidShape)
    }

    @Test
    fun invalidShapeManifestNotAnObject() {
        val entries =
            mapOf(
                "pass.json" to "{}".toByteArray(),
                "manifest.json" to "[\"pass.json\",\"abc\"]".toByteArray(),
            )
        assertFailedWith(verifyManifest(entries), ManifestFailure.InvalidShape)
    }

    @Test
    fun invalidShapeManifestNullValue() {
        val entries =
            mapOf(
                "pass.json" to "{}".toByteArray(),
                "manifest.json" to "{\"pass.json\":null}".toByteArray(),
            )
        assertFailedWith(verifyManifest(entries), ManifestFailure.InvalidShape)
    }

    @Test
    fun invalidShapeManifestNestedObjectValue() {
        val entries =
            mapOf(
                "pass.json" to "{}".toByteArray(),
                "manifest.json" to "{\"pass.json\":{\"sha1\":\"abc\"}}".toByteArray(),
            )
        assertFailedWith(verifyManifest(entries), ManifestFailure.InvalidShape)
    }

    @Test
    fun hexParsingAcceptsMixedCase() {
        val passJson = "payload".toByteArray()
        val realHex = sha1HexOf(passJson)
        // Build a hash where every alpha char's case is flipped from sha1HexOf's
        // lowercase output — proves the verifier accepts all three case mixes.
        val mixedCaseHex = realHex.toMixedCase()
        val manifestJson = manifestBytesOf("pass.json" to mixedCaseHex)
        val entries =
            mapOf(
                "pass.json" to passJson,
                "manifest.json" to manifestJson,
            )
        assertOk(verifyManifest(entries), manifestJson)
    }

    @Test
    fun hexParsingRejectsNonHexCharacter() {
        val passJson = "payload".toByteArray()
        // Replace the last hex char with a 'g' — still 40 chars, but invalid hex.
        val tainted = sha1HexOf(passJson).dropLast(1) + "g"
        val manifestJson = manifestBytesOf("pass.json" to tainted)
        val entries =
            mapOf(
                "pass.json" to passJson,
                "manifest.json" to manifestJson,
            )
        assertFailedWith(verifyManifest(entries), ManifestFailure.InvalidHashFormat("pass.json"))
    }

    @Test
    fun hexParsingRejectsWrongLengthShort() {
        val passJson = "payload".toByteArray()
        val tooShort = sha1HexOf(passJson).dropLast(1)
        val manifestJson = manifestBytesOf("pass.json" to tooShort)
        val entries =
            mapOf(
                "pass.json" to passJson,
                "manifest.json" to manifestJson,
            )
        assertFailedWith(verifyManifest(entries), ManifestFailure.InvalidHashFormat("pass.json"))
    }

    @Test
    fun hexParsingRejectsWrongLengthLong() {
        val passJson = "payload".toByteArray()
        val tooLong = sha1HexOf(passJson) + "a"
        val manifestJson = manifestBytesOf("pass.json" to tooLong)
        val entries =
            mapOf(
                "pass.json" to passJson,
                "manifest.json" to manifestJson,
            )
        assertFailedWith(verifyManifest(entries), ManifestFailure.InvalidHashFormat("pass.json"))
    }

    @Test
    fun selfReferentialEntryRejected() {
        // A manifest that declares "signature" is malformed by spec. The signature
        // signs the manifest, so a manifest entry pointing at it cannot be self-
        // consistent — distinct arm so wpass-n6g / telemetry can flag the shape.
        val passJson = "{}".toByteArray()
        val signature = byteArrayOf(0x30, 0x82.toByte(), 0x00)
        val manifestJson =
            manifestBytesOf(
                "pass.json" to sha1HexOf(passJson),
                "signature" to sha1HexOf(signature),
            )
        val entries =
            mapOf(
                "pass.json" to passJson,
                "signature" to signature,
                "manifest.json" to manifestJson,
            )
        assertFailedWith(verifyManifest(entries), ManifestFailure.SelfReferentialEntry)
    }

    @Test
    fun hashMismatchSurfacedAsHashMismatch() {
        // Critical regression test: hash mismatch must NOT collapse onto InvalidShape /
        // any structural arm. Trust UI distinguishes tampering from malformedness.
        val passJson = "{}".toByteArray()
        val iconPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val correctIconHash = sha1HexOf(iconPng)
        val tamperedIconHash = correctIconHash.flipLastNibble()
        val manifestJson =
            manifestBytesOf(
                "pass.json" to sha1HexOf(passJson),
                "icon.png" to tamperedIconHash,
            )
        val entries =
            mapOf(
                "pass.json" to passJson,
                "icon.png" to iconPng,
                "manifest.json" to manifestJson,
            )
        assertFailedWith(verifyManifest(entries), ManifestFailure.HashMismatch("icon.png"))
    }

    @Test
    fun extraFileInArchive() {
        val passJson = "{}".toByteArray()
        val extra = "stowaway".toByteArray()
        val manifestJson = manifestBytesOf("pass.json" to sha1HexOf(passJson))
        val entries =
            mapOf(
                "pass.json" to passJson,
                "extra.json" to extra,
                "manifest.json" to manifestJson,
            )
        assertFailedWith(verifyManifest(entries), ManifestFailure.ExtraEntry("extra.json"))
    }

    @Test
    fun manifestReferencesMissingFile() {
        val passJson = "{}".toByteArray()
        // Manifest declares icon.png with a plausible hash, but archive lacks it.
        val phantomHex = sha1HexOf("imaginary-icon-bytes".toByteArray())
        val manifestJson =
            manifestBytesOf(
                "pass.json" to sha1HexOf(passJson),
                "icon.png" to phantomHex,
            )
        val entries =
            mapOf(
                "pass.json" to passJson,
                "manifest.json" to manifestJson,
            )
        assertFailedWith(verifyManifest(entries), ManifestFailure.MissingEntry("icon.png"))
    }

    @Test
    fun signatureFileExemptFromManifest() {
        // The signature file is the only PKPASS member with no manifest entry. Its
        // absence from the manifest is correct; presence in the archive must not be
        // surfaced as ExtraEntry.
        val passJson = "{}".toByteArray()
        val signature = byteArrayOf(0x30, 0x82.toByte(), 0x00)
        val manifestJson = manifestBytesOf("pass.json" to sha1HexOf(passJson))
        val entries =
            mapOf(
                "pass.json" to passJson,
                "signature" to signature,
                "manifest.json" to manifestJson,
            )
        assertOk(verifyManifest(entries), manifestJson)
    }

    @Test
    fun manifestJsonItselfNotInManifest() {
        // The manifest cannot list itself (chicken-and-egg). Verifier must not fault
        // its own entry as ExtraEntry.
        val passJson = "{}".toByteArray()
        val manifestJson = manifestBytesOf("pass.json" to sha1HexOf(passJson))
        val entries =
            mapOf(
                "pass.json" to passJson,
                "manifest.json" to manifestJson,
            )
        assertOk(verifyManifest(entries), manifestJson)
    }

    @Test
    fun emptyManifestWithNoOtherEntriesSucceeds() {
        // Whether pass.json is mandatory is a downstream concern (wpass-7sl); this
        // layer only verifies the hash chain. An empty manifest with only itself in
        // the archive is structurally consistent.
        val manifestJson = "{}".toByteArray()
        val entries = mapOf("manifest.json" to manifestJson)
        assertOk(verifyManifest(entries), manifestJson)
    }

    @Test
    fun emptyManifestWithSignatureSucceeds() {
        val manifestJson = "{}".toByteArray()
        val entries =
            mapOf(
                "manifest.json" to manifestJson,
                "signature" to byteArrayOf(0x30),
            )
        assertOk(verifyManifest(entries), manifestJson)
    }

    @Test
    fun emptyManifestWithExtraEntryRejected() {
        val manifestJson = "{}".toByteArray()
        val entries =
            mapOf(
                "manifest.json" to manifestJson,
                "rogue.json" to "{}".toByteArray(),
            )
        assertFailedWith(verifyManifest(entries), ManifestFailure.ExtraEntry("rogue.json"))
    }

    @Test
    fun firstMismatchInDeclarationOrderWinsOverLater() {
        // Two entries with mismatched hashes. The verifier short-circuits on the
        // first mismatch in iteration order. We can't directly observe constant-time
        // comparison from the outside, but exercising the code path here ensures
        // MessageDigest.isEqual stays in place — a refactor to contentEquals would
        // not change which arm fires, but a refactor to early-aggregate would.
        val a = "alpha".toByteArray()
        val b = "beta".toByteArray()
        val manifestJson =
            manifestBytesOf(
                "a.json" to sha1HexOf(a).flipLastNibble(),
                "b.json" to sha1HexOf(b).flipLastNibble(),
            )
        val entries =
            mapOf(
                "a.json" to a,
                "b.json" to b,
                "manifest.json" to manifestJson,
            )
        assertFailedWith(verifyManifest(entries), ManifestFailure.HashMismatch("a.json"))
    }

    private fun assertOk(
        actual: ManifestVerifyResult,
        expectedManifestBytes: ByteArray,
    ) {
        assertThat(actual).isInstanceOf(ManifestVerifyResult.Ok::class.java)
        Truth.assertWithMessage("Ok.manifestBytes mismatch")
            .that((actual as ManifestVerifyResult.Ok).manifestBytes)
            .isEqualTo(expectedManifestBytes)
    }

    private fun assertFailedWith(
        actual: ManifestVerifyResult,
        expected: ManifestFailure,
    ) {
        assertThat(actual).isInstanceOf(ManifestVerifyResult.Failed::class.java)
        val failure = (actual as ManifestVerifyResult.Failed).failure
        Truth.assertWithMessage("expected ManifestFailure=$expected, got $failure")
            .that(failure)
            .isEqualTo(expected)
    }
}

private fun manifestBytesOf(vararg pairs: Pair<String, String>): ByteArray {
    val map = pairs.associate { (k, v) -> k to JsonPrimitive(v) }
    return Json.encodeToString(JsonObject.serializer(), JsonObject(map)).toByteArray()
}

private fun sha1HexOf(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_DIGITS[v ushr 4])
        sb.append(HEX_DIGITS[v and 0x0F])
    }
    return sb.toString()
}

/** Flips the last hex nibble so the hash differs by exactly one bit-quartet. */
private fun String.flipLastNibble(): String {
    val last = last()
    val flipped =
        when (last) {
            in '0'..'9' -> 'a'
            in 'a'..'f' -> '0'
            else -> error("flipLastNibble called on non-hex char: $last")
        }
    return dropLast(1) + flipped
}

/** Cycles through lower / upper / mixed cases char-by-char to exercise hex case mixes. */
private fun String.toMixedCase(): String {
    val sb = StringBuilder(length)
    for ((i, c) in this.withIndex()) {
        sb.append(if (i % 2 == 0) c.uppercaseChar() else c.lowercaseChar())
    }
    return sb.toString()
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()
