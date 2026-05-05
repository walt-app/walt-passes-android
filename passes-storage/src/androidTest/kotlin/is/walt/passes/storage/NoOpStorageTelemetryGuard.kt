package `is`.walt.passes.storage

import `is`.walt.passes.core.PassType

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
}
