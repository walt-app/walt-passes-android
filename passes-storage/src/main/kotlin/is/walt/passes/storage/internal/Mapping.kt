package `is`.walt.passes.storage.internal

import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.storage.SignatureStatusKind
import `is`.walt.passes.storage.StorageError
import `is`.walt.passes.storage.StorageFailureKind

/** Mirrors the `passes-core` sealed interface arms onto the storage telemetry enum. */
internal fun SignatureStatus.toSignatureStatusKind(): SignatureStatusKind = when (this) {
    SignatureStatus.Unsigned -> SignatureStatusKind.Unsigned
    SignatureStatus.SelfSigned -> SignatureStatusKind.SelfSigned
    SignatureStatus.AppleVerified -> SignatureStatusKind.AppleVerified
    SignatureStatus.CertChainIncomplete -> SignatureStatusKind.CertChainIncomplete
}

/** Stable telemetry projection of [StorageError]. New error arms require new enum arms. */
internal fun StorageError.toFailureKind(): StorageFailureKind = when (this) {
    StorageError.KeyUnavailable -> StorageFailureKind.KeyUnavailable
    StorageError.KeyUnwrapFailed -> StorageFailureKind.KeyUnwrapFailed
    StorageError.DatabaseLocked -> StorageFailureKind.DatabaseLocked
    is StorageError.IntegrityViolation -> StorageFailureKind.IntegrityViolation
    is StorageError.Unsupported -> StorageFailureKind.Unsupported
    is StorageError.Unknown -> StorageFailureKind.Unknown
}
