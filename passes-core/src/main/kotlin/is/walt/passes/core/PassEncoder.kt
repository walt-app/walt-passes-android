package `is`.walt.passes.core

import `is`.walt.passes.core.internal.writePassArchive

/**
 * Encodes a parsed [Pass] back into an UNSIGNED `.pkpass` archive: `pass.json`,
 * `manifest.json`, the top-level role images, and one `<tag>.lproj/pass.strings` per
 * locale. Exists so passes stored before archive-byte retention (ADR 0002 D6) can still
 * be exported in the same container as everything else; a pass whose original bytes are
 * retained should be exported byte-exact instead, never re-encoded.
 *
 * **Honestly unsigned.** No `signature` entry is ever written, so re-import lands as
 * [SignatureStatus.Unsigned] under the lenient default and as
 * `ParseResult.Tampered(SignatureCryptoFailure)` under [ParserConfig.Strict]. The
 * `manifest.json` IS written: the parser rejects an archive without one, and an entry
 * the manifest does not list. It is an integrity index (SHA-1 per entry), not
 * provenance; the only provenance signal the kernel recognises is the CMS signature.
 *
 * **Lossy by construction.** Only the fields [Pass] keeps are emitted. Everything the
 * parser discards (`passTypeIdentifier`, `teamIdentifier`, `webServiceURL`,
 * `authenticationToken`, `nfc`, `relevantDate`, `locations`, extra barcodes, localized
 * images, ...) is absent. Walt's own parser requires none of them.
 *
 * **Bounded.** The archive is checked against [config] before it is returned, so the
 * encoder never produces bytes the parser would reject on a resource limit. The
 * inputs are already bounded when they came out of the parser; the check guards a
 * hand-built [Pass].
 *
 * **Deterministic.** Fixed entry order, sorted keys, and a fixed timestamp: two encodes
 * of an equal [Pass] are byte-identical on the same device, so a consumer can hash the
 * output.
 */
public object PassEncoder {
    public fun encode(
        pass: Pass,
        config: ParserConfig = ParserConfig(),
    ): PassEncodeResult = writePassArchive(pass, config)
}

/** Outcome of [PassEncoder.encode]. Never an exception; see [ParseResult] for the pattern. */
public sealed interface PassEncodeResult {
    /** The archive bytes. Caller owns the array. */
    public class Success(public val bytes: ByteArray) : PassEncodeResult

    /** The [Pass] would produce an archive the parser rejects on [limit]; nothing was written. */
    public data class LimitExceeded(public val limit: ResourceLimit) : PassEncodeResult

    /**
     * A [PassLocale.tag] cannot name a `<tag>.lproj/` directory the parser's path rules
     * accept (empty, contains a separator, or looks like a drive letter).
     */
    public data class InvalidLocaleTag(public val tag: String) : PassEncodeResult
}
