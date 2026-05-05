package `is`.walt.passes.core.internal

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * Verifies the SHA-1 hash chain a PKPASS archive declares in `manifest.json`. Pure
 * function: no I/O, no temp files, no allocation outside the entries it returns.
 * [entries] is the map produced by [extractSafely] on a successful run.
 *
 * The PKPASS spec uses SHA-1 here as a structural integrity check, *not* a security
 * cipher choice. The actual cryptographic binding is the PKCS#7 detached signature
 * over `manifest.json`'s bytes — that's `wpass-dw2`'s job. If SHA-1 collisions are
 * weaponized against this layer, the attacker still has to forge the PKCS#7 envelope
 * to reach the trust boundary. SHA-1 here is sufficient because PKPASS writers
 * (Apple Wallet, every issuer toolchain) emit SHA-1; deviating would break every real
 * pass in the wild.
 *
 * Failure-arm ordering inside [verifyManifest] is documented and load-bearing:
 *
 *  1. **No manifest** → [ManifestFailure.Missing]
 *  2. **Bad JSON** → [ManifestFailure.InvalidJson]
 *  3. **Wrong shape** (not an object, or a value that's not a string) →
 *     [ManifestFailure.InvalidShape]
 *  4. **Per declared entry, in declaration order:**
 *     a. hex parse fails → [ManifestFailure.InvalidHashFormat]
 *     b. entry name is `"signature"` → [ManifestFailure.SelfReferentialEntry]
 *     c. entry not in archive → [ManifestFailure.MissingEntry]
 *     d. hash differs → [ManifestFailure.HashMismatch]
 *  5. **After the loop**, an archive entry not declared in the manifest (other than
 *     the `signature` and `manifest.json` exemptions) → [ManifestFailure.ExtraEntry]
 *
 * The loop short-circuits on the first per-entry failure. That means
 * [ManifestFailure.HashMismatch] (raised inside the loop) wins over
 * [ManifestFailure.ExtraEntry] (raised after the loop) when both could fire — once
 * `wpass-n6g` lands dedicated `MalformedReason` arms, telemetry on hash-mismatch is
 * operationally distinct from "structurally malformed", and an attack that simulta-
 * neously tampers a hash and adds a stray file should surface as the security event,
 * not as the structural one. Reordering this loop without updating that bead silently
 * downgrades the alert.
 */
internal fun verifyManifest(entries: Map<String, ByteArray>): ManifestVerifyResult {
    val manifestBytes =
        entries[MANIFEST_FILE_NAME]
            ?: return ManifestVerifyResult.Failed(ManifestFailure.Missing)
    val failure = checkManifest(manifestBytes, entries)
    return failure?.let { ManifestVerifyResult.Failed(it) }
        ?: ManifestVerifyResult.Ok(manifestBytes)
}

private fun checkManifest(
    manifestBytes: ByteArray,
    entries: Map<String, ByteArray>,
): ManifestFailure? =
    when (val parsed = parseManifest(manifestBytes)) {
        is ManifestParse.Failed -> parsed.failure
        is ManifestParse.Ok -> validateAllEntries(parsed.declared, entries)
    }

private fun parseManifest(manifestBytes: ByteArray): ManifestParse {
    val root =
        parseJsonOrNull(manifestBytes)
            ?: return ManifestParse.Failed(ManifestFailure.InvalidJson)
    return readStringMap(root)
}

private fun parseJsonOrNull(manifestBytes: ByteArray): JsonElement? =
    try {
        Json.parseToJsonElement(manifestBytes.decodeToString())
    } catch (_: SerializationException) {
        null
    }

private fun readStringMap(root: JsonElement): ManifestParse {
    val obj =
        root as? JsonObject
            ?: return ManifestParse.Failed(ManifestFailure.InvalidShape)
    return collectStringEntries(obj)
}

private fun collectStringEntries(obj: JsonObject): ManifestParse {
    val map = LinkedHashMap<String, String>(obj.size)
    for ((key, value) in obj) {
        val str =
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return ManifestParse.Failed(ManifestFailure.InvalidShape)
        map[key] = str
    }
    return ManifestParse.Ok(map)
}

private fun validateAllEntries(
    declared: Map<String, String>,
    entries: Map<String, ByteArray>,
): ManifestFailure? {
    val perEntry = firstEntryFailure(declared, entries)
    return perEntry ?: extraEntryFailure(declared.keys, entries.keys)
}

private fun firstEntryFailure(
    declared: Map<String, String>,
    entries: Map<String, ByteArray>,
): ManifestFailure? {
    for ((name, hexHash) in declared) {
        val failure = validateOneEntry(name, hexHash, entries)
        if (failure != null) return failure
    }
    return null
}

private fun validateOneEntry(
    name: String,
    hexHash: String,
    entries: Map<String, ByteArray>,
): ManifestFailure? {
    val expected =
        decodeSha1HexOrNull(hexHash)
            ?: return ManifestFailure.InvalidHashFormat(name)
    return checkEntryAgainstHash(name, expected, entries)
}

private fun checkEntryAgainstHash(
    name: String,
    expected: ByteArray,
    entries: Map<String, ByteArray>,
): ManifestFailure? {
    if (name == SIGNATURE_FILE_NAME) return ManifestFailure.SelfReferentialEntry
    return compareEntryBytes(name, expected, entries)
}

private fun compareEntryBytes(
    name: String,
    expected: ByteArray,
    entries: Map<String, ByteArray>,
): ManifestFailure? {
    val actual = entries[name] ?: return ManifestFailure.MissingEntry(name)
    // MessageDigest.isEqual is the constant-time comparator. Plain contentEquals or
    // == on ByteArray short-circuits on first mismatch and leaks a timing oracle; for
    // a hash compared against attacker-controlled bytes that's the textbook footgun.
    val matches = MessageDigest.isEqual(sha1Of(actual), expected)
    return if (matches) null else ManifestFailure.HashMismatch(name)
}

private fun extraEntryFailure(
    declared: Set<String>,
    archiveEntries: Set<String>,
): ManifestFailure? {
    val firstExtra = archiveEntries.firstOrNull { it !in declared && !isManifestExempt(it) }
    return firstExtra?.let { ManifestFailure.ExtraEntry(it) }
}

private fun isManifestExempt(name: String): Boolean =
    // The signature blob signs the manifest, so cannot be a manifest entry. The
    // manifest cannot list itself (chicken-and-egg). Both are exempt by spec.
    name == SIGNATURE_FILE_NAME || name == MANIFEST_FILE_NAME

private fun sha1Of(bytes: ByteArray): ByteArray = MessageDigest.getInstance(SHA1_ALGORITHM).digest(bytes)

private sealed interface ManifestParse {
    data class Ok(val declared: Map<String, String>) : ManifestParse

    data class Failed(val failure: ManifestFailure) : ManifestParse
}

private const val MANIFEST_FILE_NAME = "manifest.json"
private const val SIGNATURE_FILE_NAME = "signature"
private const val SHA1_ALGORITHM = "SHA-1"
