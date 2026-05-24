package `is`.walt.passes.storage

import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.ScannableFormat

/**
 * Telemetry sink for instrumentation tests. The on-device tests verify storage behavior,
 * not the structural-PII-prevention shape of the telemetry interface (the JVM suite
 * already locks that). Discarding events here keeps the device tests focused.
 */
internal object NoOpStorageTelemetryGuard : StorageTelemetryGuard {
    override fun onKeyProviderInitialized(backing: KeyBacking) = Unit
    override fun onPassUpserted(
        type: PassType,
        signatureStatus: SignatureStatusKind,
        wasReplacement: Boolean,
    ) = Unit
    override fun onPassDeleted(type: PassType, signatureStatus: SignatureStatusKind) = Unit
    override fun onMigrationRowDropped(kind: MigrationFailureKind) = Unit
    override fun onStorageFailure(
        kind: StorageFailureKind,
        unknownKind: UnknownStorageFailureKind?,
    ) = Unit
    override fun onDocumentImported(event: DocumentImportedEvent) = Unit
    override fun onDocumentRejected(kind: DocumentStorageRejectedKind) = Unit
    override fun onDocumentDeleted(event: DocumentDeletedEvent) = Unit
    override fun onScannableCardCreated(format: ScannableFormat) = Unit
    override fun onScannableCardDeleted(format: ScannableFormat) = Unit
    override fun onScannableCardRejected(kind: ScannableCardRejectedKind) = Unit
    override fun onUserLabelUpdated(
        type: PassType,
        hadPriorLabel: Boolean,
        clearing: Boolean,
    ) = Unit
    override fun onPassRejected(kind: PassUpdateRejectedKind) = Unit
}
