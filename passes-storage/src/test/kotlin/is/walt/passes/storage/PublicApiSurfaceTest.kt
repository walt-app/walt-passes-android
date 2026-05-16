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
            StorageError.IntegrityViolation(DocumentRecordId(2L)),
            StorageError.Unsupported(onDiskSchemaVersion = 99),
            StorageError.Unknown(UnknownStorageFailureKind.DiskFull),
            StorageError.DocumentRejected(DocumentStorageRejectedKind.OversizedAtStorage),
        )
        val labels = errors.map { error ->
            when (error) {
                StorageError.KeyUnavailable -> "key-unavailable"
                StorageError.KeyUnwrapFailed -> "key-unwrap-failed"
                StorageError.DatabaseLocked -> "db-locked"
                is StorageError.IntegrityViolation -> when (val id = error.recordId) {
                    is PassRecordId -> "integrity-pass:${id.value}"
                    is DocumentRecordId -> "integrity-doc:${id.value}"
                }
                is StorageError.Unsupported -> "unsupported:${error.onDiskSchemaVersion}"
                is StorageError.Unknown -> "unknown:${error.kind.name}"
                is StorageError.DocumentRejected -> "doc-rejected:${error.kind.name}"
            }
        }
        assertThat(labels).containsExactly(
            "key-unavailable",
            "key-unwrap-failed",
            "db-locked",
            "integrity-pass:1",
            "integrity-doc:2",
            "unsupported:99",
            "unknown:DiskFull",
            "doc-rejected:OversizedAtStorage",
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
            StorageError.DocumentRejected(DocumentStorageRejectedKind.OversizedAtStorage),
        )
        val kinds = errors.map { error ->
            when (error) {
                StorageError.KeyUnavailable -> StorageFailureKind.KeyUnavailable
                StorageError.KeyUnwrapFailed -> StorageFailureKind.KeyUnwrapFailed
                StorageError.DatabaseLocked -> StorageFailureKind.DatabaseLocked
                is StorageError.IntegrityViolation -> StorageFailureKind.IntegrityViolation
                is StorageError.Unsupported -> StorageFailureKind.Unsupported
                is StorageError.Unknown -> StorageFailureKind.Unknown
                is StorageError.DocumentRejected -> StorageFailureKind.DocumentRejected
            }
        }
        assertThat(kinds.toSet()).containsExactlyElementsIn(StorageFailureKind.entries)
    }

    @Test
    fun recordIdSealedArmsAreExhaustive() {
        // Pinning the sealed surface: adding a third RecordId variant requires updating
        // every consumer that pattern-matches on it (StorageError.IntegrityViolation
        // routing in tests, telemetry projections, etc.).
        val ids: List<RecordId> = listOf(PassRecordId(1L), DocumentRecordId(2L))
        val labels = ids.map { id ->
            when (id) {
                is PassRecordId -> "pass:${id.value}"
                is DocumentRecordId -> "doc:${id.value}"
            }
        }
        assertThat(labels).containsExactly("pass:1", "doc:2").inOrder()
    }

    @Test
    fun schemaDeclaresSixTablesAndIsAtVersionTwo() {
        assertThat(Schema.VERSION).isEqualTo(2)
        assertThat(Schema.Tables.SCHEMA_META).isEqualTo("schema_meta")
        assertThat(Schema.Tables.PASSES).isEqualTo("passes")
        assertThat(Schema.Tables.PASS_IMAGES).isEqualTo("pass_images")
        assertThat(Schema.Tables.PASS_LOCALES).isEqualTo("pass_locales")
        assertThat(Schema.Tables.DOCUMENTS).isEqualTo("documents")
        assertThat(Schema.Tables.DOCUMENT_THUMBNAILS).isEqualTo("document_thumbnails")
        // schema_meta + passes + 3 pass-side indexes + pass_images + pass_locales
        // + documents + 1 document index + document_thumbnails = 10 statements.
        assertThat(Schema.DDL).hasSize(10)
        assertThat(Schema.MIGRATIONS.keys).containsExactly(1)
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

    @Test
    fun copyForRetainedConsumerZerosTheMasterAndReturnsLiveCopy() {
        val raw = ByteArray(32) { (it + 1).toByte() }
        val key = DatabaseKey(raw)
        val buffer = key.copyForRetainedConsumer()
        // Master buffer is zeroed as soon as the copy is handed off: a subsequent
        // withBytes / copyForRetainedConsumer call cannot re-hand the live key, which
        // is the type-level half of the wpass-aio symptom prevention.
        assertThat(raw.all { it.toInt() == 0 }).isTrue()
        // The copy is the live key, alive until the buffer is closed - this is the
        // SQLCipher connection-pool contract: the binding holds the password byte[]
        // by reference and lazily re-keys pool connections from it.
        buffer.use { buf ->
            assertThat(buf.bytes.any { it.toInt() != 0 }).isTrue()
            assertThat(buf.bytes.toList()).isEqualTo((1..32).map { it.toByte() })
        }
    }

    @Test
    fun retainedKeyBufferCloseZerosTheBytes() {
        val raw = ByteArray(32) { (it + 1).toByte() }
        val buffer = DatabaseKey(raw).copyForRetainedConsumer()
        val internalBytes = buffer.bytes
        assertThat(internalBytes.any { it.toInt() != 0 }).isTrue()
        buffer.close()
        assertThat(internalBytes.all { it.toInt() == 0 }).isTrue()
    }

    @Test
    fun copyForRetainedConsumerThrowsOnSecondCall() {
        // The footgun the refactor closes: re-handing an already-zeroed master to
        // SQLCipher is the exact wpass-aio symptom. Surface loudly at the call site
        // instead of silently producing a pool keyed with zeros.
        val key = DatabaseKey(ByteArray(32) { (it + 1).toByte() })
        key.copyForRetainedConsumer().close()
        val thrown = assertFails { key.copyForRetainedConsumer() }
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown).hasMessageThat().contains("already consumed")
    }

    @Test
    fun copyForRetainedConsumerThrowsAfterWithBytes() {
        val key = DatabaseKey(ByteArray(32) { (it + 1).toByte() })
        key.withBytes { /* consume */ }
        val thrown = assertFails { key.copyForRetainedConsumer() }
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun withBytesThrowsOnSecondCall() {
        val key = DatabaseKey(ByteArray(32) { (it + 1).toByte() })
        key.withBytes { /* consume */ }
        val thrown = assertFails { key.withBytes { error("should not run") } }
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown).hasMessageThat().contains("already consumed")
    }

    @Test
    fun withBytesThrowsAfterCopyForRetainedConsumer() {
        val key = DatabaseKey(ByteArray(32) { (it + 1).toByte() })
        key.copyForRetainedConsumer().close()
        val thrown = assertFails { key.withBytes { error("should not run") } }
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun consumedFlagFlipsBeforeBlock_soWithBytesIsSingleUseEvenIfBlockThrows() {
        // Set-before-block semantics: a throwing consumer still claims the key, so a
        // retry against the same DatabaseKey is rejected. The buffer is zeroed by the
        // finally block on the throw path.
        val raw = ByteArray(32) { (it + 1).toByte() }
        val key = DatabaseKey(raw)
        val boom = RuntimeException("simulated consumer failure")
        try {
            key.withBytes { throw boom }
            error("expected the block's exception to propagate")
        } catch (caught: RuntimeException) {
            assertThat(caught).isSameInstanceAs(boom)
        }
        assertThat(raw.all { it.toInt() == 0 }).isTrue()
        val retry = assertFails { key.withBytes { error("should not run") } }
        assertThat(retry).isInstanceOf(IllegalStateException::class.java)
    }

    private inline fun assertFails(block: () -> Unit): Throwable {
        try {
            block()
        } catch (t: Throwable) {
            return t
        }
        error("expected the block to throw")
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

            override fun onDocumentImported(event: DocumentImportedEvent) {
                recorded += "doc-imported:${event.byteCount}:${event.pageCount}"
            }

            override fun onDocumentRejected(kind: DocumentStorageRejectedKind) {
                recorded += "doc-rejected:${kind.name}"
            }

            override fun onDocumentDeleted(event: DocumentDeletedEvent) {
                recorded += "doc-deleted:${event.byteCount}"
            }
        }
        guard.onKeyProviderInitialized(KeyBacking.StrongBox)
        guard.onPassUpserted(PassType.BoardingPass, SignatureStatusKind.AppleVerified, false)
        guard.onPassDeleted(PassType.EventTicket, SignatureStatusKind.SelfSigned)
        guard.onMigrationRowDropped(MigrationFailureKind.JsonShapeMismatch)
        guard.onStorageFailure(StorageFailureKind.Unknown, UnknownStorageFailureKind.DiskFull)
        guard.onDocumentImported(DocumentImportedEvent(byteCount = 1024L, pageCount = 3))
        guard.onDocumentRejected(DocumentStorageRejectedKind.OversizedAtStorage)
        guard.onDocumentDeleted(DocumentDeletedEvent(byteCount = 2048L))

        assertThat(recorded).containsExactly(
            "init:StrongBox",
            "upsert:BoardingPass:AppleVerified:false",
            "delete:EventTicket:SelfSigned",
            "drop:JsonShapeMismatch",
            "failure:Unknown:DiskFull",
            "doc-imported:1024:3",
            "doc-rejected:OversizedAtStorage",
            "doc-deleted:2048",
        ).inOrder()
    }

    @Test
    fun documentStorageRejectedKindCoversTheThreeStorageSideArms() {
        assertThat(DocumentStorageRejectedKind.entries.map { it.name }).containsExactly(
            "OversizedAtStorage",
            "TooManyPagesAtStorage",
            "LabelTooLongAtStorage",
        ).inOrder()
    }

    @Test
    fun documentBoundsMirrorAdr0005D7CapsAndCarryALabelLengthCap() {
        // The `passes-pdf-core` renderer-service enforces MAX_BYTES and MAX_PAGES.
        // Storage carries them again so a future caller bug cannot land an oversized
        // row. MAX_LABEL_CHARS is enforced only at this layer; nothing upstream bounds
        // the consumer-supplied display label, so a multi-MB string would inflate the
        // indexed list-view query without this cap.
        assertThat(DocumentBounds.MAX_BYTES).isEqualTo(25L * 1024 * 1024)
        assertThat(DocumentBounds.MAX_PAGES).isEqualTo(10)
        assertThat(DocumentBounds.MAX_LABEL_CHARS).isEqualTo(256)
    }
}
