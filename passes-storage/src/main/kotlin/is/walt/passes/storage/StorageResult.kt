package `is`.walt.passes.storage

import `is`.walt.passes.core.EncoderFailureReason
import `is`.walt.passes.core.LabelRejection
import `is`.walt.passes.core.PayloadRejection
import `is`.walt.passes.core.ScannableFormat

/**
 * The result type for every [PassRepository] call. Mirrors the `Result<T> over exceptions`
 * convention from CLAUDE.md and gives the consumer a typed partition of the failure space:
 * key custody failures must be distinguishable from concurrent-open failures from
 * transient-DB failures, because the appropriate UI response differs.
 */
public sealed interface StorageResult<out T> {
    public data class Success<T>(public val value: T) : StorageResult<T>
    public data class Failure(public val error: StorageError) : StorageResult<Nothing>
}

/**
 * Storage failure modes. The arms are deliberately coarse: the consumer needs enough
 * resolution to render the right user-facing message, not enough to second-guess the
 * library's internal handling.
 */
public sealed interface StorageError {
    /**
     * The Keystore master alias has been removed (factory reset partial, app-data clear,
     * Android upgrade dropped the entry, lock-screen credential deleted on a setup that
     * required user-authentication-bound keys). The wrapped DB key cannot be unwrapped;
     * the existing database is unrecoverable. The UI should surface this as
     * "secure storage was reset by the system" and offer to re-import passes.
     */
    public data object KeyUnavailable : StorageError

    /**
     * The Keystore master alias is present but unwrap of [`schema_meta.wrapped_db_key`]
     * failed (GCM tag mismatch, IV mismatch, alias rotated). This is a security-relevant
     * signal distinct from [KeyUnavailable]; the database file may have been replaced
     * out-of-band.
     */
    public data object KeyUnwrapFailed : StorageError

    /**
     * Another process holds an exclusive lock on the database file. Transient; the caller
     * may retry.
     */
    public data object DatabaseLocked : StorageError

    /**
     * A row failed to deserialize at load time and was dropped (e.g. schema-migration
     * partial failure for that row). The repository continues to serve other rows. The
     * [recordId] is a [RecordId], so the wrapper names which table the unknown id
     * belongs to (passes vs documents) without requiring a free-form String.
     */
    public data class IntegrityViolation(public val recordId: RecordId) : StorageError

    /**
     * A document insert was rejected by the storage-side defense-in-depth check
     * (ADR 0005 D7). Carries the same [DocumentStorageRejectedKind] as the matching
     * `onDocumentRejected` telemetry event so callers can distinguish a defensive
     * rejection from a transient infra failure without listening to telemetry. The row
     * never reaches disk.
     */
    public data class DocumentRejected(
        public val kind: DocumentStorageRejectedKind,
    ) : StorageError

    /**
     * A scannable-card insert was rejected by the kernel validator
     * (`ScannableCardInputValidator`). Carries the typed kernel rejection so the
     * consumer's error UI can localize a specific message without re-running
     * validation. The row never reaches disk.
     */
    public data class ScannableCardRejected(
        public val reason: ScannableCardRejectionReason,
    ) : StorageError

    /**
     * The schema version on disk is newer than this build of `passes-storage` understands.
     * This happens when a user downgrades the wallet app. The DB is read-only-protected
     * until a forward-compatible build runs again.
     */
    public data class Unsupported(public val onDiskSchemaVersion: Int) : StorageError

    /**
     * Catch-all for failures that do not warrant a typed arm. Carries a stable [kind] for
     * the telemetry guard; the [cause] is `Throwable?` so the caller can log internally,
     * but [kind] is what flows through telemetry.
     */
    public data class Unknown(
        public val kind: UnknownStorageFailureKind,
        public val cause: Throwable? = null,
    ) : StorageError
}

/**
 * Stable telemetry-friendly enumeration of the open-ended failure space. New arms here
 * are an API addition; new strings are not. Mirrors the `passes-core` discipline of
 * routing telemetry through enums rather than free-form strings.
 */
/**
 * Why a [PassRepository.createScannableCard] call was refused. The first two arms
 * mirror what `ScannableCardInputValidator` produces today (structural payload and
 * label checks). The latter two cover the kernel result family's remaining arms; the
 * validator does not produce them in the current build, but typing them here keeps
 * the defensive path loud rather than collapsing them into [StorageError.Unknown] on
 * the day the kernel does start surfacing one.
 */
public sealed interface ScannableCardRejectionReason {
    public data class InvalidLabel(public val reason: LabelRejection) : ScannableCardRejectionReason

    public data class InvalidPayload(public val reason: PayloadRejection) : ScannableCardRejectionReason

    public data class UnsupportedFormat(public val format: ScannableFormat) : ScannableCardRejectionReason

    public data class EncoderFailure(public val reason: EncoderFailureReason) : ScannableCardRejectionReason
}

public enum class UnknownStorageFailureKind {
    DiskFull,
    PermissionDenied,
    DatabaseCorrupt,
    SerializationFailure,
    Other,
}
