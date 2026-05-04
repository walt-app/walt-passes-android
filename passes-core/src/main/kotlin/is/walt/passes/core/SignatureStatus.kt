package `is`.walt.passes.core

/**
 * The provenance of a successfully-parsed pass. Per decision-wlt-0tn-q1, the parser accepts
 * unsigned and self-signed archives by default and surfaces their status here so the UI can
 * communicate trust to the user. Cryptographic *failures* are not reported here: they
 * produce a [ParseResult.Tampered] outcome instead.
 *
 * Distinguishing [Unsigned] from [SelfSigned] from [AppleVerified] is the point of this
 * type; collapsing them in UI defeats the purpose of the lenient policy.
 */
public sealed interface SignatureStatus {
    /** No `signature` file present in the archive. The archive is just a zipped manifest. */
    public data object Unsigned : SignatureStatus

    /**
     * The signature validates against its certificate, but the certificate chain does not
     * terminate at an Apple-issued root.
     */
    public data object SelfSigned : SignatureStatus

    /**
     * The signature validates and the certificate chain terminates at the Apple WWDR root
     * trusted by Apple Wallet. This is the strongest provenance pkpass offers.
     */
    public data object AppleVerified : SignatureStatus

    /**
     * The signature validates against the leaf certificate present in the archive, but
     * intermediate certificates required to reach a known root were absent and the parser
     * did not perform external fetches.
     */
    public data object CertChainIncomplete : SignatureStatus
}

/**
 * Telemetry-safe flattening. The exhaustive `when` here is the load-bearing drift detector:
 * adding a [SignatureStatus] arm without extending [SignatureStatusKind] is a compile error,
 * not a silent observability gap.
 */
public fun SignatureStatus.toKind(): SignatureStatusKind = when (this) {
    SignatureStatus.Unsigned -> SignatureStatusKind.Unsigned
    SignatureStatus.SelfSigned -> SignatureStatusKind.SelfSigned
    SignatureStatus.AppleVerified -> SignatureStatusKind.AppleVerified
    SignatureStatus.CertChainIncomplete -> SignatureStatusKind.CertChainIncomplete
}
