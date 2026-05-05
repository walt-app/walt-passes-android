package `is`.walt.passes.core.internal

/**
 * Outcome of running [verifyManifest] over the entries map produced by [extractSafely].
 * Internal only: the parser-glue bead lifts a [Failed] into the right
 * [`is`.walt.passes.core.ParseResult] arm. The arm split is finer-grained than the
 * frozen public [`is`.walt.passes.core.MalformedReason] surface so that follow-up bead
 * `wpass-n6g` (dedicated `MalformedReason` arms) and `wpass-dw2` (signature
 * verification) can route each failure shape independently without re-deriving it from
 * a stringy reason.
 *
 * [Ok.manifestBytes] are returned verbatim from `entries["manifest.json"]` so the
 * signature bead can hash the exact bytes the PKCS#7 envelope was constructed over.
 * Re-reading the entry from the map there would work today but couples the signature
 * step to the entries map's lifetime; returning the bytes here makes the contract
 * explicit and lets the parser-glue bead drop the entries map sooner.
 */
internal sealed interface ManifestVerifyResult {
    data class Ok(val manifestBytes: ByteArray) : ManifestVerifyResult {
        override fun equals(other: Any?): Boolean {
            return this === other ||
                other is Ok && manifestBytes.contentEquals(other.manifestBytes)
        }

        override fun hashCode(): Int {
            return manifestBytes.contentHashCode()
        }
    }

    data class Failed(val failure: ManifestFailure) : ManifestVerifyResult
}

/**
 * Why [verifyManifest] rejected an archive. The arms partition along the same
 * trust / structural axis that [`is`.walt.passes.core.ParseResult] uses:
 *
 *  - [HashMismatch] is a tampering signal — the archive is structurally valid but a
 *    file's contents do not match the SHA-1 declared in `manifest.json`. The parser-glue
 *    bead routes this to [`is`.walt.passes.core.ParseResult.Tampered] with
 *    [`is`.walt.passes.core.TamperReason.FileHashMismatch].
 *  - Every other arm is structural malformedness — bad JSON, wrong shape, declared file
 *    not present, archive file not declared, an entry redeclaring `signature` (which by
 *    spec cannot self-reference because it signs the manifest). All route to
 *    [`is`.walt.passes.core.ParseResult.Malformed]; the dedicated
 *    [`is`.walt.passes.core.MalformedReason] arms land in `wpass-n6g`. Until then they
 *    collapse onto [`is`.walt.passes.core.MalformedReason.MissingManifest] /
 *    [`is`.walt.passes.core.MalformedReason.InvalidManifest] at the parser-glue layer.
 *
 * Critically: hash mismatches are NEVER coalesced with structural malformedness. The
 * trust UI must surface tampering as a security event, not as "your file is broken".
 */
internal sealed interface ManifestFailure {
    /** No `manifest.json` entry in the archive. */
    data object Missing : ManifestFailure

    /** `manifest.json` is present but its bytes are not parseable as JSON. */
    data object InvalidJson : ManifestFailure

    /**
     * `manifest.json` parses but is not a flat object of string values. Covers the
     * non-object case (top-level array, scalar) and the wrong-value case (number,
     * boolean, null, nested object/array).
     */
    data object InvalidShape : ManifestFailure

    /**
     * `manifest.json` declared a hash for [entryName] whose value is not exactly 40
     * hex characters. Lower / upper / mixed case are all accepted; everything else
     * (wrong length, non-hex character) lands here.
     */
    data class InvalidHashFormat(val entryName: String) : ManifestFailure

    /**
     * `manifest.json` declared an entry for `signature`. PKPASS forbids this — the
     * signature is the PKCS#7 envelope over the manifest itself, so a manifest entry
     * for it cannot be self-consistent. Surfaced as a distinct arm so a future
     * `wpass-n6g` arm or telemetry can flag the attack shape rather than burying it
     * in a generic "invalid manifest".
     */
    data object SelfReferentialEntry : ManifestFailure

    /**
     * The archive contains [entryName] but `manifest.json` does not declare it.
     * `signature` and `manifest.json` are exempt — the former because it is not in
     * the manifest by spec, the latter because a manifest cannot list itself.
     *
     * The PKPASS spec is silent on extra files; the conservative posture (matching
     * iOS Wallet observed behavior) is to reject. Otherwise an attacker who can wedge
     * unsigned content into a signed archive defeats the trust claim.
     */
    data class ExtraEntry(val entryName: String) : ManifestFailure

    /** `manifest.json` declares [entryName] but the archive does not contain it. */
    data class MissingEntry(val entryName: String) : ManifestFailure

    /**
     * `manifest.json` declared a hex SHA-1 for [entryName] that does not match the
     * SHA-1 of the entry's bytes. The archive is well-formed; this is tampering.
     */
    data class HashMismatch(val entryName: String) : ManifestFailure
}
