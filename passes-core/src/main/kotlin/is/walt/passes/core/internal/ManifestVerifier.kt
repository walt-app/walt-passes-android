package `is`.walt.passes.core.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Verifies the SHA-1 hash chain a PKPASS archive declares in `manifest.json`. Pure
 * function: no I/O, no temp files, no allocation outside the entries it returns.
 * [entries] is the map produced by [extractSafely] on a successful run.
 *
 * The PKPASS spec uses SHA-1 here as a structural integrity check, *not* a security
 * cipher choice. The actual cryptographic binding is the PKCS#7 detached signature
 * over `manifest.json`'s bytes — that's `wpass-dw2`'s job. SHA-1 here is sufficient
 * because every PKPASS writer in the wild emits SHA-1; deviating would break every
 * real pass.
 *
 * **Iteration-order contract.** [entries] is consumed in iteration order: the first
 * stray archive entry surfaces as [ManifestFailure.ExtraEntry], and the first
 * manifest entry that fails surfaces with its name as the per-entry arm.
 * [extractSafely] returns an insertion-ordered map mirroring the archive's local-
 * file-header order, so calling code gets deterministic failure naming. A caller that
 * passes a plain `HashMap` (e.g. a fuzz harness) would observe non-deterministic
 * names; correctness of accept-vs-reject is unaffected.
 *
 * **Failure-arm ordering inside the per-entry loop.** Documented and load-bearing —
 * once `wpass-n6g` lands dedicated `MalformedReason` arms, telemetry on hash mismatch
 * is operationally distinct from "structurally malformed", and an attack that
 * simultaneously tampers a hash and adds a stray file should surface as the security
 * event:
 *
 *  1. `name == "signature"` → [ManifestFailure.SelfReferentialEntry]. The structural
 *     rule (signature can never have a manifest entry, since it signs the manifest)
 *     wins over hex-format validity — that is, a manifest line `"signature": "g..."`
 *     surfaces as `SelfReferentialEntry`, not `InvalidHashFormat`. The structural arm
 *     is the stronger statement.
 *  2. hex parse fails → [ManifestFailure.InvalidHashFormat]
 *  3. entry not in archive → [ManifestFailure.MissingEntry]
 *  4. hash differs → [ManifestFailure.HashMismatch]
 *
 * After the loop completes without failure, [ManifestFailure.ExtraEntry] surfaces any
 * archive entry not declared in the manifest (`signature` and `manifest.json`
 * exempted). Because the loop short-circuits, [ManifestFailure.HashMismatch] beats
 * [ManifestFailure.ExtraEntry] when both could fire.
 */
internal fun verifyManifest(entries: Map<String, ByteArray>): ManifestVerifyResult {
    val manifestBytes =
        entries[MANIFEST_FILE_NAME]
            ?: return ManifestVerifyResult.Failed(ManifestFailure.Missing)
    val failure =
        when (val parsed = parseManifest(manifestBytes)) {
            is ManifestParse.Failed -> parsed.failure
            is ManifestParse.Ok -> validateAllEntries(parsed.declared, entries)
        }
    return failure?.let { ManifestVerifyResult.Failed(it) }
        ?: ManifestVerifyResult.Ok(manifestBytes)
}

private fun parseManifest(manifestBytes: ByteArray): ManifestParse {
    val root =
        runCatching { Json.parseToJsonElement(manifestBytes.decodeToString()) }.getOrNull()
            ?: return ManifestParse.Failed(ManifestFailure.InvalidJson)
    val declared = (root as? JsonObject)?.toStringMapOrNull()
    return declared?.let { ManifestParse.Ok(it) }
        ?: ManifestParse.Failed(ManifestFailure.InvalidShape)
}

private fun JsonObject.toStringMapOrNull(): Map<String, String>? {
    val out = LinkedHashMap<String, String>(size)
    for ((key, value) in this) {
        val str =
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return null
        out[key] = str
    }
    return out
}

private fun validateAllEntries(
    declared: Map<String, String>,
    entries: Map<String, ByteArray>,
): ManifestFailure? {
    for ((name, hexHash) in declared) {
        val failure = perEntryFailure(name, hexHash, entries)
        if (failure != null) return failure
    }
    return findExtraEntry(declared.keys, entries.keys)
}

private fun perEntryFailure(
    name: String,
    hexHash: String,
    entries: Map<String, ByteArray>,
): ManifestFailure? {
    if (name == SIGNATURE_FILE_NAME) return ManifestFailure.SelfReferentialEntry
    val expected = decodeSha1Hex(hexHash)
    return if (expected == null) {
        ManifestFailure.InvalidHashFormat(name)
    } else {
        matchEntry(name, expected, entries)
    }
}

private fun matchEntry(
    name: String,
    expected: ByteArray,
    entries: Map<String, ByteArray>,
): ManifestFailure? {
    val actual = entries[name] ?: return ManifestFailure.MissingEntry(name)
    // MessageDigest.isEqual is the constant-time comparator. Plain contentEquals or
    // == on ByteArray short-circuits on first mismatch and leaks a timing oracle; for
    // a hash compared against attacker-controlled bytes that's the textbook footgun.
    val matches = MessageDigest.isEqual(MessageDigest.getInstance(SHA1_ALGORITHM).digest(actual), expected)
    return if (matches) null else ManifestFailure.HashMismatch(name)
}

private fun findExtraEntry(
    declared: Set<String>,
    archiveEntries: Set<String>,
): ManifestFailure? {
    val firstExtra =
        archiveEntries.firstOrNull {
            // signature signs the manifest (cannot self-reference); manifest.json
            // cannot list itself (chicken-and-egg). Both exempt by spec.
            it !in declared && it != SIGNATURE_FILE_NAME && it != MANIFEST_FILE_NAME
        }
    return firstExtra?.let { ManifestFailure.ExtraEntry(it) }
}

/**
 * Decodes a 40-character SHA-1 hex string to its 20 raw bytes, accepting any case
 * mix. Returns `null` (not an exception) so the caller can map to
 * [ManifestFailure.InvalidHashFormat] without a try/catch at every call site.
 * [HexFormat.parseHex] accepts both cases; the explicit length check is here because
 * `parseHex` accepts any even length and we require exactly SHA-1.
 */
private fun decodeSha1Hex(hex: String): ByteArray? {
    if (hex.length != SHA1_HEX_LENGTH) return null
    return runCatching { HEX.parseHex(hex) }.getOrNull()
}

private sealed interface ManifestParse {
    data class Ok(val declared: Map<String, String>) : ManifestParse

    data class Failed(val failure: ManifestFailure) : ManifestParse
}

private const val SHA1_ALGORITHM = "SHA-1"
private const val SHA1_HEX_LENGTH = 40
private val HEX: HexFormat = HexFormat.of()
