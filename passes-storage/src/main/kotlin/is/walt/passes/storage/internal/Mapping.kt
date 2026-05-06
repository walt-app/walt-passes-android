package `is`.walt.passes.storage.internal

import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.core.SignatureStatusKind
import `is`.walt.passes.storage.StorageError
import `is`.walt.passes.storage.StorageFailureKind

/**
 * Inverse of `passes-core`'s `SignatureStatus.toKind()`. Used when re-hydrating
 * `signature_status_kind` from a cursor: the on-disk vocabulary is the [SignatureStatusKind]
 * `name`, so unknown names route through the migration-row-dropped path before this is
 * reached.
 */
internal fun SignatureStatusKind.toSignatureStatus(): SignatureStatus = when (this) {
    SignatureStatusKind.Unsigned -> SignatureStatus.Unsigned
    SignatureStatusKind.SelfSigned -> SignatureStatus.SelfSigned
    SignatureStatusKind.AppleVerified -> SignatureStatus.AppleVerified
    SignatureStatusKind.CertChainIncomplete -> SignatureStatus.CertChainIncomplete
}

/**
 * Stable telemetry projection of [StorageError]. The exhaustive `when` is the load-bearing
 * drift detector: a new error arm without a matching enum entry is a compile error, not a
 * silent observability gap.
 */
internal fun StorageError.toFailureKind(): StorageFailureKind = when (this) {
    StorageError.KeyUnavailable -> StorageFailureKind.KeyUnavailable
    StorageError.KeyUnwrapFailed -> StorageFailureKind.KeyUnwrapFailed
    StorageError.DatabaseLocked -> StorageFailureKind.DatabaseLocked
    is StorageError.IntegrityViolation -> StorageFailureKind.IntegrityViolation
    is StorageError.Unsupported -> StorageFailureKind.Unsupported
    is StorageError.Unknown -> StorageFailureKind.Unknown
    is StorageError.DocumentRejected -> StorageFailureKind.DocumentRejected
}
