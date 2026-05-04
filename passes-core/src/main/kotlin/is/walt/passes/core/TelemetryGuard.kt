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

public data class ParseFailedEvent(
    public val outcome: ParseFailureKind,
    public val durationMillis: Long,
)

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
