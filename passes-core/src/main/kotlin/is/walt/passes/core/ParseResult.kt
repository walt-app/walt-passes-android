package `is`.walt.passes.core

/**
 * Outcome of attempting to parse a PKPASS archive. The four arms partition along trust /
 * recoverability lines so that the consumer UI can render distinct states without inspecting
 * exception messages:
 *
 * - [Success]: a usable [Pass]. Trust level is in the accompanying [SignatureStatus].
 * - [Tampered]: the archive's signature or per-file hash did not validate. Never coalesce
 *   with [Malformed] in UI; tampering implies a security event, malformedness does not.
 * - [Malformed]: the archive structure is invalid or exceeds a configured resource limit.
 * - [Unsupported]: the archive is structurally valid but uses a feature this parser does
 *   not handle (e.g. an unknown formatVersion).
 */
public sealed interface ParseResult {
    public data class Success(
        public val pass: Pass,
        public val signatureStatus: SignatureStatus,
    ) : ParseResult

    public data class Tampered(public val reason: TamperReason) : ParseResult

    public data class Malformed(public val reason: MalformedReason) : ParseResult

    public data class Unsupported(public val reason: UnsupportedReason) : ParseResult
}

public sealed interface TamperReason {
    /** The PKCS#7 detached signature failed cryptographic verification against `manifest.json`. */
    public data object ManifestSignatureMismatch : TamperReason

    /** A file's SHA-1 hash in `manifest.json` did not match the file's actual contents. */
    public data object FileHashMismatch : TamperReason

    /** The signature blob is structurally a PKCS#7 envelope but cryptographically malformed. */
    public data object SignatureCryptoFailure : TamperReason
}

public sealed interface MalformedReason {
    public data object NotAZipArchive : MalformedReason

    public data object MissingPassJson : MalformedReason

    public data object MissingManifest : MalformedReason

    public data object InvalidPassJson : MalformedReason

    public data object InvalidManifest : MalformedReason

    public data class ResourceLimitExceeded(public val limit: ResourceLimit) : MalformedReason
}

/**
 * Which guard from [ParserConfig] tripped. Surfaced separately from the structural failures
 * so monitoring (via [TelemetryGuard]) can distinguish a misconfiguration from an attack
 * payload.
 */
public enum class ResourceLimit {
    ArchiveSize,
    EntryCount,
    EntrySize,
    JsonDepth,
    JsonStringSize,
    ImagePixelCount,
    LocaleCount,
}

public sealed interface UnsupportedReason {
    public data class FormatVersion(public val version: Int) : UnsupportedReason

    /** The pass.json declared a top-level pass-style key this parser does not implement. */
    public data class UnknownPassStyle(public val raw: String) : UnsupportedReason

    public data object EncryptedArchive : UnsupportedReason
}

/**
 * Telemetry-safe flattening of failure outcomes. [ParseResult.Success] returns `null` —
 * success is not a failure event. The exhaustive `when` is the drift detector: adding a
 * [ParseResult] arm without extending [ParseFailureKind] is a compile error. Resource-limit
 * hits are pulled out of [ParseResult.Malformed] into their own bucket because operationally
 * they are the most useful failure to alert on (they signal a too-tight [ParserConfig], not
 * an attack payload).
 */
public fun ParseResult.toFailureKind(): ParseFailureKind? =
    when (this) {
        is ParseResult.Success -> null
        is ParseResult.Tampered -> ParseFailureKind.Tampered
        is ParseResult.Malformed ->
            when (reason) {
                is MalformedReason.ResourceLimitExceeded -> ParseFailureKind.ResourceLimitExceeded
                else -> ParseFailureKind.Malformed
            }
        is ParseResult.Unsupported -> ParseFailureKind.Unsupported
    }
