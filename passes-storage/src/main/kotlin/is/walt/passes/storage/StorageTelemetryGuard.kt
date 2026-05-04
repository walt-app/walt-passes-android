package `is`.walt.passes.storage

import `is`.walt.passes.core.PassType

/**
 * The storage-side counterpart to `passes-core`'s `TelemetryGuard`. Carries the same
 * structural-PII-prevention discipline: every method takes only enums, counts, and
 * durations. There is no `String`, `Pass`, `PassField`, or `ByteArray` parameter
 * anywhere in this interface.
 *
 * The trust claim "pass content never appears in storage telemetry" is delivered by the
 * shape of these methods, not by documentation. Reviewers MUST treat any future addition
 * of a free-form parameter as a security-policy change.
 */
public interface StorageTelemetryGuard {
    /**
     * Emitted once per database open after the key provider is initialized. Reports the
     * actual Keystore backing chosen so the wallet can surface hardware-backing status
     * without inspecting strings.
     */
    public fun onKeyProviderInitialized(backing: KeyBacking)

    /**
     * A pass was inserted or replaced. Carries the type and trust band only.
     */
    public fun onPassUpserted(
        type: PassType,
        signatureStatus: SignatureStatusKind,
        wasReplacement: Boolean,
    )

    /**
     * A pass row was deleted (D6). Emitted after the transaction commits and after the
     * StateFlow is updated.
     */
    public fun onPassDeleted(type: PassType, signatureStatus: SignatureStatusKind)

    /**
     * A row failed to deserialize during a load or schema migration and was dropped.
     * Carries only the failure kind; the row identity is intentionally NOT part of the
     * event because the row is gone.
     */
    public fun onMigrationRowDropped(kind: MigrationFailureKind)

    /**
     * A storage call returned [StorageResult.Failure]. The [kind] is the structural
     * arm of [StorageError]; for [StorageError.Unknown] cases the [unknownKind] carries
     * the [UnknownStorageFailureKind].
     */
    public fun onStorageFailure(
        kind: StorageFailureKind,
        unknownKind: UnknownStorageFailureKind?,
    )
}

/**
 * Flattened enum mirror of `passes-core`'s `SignatureStatus` arms, suitable for crossing
 * a metric backend that wants string dimensions. New arms here MUST mirror new arms in
 * `SignatureStatus`.
 */
public enum class SignatureStatusKind {
    Unsigned,
    SelfSigned,
    AppleVerified,
    CertChainIncomplete,
}

/**
 * Stable telemetry projection of [StorageError]. Mirrors the sealed-interface arms
 * one-for-one. New arms in [StorageError] require new arms here.
 */
public enum class StorageFailureKind {
    KeyUnavailable,
    KeyUnwrapFailed,
    DatabaseLocked,
    IntegrityViolation,
    Unsupported,
    Unknown,
}

public enum class MigrationFailureKind {
    JsonShapeMismatch,
    UnknownEnumValue,
    BlobTruncated,
    Other,
}
