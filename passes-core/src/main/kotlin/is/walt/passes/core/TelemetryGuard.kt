package `is`.walt.passes.core

/**
 * Hook for emitting parser observability events to a host telemetry pipeline. The shape of
 * the event types is the load-bearing security control here: every parameter is either an
 * enum, a count, or a duration. There is no `String` carrying pass content, no field key,
 * no organization name, no serial number, no barcode message.
 *
 * That structural restriction is the trust claim ("Pass content never appears in logs or
 * telemetry" from README.md). A consumer cannot accidentally log PII through this interface
 * because the interface refuses to accept it. Reviewers should treat any future addition of
 * a `String` parameter to these events as a security-policy change requiring re-review.
 */
public interface TelemetryGuard {
    public fun onParseStarted()

    public fun onParseSucceeded(event: ParseSucceededEvent)

    public fun onParseFailed(event: ParseFailedEvent)

    public object NoOp : TelemetryGuard {
        override fun onParseStarted(): Unit = Unit

        override fun onParseSucceeded(event: ParseSucceededEvent): Unit = Unit

        override fun onParseFailed(event: ParseFailedEvent): Unit = Unit
    }
}

public data class ParseSucceededEvent(
    public val passType: PassType,
    public val signatureStatus: SignatureStatusKind,
    public val archiveBytes: Long,
    public val durationMillis: Long,
    public val imageCount: Int,
    public val localeCount: Int,
)

/**
 * [outcome] is a computed property, not a constructor arg, so the cross-field invariant
 * `outcome == reason.toKind()` is enforced by construction rather than by convention. A
 * caller cannot build an event with mismatched bucket and reason; the only knobs that
 * shape the event are [reason] and [durationMillis]. Equality / [hashCode] / [copy] /
 * destructuring follow the data-class shape — i.e. two events with the same [reason]
 * and [durationMillis] are equal regardless, which is the right semantics because
 * [outcome] adds no independent information.
 */
public data class ParseFailedEvent(
    public val reason: ParseFailureReason,
    public val durationMillis: Long,
) {
    public val outcome: ParseFailureKind
        get() = reason.toKind()
}

/**
 * Telemetry-safe flattening of [SignatureStatus]. Mirrors the sealed-interface arms but
 * lives as an enum so it can travel through metric backends that prefer dimension strings.
 */
public enum class SignatureStatusKind {
    Unsigned,
    SelfSigned,
    AppleVerified,
    CertChainIncomplete,
}

/**
 * Telemetry-safe flattening of [ParseResult] failure arms. Resource-limit hits are surfaced
 * as their own bucket because they are the most operationally-meaningful failure for tuning
 * [ParserConfig] limits.
 */
public enum class ParseFailureKind {
    Tampered,
    Malformed,
    Unsupported,
    ResourceLimitExceeded,
}

/**
 * Telemetry-safe flattening of every [ParseResult] failure-reason arm. Mirrors the sealed
 * arms of [TamperReason], [MalformedReason], and [UnsupportedReason] (and the seven
 * [ResourceLimit] sub-buckets that lift out of [MalformedReason.ResourceLimitExceeded]).
 *
 * Lives as an enum, not a String, so the same structural-restriction discipline that
 * [ParseFailedEvent] enforced on `outcome` extends to the reason: a consumer cannot smuggle
 * a pass field, filename, or BC exception message through this dimension. The data carried
 * by data-class arms (e.g. [UnsupportedReason.UnknownPassStyle.raw],
 * [UnsupportedReason.FormatVersion.version]) is intentionally dropped — telemetry sees only
 * the failure shape, never user-supplied content.
 *
 * Adding a new sealed arm to [ParseResult]'s reasons without extending this enum produces a
 * compile error inside [toFailureReason], the same drift-detector pattern used by
 * [toFailureKind].
 */
public enum class ParseFailureReason {
    // TamperReason arms.
    ManifestSignatureMismatch,
    FileHashMismatch,
    SignatureCryptoFailure,
    SignerCertificateMissing,

    // MalformedReason arms (non-resource).
    NotAZipArchive,
    MissingPassJson,
    MissingManifest,
    InvalidPassJson,
    InvalidManifest,
    InvalidStrings,

    // ResourceLimit sub-buckets — surfaced individually because the operational signal of
    // "which guard tripped" is exactly what makes ResourceLimitExceeded its own bucket in
    // [ParseFailureKind] in the first place.
    ArchiveSizeLimit,
    EntryCountLimit,
    EntrySizeLimit,
    JsonDepthLimit,
    JsonStringSizeLimit,
    ImagePixelCountLimit,
    LocaleCountLimit,

    // UnsupportedReason arms.
    UnsupportedFormatVersion,
    UnknownPassStyle,
    EncryptedArchive,
}

/**
 * Maps a [ParseFailureReason] to its enclosing [ParseFailureKind] bucket. Encodes the 20→4
 * narrowing that exists implicitly across [toFailureReason] / [toFailureKind] so that the
 * cross-field invariant on [ParseFailedEvent] (`reason ∈ buckets-of(outcome)`) is checkable
 * in code rather than enforced by convention. Adding a new [ParseFailureReason] arm without
 * a bucket here is a compile error, the same drift-detector pattern used by [toFailureKind]
 * and [toFailureReason].
 */
public fun ParseFailureReason.toKind(): ParseFailureKind =
    when (this) {
        ParseFailureReason.ManifestSignatureMismatch,
        ParseFailureReason.FileHashMismatch,
        ParseFailureReason.SignatureCryptoFailure,
        ParseFailureReason.SignerCertificateMissing,
        -> ParseFailureKind.Tampered

        ParseFailureReason.NotAZipArchive,
        ParseFailureReason.MissingPassJson,
        ParseFailureReason.MissingManifest,
        ParseFailureReason.InvalidPassJson,
        ParseFailureReason.InvalidManifest,
        ParseFailureReason.InvalidStrings,
        -> ParseFailureKind.Malformed

        ParseFailureReason.ArchiveSizeLimit,
        ParseFailureReason.EntryCountLimit,
        ParseFailureReason.EntrySizeLimit,
        ParseFailureReason.JsonDepthLimit,
        ParseFailureReason.JsonStringSizeLimit,
        ParseFailureReason.ImagePixelCountLimit,
        ParseFailureReason.LocaleCountLimit,
        -> ParseFailureKind.ResourceLimitExceeded

        ParseFailureReason.UnsupportedFormatVersion,
        ParseFailureReason.UnknownPassStyle,
        ParseFailureReason.EncryptedArchive,
        -> ParseFailureKind.Unsupported
    }
