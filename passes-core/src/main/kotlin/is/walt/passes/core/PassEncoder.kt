package `is`.walt.passes.core

import `is`.walt.passes.core.internal.writePassArchive

/**
 * Encodes a parsed [Pass] into an UNSIGNED `.pkpass`: `pass.json`, `manifest.json`, the
 * top-level role images, and one `<tag>.lproj/pass.strings` per locale. No `signature`
 * is ever written, so re-import lands as [SignatureStatus.Unsigned] under the default
 * policy and as `Tampered(SignatureCryptoFailure)` under [ParserConfig.Strict].
 *
 * Only the fields [Pass] keeps are emitted. The output is checked against [config]
 * before it is returned ([PassEncodeResult.LimitExceeded] names the limit the parser
 * would have tripped) and is byte-identical for an equal [Pass] on the same device.
 * Rationale and scope: ADR 0001 D8.
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
     * accept (empty, contains a separator, looks like a drive letter, or is not valid UTF-16).
     */
    public data class InvalidLocaleTag(public val tag: String) : PassEncodeResult
}
