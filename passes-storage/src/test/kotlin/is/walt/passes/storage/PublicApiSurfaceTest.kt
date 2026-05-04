package `is`.walt.passes.storage

import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.SignatureStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the public API surface of `passes-storage`. Mirrors the discipline of
 * `passes-core`'s `PublicApiSurfaceTest`: every sealed arm is reached via an exhaustive
 * `when` so that adding or removing an arm forces a compile-time conversation.
 *
 * Implementation behavior (SQLCipher round-trip, Keystore wrapping, Auto Backup
 * exclusion) is exercised by the implementation bead's instrumentation tests; this file
 * stays JVM-only.
 */
class PublicApiSurfaceTest {

    @Test
    fun storageResultArmsAreReachableViaWhen() {
        val result: StorageResult<Long> = StorageResult.Success(7L)
        val branch = when (result) {
            is StorageResult.Success -> "success:${result.value}"
            is StorageResult.Failure -> "failure"
        }
        assertThat(branch).isEqualTo("success:7")
    }

    @Test
    fun storageErrorArmsAreReachableViaWhen() {
        val errors: List<StorageError> = listOf(
            StorageError.KeyUnavailable,
            StorageError.KeyUnwrapFailed,
            StorageError.DatabaseLocked,
            StorageError.IntegrityViolation(PassRecordId(1L)),
            StorageError.Unsupported(onDiskSchemaVersion = 99),
            StorageError.Unknown(UnknownStorageFailureKind.DiskFull),
        )
        val labels = errors.map { error ->
            when (error) {
                StorageError.KeyUnavailable -> "key-unavailable"
                StorageError.KeyUnwrapFailed -> "key-unwrap-failed"
                StorageError.DatabaseLocked -> "db-locked"
                is StorageError.IntegrityViolation -> "integrity:${error.recordId.value}"
                is StorageError.Unsupported -> "unsupported:${error.onDiskSchemaVersion}"
                is StorageError.Unknown -> "unknown:${error.kind.name}"
            }
        }
        assertThat(labels).containsExactly(
            "key-unavailable",
            "key-unwrap-failed",
            "db-locked",
            "integrity:1",
            "unsupported:99",
            "unknown:DiskFull",
        ).inOrder()
    }

    @Test
    fun unknownStorageFailureKindCoversTheDocumentedFiveBuckets() {
        assertThat(UnknownStorageFailureKind.entries.map { it.name }).containsExactly(
            "DiskFull",
            "PermissionDenied",
            "DatabaseCorrupt",
            "SerializationFailure",
            "Other",
        ).inOrder()
    }

    @Test
    fun keyBackingEnumeratesTheThreeDocumentedBackings() {
        assertThat(KeyBacking.entries.map { it.name }).containsExactly(
            "StrongBox",
            "Tee",
            "Software",
        ).inOrder()
    }

    @Test
    fun signatureStatusKindMirrorsCoreSignatureStatusArms() {
        val statuses: List<SignatureStatus> = listOf(
            SignatureStatus.Unsigned,
            SignatureStatus.SelfSigned,
            SignatureStatus.AppleVerified,
            SignatureStatus.CertChainIncomplete,
        )
        // Exhaustive when on SignatureStatus side; flip to SignatureStatusKind keeps lockstep.
        val kinds = statuses.map { status ->
            when (status) {
                SignatureStatus.Unsigned -> SignatureStatusKind.Unsigned
                SignatureStatus.SelfSigned -> SignatureStatusKind.SelfSigned
                SignatureStatus.AppleVerified -> SignatureStatusKind.AppleVerified
                SignatureStatus.CertChainIncomplete -> SignatureStatusKind.CertChainIncomplete
            }
        }
        assertThat(kinds.toSet()).containsExactlyElementsIn(SignatureStatusKind.entries)
    }

    @Test
    fun migrationFailureKindCoversTheDocumentedBuckets() {
        assertThat(MigrationFailureKind.entries.map { it.name }).containsExactly(
            "JsonShapeMismatch",
            "UnknownEnumValue",
            "BlobTruncated",
            "Other",
        ).inOrder()
    }

    @Test
    fun storageFailureKindMirrorsStorageErrorArmsOneForOne() {
        val errors: List<StorageError> = listOf(
            StorageError.KeyUnavailable,
            StorageError.KeyUnwrapFailed,
            StorageError.DatabaseLocked,
            StorageError.IntegrityViolation(PassRecordId(1L)),
            StorageError.Unsupported(0),
            StorageError.Unknown(UnknownStorageFailureKind.Other),
        )
        val kinds = errors.map { error ->
            when (error) {
                StorageError.KeyUnavailable -> StorageFailureKind.KeyUnavailable
                StorageError.KeyUnwrapFailed -> StorageFailureKind.KeyUnwrapFailed
                StorageError.DatabaseLocked -> StorageFailureKind.DatabaseLocked
                is StorageError.IntegrityViolation -> StorageFailureKind.IntegrityViolation
                is StorageError.Unsupported -> StorageFailureKind.Unsupported
                is StorageError.Unknown -> StorageFailureKind.Unknown
            }
        }
        assertThat(kinds.toSet()).containsExactlyElementsIn(StorageFailureKind.entries)
    }

    @Test
    fun schemaDeclaresFourTablesAndIsAtVersionOne() {
        assertThat(Schema.VERSION).isEqualTo(1)
        assertThat(Schema.Tables.SCHEMA_META).isEqualTo("schema_meta")
        assertThat(Schema.Tables.PASSES).isEqualTo("passes")
        assertThat(Schema.Tables.PASS_IMAGES).isEqualTo("pass_images")
        assertThat(Schema.Tables.PASS_LOCALES).isEqualTo("pass_locales")
        // 1 schema_meta + 1 passes + 3 indexes + 1 pass_images + 1 pass_locales = 7 statements.
        assertThat(Schema.DDL).hasSize(7)
        assertThat(Schema.MIGRATIONS).isEmpty()
    }

    @Test
    fun metaKeysAreThePersistenceVocabularyDocumentedInTheADR() {
        assertThat(Schema.MetaKeys.SCHEMA_VERSION).isEqualTo("schema_version")
        assertThat(Schema.MetaKeys.WRAPPED_DB_KEY).isEqualTo("wrapped_db_key")
        assertThat(Schema.MetaKeys.WRAPPED_DB_KEY_IV).isEqualTo("wrapped_db_key_iv")
        assertThat(Schema.MetaKeys.KEY_ALIAS).isEqualTo("key_alias")
        assertThat(Schema.MetaKeys.KEY_BACKING).isEqualTo("key_backing")
    }

    @Test
    fun passRecordIdIsAValueClassWrappingALong() {
        val id = PassRecordId(42L)
        assertThat(id.value).isEqualTo(42L)
    }

    @Test
    fun databaseKeyRejectsNon32ByteInput() {
        try {
            DatabaseKey(ByteArray(31))
            error("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected.message).contains("32 bytes")
        }
    }

    @Test
    fun databaseKeyToStringIsRedacted() {
        val key = DatabaseKey(ByteArray(32))
        assertThat(key.toString()).isEqualTo("DatabaseKey(redacted)")
    }

    @Test
    fun databaseKeyWithBytesZerosTheBufferAfterTheBlockReturns() {
        val raw = ByteArray(32) { (it + 1).toByte() }
        val key = DatabaseKey(raw)
        var sawNonZero = false
        key.withBytes { borrowed ->
            sawNonZero = borrowed.any { it.toInt() != 0 }
        }
        assertThat(sawNonZero).isTrue()
        assertThat(raw.all { it.toInt() == 0 }).isTrue()
    }

    /**
     * Pins the `StorageTelemetryGuard` PII discipline (ADR 0002 D8). Every event method
     * is reachable here with enums-and-primitives-only arguments. Adding a free-form
     * `String`, `ByteArray`, `Pass`, or `PassField` parameter to any method below would
     * fail to compile against this lock without a deliberate edit.
     */
    @Test
    fun storageTelemetryGuardEventsAreEnumsAndPrimitivesOnly() {
        val recorded = mutableListOf<String>()
        val guard = object : StorageTelemetryGuard {
            override fun onKeyProviderInitialized(backing: KeyBacking) {
                recorded += "init:${backing.name}"
            }

            override fun onPassUpserted(
                type: PassType,
                signatureStatus: SignatureStatusKind,
                wasReplacement: Boolean,
            ) {
                recorded += "upsert:${type.name}:${signatureStatus.name}:$wasReplacement"
            }

            override fun onPassDeleted(type: PassType, signatureStatus: SignatureStatusKind) {
                recorded += "delete:${type.name}:${signatureStatus.name}"
            }

            override fun onMigrationRowDropped(kind: MigrationFailureKind) {
                recorded += "drop:${kind.name}"
            }

            override fun onStorageFailure(
                kind: StorageFailureKind,
                unknownKind: UnknownStorageFailureKind?,
            ) {
                recorded += "failure:${kind.name}:${unknownKind?.name ?: "n/a"}"
            }
        }
        guard.onKeyProviderInitialized(KeyBacking.StrongBox)
        guard.onPassUpserted(PassType.BoardingPass, SignatureStatusKind.AppleVerified, false)
        guard.onPassDeleted(PassType.EventTicket, SignatureStatusKind.SelfSigned)
        guard.onMigrationRowDropped(MigrationFailureKind.JsonShapeMismatch)
        guard.onStorageFailure(StorageFailureKind.Unknown, UnknownStorageFailureKind.DiskFull)

        assertThat(recorded).containsExactly(
            "init:StrongBox",
            "upsert:BoardingPass:AppleVerified:false",
            "delete:EventTicket:SelfSigned",
            "drop:JsonShapeMismatch",
            "failure:Unknown:DiskFull",
        ).inOrder()
    }
}
