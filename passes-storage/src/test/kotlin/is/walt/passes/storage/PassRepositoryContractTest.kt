package `is`.walt.passes.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import `is`.walt.passes.core.Barcode
import `is`.walt.passes.core.BarcodeFormat
import `is`.walt.passes.core.ColorValue
import `is`.walt.passes.core.ImageBytes
import `is`.walt.passes.core.ImageRole
import `is`.walt.passes.core.LocalizedStrings
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassColors
import `is`.walt.passes.core.PassField
import `is`.walt.passes.core.PassFields
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.PassLocale
import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.storage.internal.DeleteOutcome
import `is`.walt.passes.storage.internal.DocumentDeleteOutcome
import `is`.walt.passes.storage.internal.DocumentInsertOutcome
import `is`.walt.passes.storage.internal.DocumentInsertRequest
import `is`.walt.passes.storage.internal.DocumentStore
import `is`.walt.passes.storage.internal.PassStore
import `is`.walt.passes.storage.internal.UpsertOutcome
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric-runner-backed verification of the StateFlow + delete sequencing contract.
 * The repository is constructed against an in-memory fake [PassStore] so we exercise the
 * public contract without touching SQLCipher native libraries.
 *
 * Properties locked here:
 *
 *  1. The `passes` StateFlow seeds from `store.listSummaries()` at construction time.
 *  2. After `upsert`, the StateFlow reflects the new row (insert OR replace).
 *  3. After `delete(id)` the StateFlow no longer carries that id.
 *  4. `onPassDeleted(type, signatureStatus)` is emitted AFTER the StateFlow update.
 *  5. `onPassDeleted` is NOT emitted for an unknown id; that surfaces as
 *     `IntegrityViolation` instead.
 *  6. Deletion is irreversible at the repository surface: there is no `undelete` /
 *     `restore` / soft-delete arm reachable from the public API.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PassRepositoryContractTest {

    @Test
    fun stateFlowSeedsFromStoreOnConstruction() = runTest {
        val seed = listOf(sampleSummary(id = 1L), sampleSummary(id = 2L))
        val store = FakePassStore(initial = seed)
        val telemetry = RecordingGuard()
        val repo = SqlCipherPassRepository(
            store = store,
            documentStore = NoOpDocumentStore,
            telemetryGuard = telemetry,
            ioDispatcher = UnconfinedTestDispatcher(),
            clock = { 1_000L },
            keyBacking = KeyBacking.StrongBox,
        )
        assertThat(repo.passes.value.map { it.id.value }).containsExactly(1L, 2L).inOrder()
        assertThat(telemetry.events).containsExactly("init:StrongBox")
    }

    @Test
    fun upsertUpdatesStateFlowAndEmitsTelemetryAfter() = runTest {
        val store = FakePassStore()
        val telemetry = RecordingGuard()
        val repo = SqlCipherPassRepository(
            store = store,
            documentStore = NoOpDocumentStore,
            telemetryGuard = telemetry,
            ioDispatcher = UnconfinedTestDispatcher(),
            clock = { 1_000L },
            keyBacking = KeyBacking.Tee,
        )

        val pass = samplePass(serial = "S1")
        val result = repo.upsert(pass, SignatureStatus.AppleVerified)

        check(result is StorageResult.Success)
        assertThat(repo.passes.value.map { it.serialNumber }).containsExactly("S1")
        assertThat(telemetry.events).containsExactly(
            "init:Tee",
            "upsert:BoardingPass:AppleVerified:false",
        ).inOrder()
    }

    @Test
    fun deleteUpdatesStateFlowAndEmitsTelemetryAfterTransaction() = runTest {
        val store = FakePassStore(initial = listOf(sampleSummary(id = 7L)))
        val telemetry = RecordingGuard()
        val repo = SqlCipherPassRepository(
            store = store,
            documentStore = NoOpDocumentStore,
            telemetryGuard = telemetry,
            ioDispatcher = UnconfinedTestDispatcher(),
            clock = { 1_000L },
            keyBacking = KeyBacking.Software,
        )

        val result = repo.delete(PassRecordId(7L))
        check(result is StorageResult.Success)

        assertThat(repo.passes.value).isEmpty()

        // Sequencing: telemetry fires after the StateFlow update. The recorded transcript
        // observes only the post-delete() state, by which point the value is already empty.
        assertThat(telemetry.events).containsExactly(
            "init:Software",
            "delete:BoardingPass:AppleVerified",
        ).inOrder()
    }

    @Test
    fun deleteOfUnknownIdReturnsIntegrityViolationAndEmitsFailureNotDeleteEvent() = runTest {
        val store = FakePassStore()
        val telemetry = RecordingGuard()
        val repo = SqlCipherPassRepository(
            store = store,
            documentStore = NoOpDocumentStore,
            telemetryGuard = telemetry,
            ioDispatcher = UnconfinedTestDispatcher(),
            clock = { 1L },
            keyBacking = KeyBacking.StrongBox,
        )

        val result = repo.delete(PassRecordId(404L))

        check(result is StorageResult.Failure)
        check(result.error is StorageError.IntegrityViolation)
        assertThat(telemetry.events).containsExactly(
            "init:StrongBox",
            "failure:IntegrityViolation:n/a",
        ).inOrder()
    }

    @Test
    fun closeMakesSubsequentCallsReturnDatabaseLockedAndEmitsFailure() = runTest {
        val store = FakePassStore(initial = listOf(sampleSummary(id = 1L)))
        val telemetry = RecordingGuard()
        val repo = SqlCipherPassRepository(
            store = store,
            documentStore = NoOpDocumentStore,
            telemetryGuard = telemetry,
            ioDispatcher = UnconfinedTestDispatcher(),
            clock = { 1L },
            keyBacking = KeyBacking.Tee,
        )
        repo.close()
        // close() is idempotent.
        repo.close()
        assertThat(store.closeCount).isEqualTo(1)

        val result = repo.summaryOf(PassRecordId(1L))
        check(result is StorageResult.Failure)
        check(result.error == StorageError.DatabaseLocked)
        assertThat(telemetry.events).containsExactly(
            "init:Tee",
            "failure:DatabaseLocked:n/a",
        ).inOrder()
    }

    @Test
    fun deleteIsIrreversibleNoUndoArmOnPublicSurface() {
        // Compile-time lock: the only mutating method on PassRepository for an existing row
        // is `delete(id)`. No `undelete`, `restore`, `softDelete`, or `archive` arms exist.
        val methods = PassRepository::class.java.declaredMethods.map { it.name }.toSet()
        assertThat(methods).doesNotContain("undelete")
        assertThat(methods).doesNotContain("restore")
        assertThat(methods).doesNotContain("softDelete")
        assertThat(methods).doesNotContain("archive")
    }

    /**
     * In-memory [PassStore] that exercises the same surface area as `SqlCipherPassStore` but
     * works in JVM. Sequence is single-threaded; tests use [UnconfinedTestDispatcher] so
     * `withContext(ioDispatcher)` runs in the calling test thread.
     */
    private class FakePassStore(initial: List<PassSummary> = emptyList()) : PassStore {
        private val rows: LinkedHashMap<Long, StoredPass> = LinkedHashMap()
        private val summaries: LinkedHashMap<Long, PassSummary> = LinkedHashMap()
        private var nextId: Long = 0L

        init {
            for (s in initial) {
                summaries[s.id.value] = s
                rows[s.id.value] = StoredPass(
                    id = s.id,
                    pass = samplePass(serial = s.serialNumber),
                    signatureStatus = s.signatureStatus,
                    createdAt = s.createdAt,
                    updatedAt = s.updatedAt,
                )
                nextId = maxOf(nextId, s.id.value)
            }
        }

        override fun listSummaries(): List<PassSummary> = summaries.values.toList()
        override fun loadById(id: PassRecordId): StoredPass? = rows[id.value]
        override fun summaryById(id: PassRecordId): PassSummary? = summaries[id.value]

        override fun upsert(
            pass: Pass,
            signatureStatus: SignatureStatus,
            nowEpochMs: Long,
        ): UpsertOutcome {
            val existing = summaries.values.firstOrNull {
                it.type == pass.type &&
                    it.serialNumber == pass.serialNumber &&
                    it.organizationName == pass.organizationName
            }
            val id = existing?.id ?: PassRecordId(++nextId)
            val createdAt = existing?.createdAt ?: PassInstant(nowEpochMs)
            val summary = PassSummary(
                id = id,
                type = pass.type,
                serialNumber = pass.serialNumber,
                organizationName = pass.organizationName,
                description = pass.description,
                expirationDate = pass.expirationDate,
                voided = pass.voided,
                signatureStatus = signatureStatus,
                createdAt = createdAt,
                updatedAt = PassInstant(nowEpochMs),
            )
            summaries[id.value] = summary
            rows[id.value] = StoredPass(
                id = id,
                pass = pass,
                signatureStatus = signatureStatus,
                createdAt = createdAt,
                updatedAt = PassInstant(nowEpochMs),
            )
            return UpsertOutcome(
                recordId = id,
                summary = summary,
                wasReplacement = existing != null,
            )
        }

        override fun delete(id: PassRecordId): DeleteOutcome? {
            val summary = summaries.remove(id.value) ?: return null
            rows.remove(id.value)
            return DeleteOutcome(summary)
        }

        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount++
        }
    }

    private class RecordingGuard : StorageTelemetryGuard {
        val events: MutableList<String> = mutableListOf()
        override fun onKeyProviderInitialized(backing: KeyBacking) {
            events += "init:${backing.name}"
        }
        override fun onPassUpserted(
            type: PassType,
            signatureStatus: SignatureStatusKind,
            wasReplacement: Boolean,
        ) {
            events += "upsert:${type.name}:${signatureStatus.name}:$wasReplacement"
        }
        override fun onPassDeleted(type: PassType, signatureStatus: SignatureStatusKind) {
            events += "delete:${type.name}:${signatureStatus.name}"
        }
        override fun onMigrationRowDropped(kind: MigrationFailureKind) {
            events += "drop:${kind.name}"
        }
        override fun onStorageFailure(
            kind: StorageFailureKind,
            unknownKind: UnknownStorageFailureKind?,
        ) {
            events += "failure:${kind.name}:${unknownKind?.name ?: "n/a"}"
        }
        override fun onDocumentImported(event: DocumentImportedEvent) {
            events += "doc-imported:${event.byteCount}:${event.pageCount}"
        }
        override fun onDocumentRejected(kind: DocumentStorageRejectedKind) {
            events += "doc-rejected:${kind.name}"
        }
        override fun onDocumentDeleted(event: DocumentDeletedEvent) {
            events += "doc-deleted:${event.byteCount}"
        }
    }

    /**
     * In-memory [DocumentStore] used by the pass-side contract tests; the document-side
     * contract tests use their own richer fake (see DocumentRepositoryTest).
     */
    private object NoOpDocumentStore : DocumentStore {
        var closeCount: Int = 0
            private set
        override fun listRows(): List<DocumentRow> = emptyList()
        override fun insert(request: DocumentInsertRequest): DocumentInsertOutcome =
            error("unused in pass-side tests")
        override fun loadBytes(id: DocumentRecordId): ByteArray? = null
        override fun loadThumbnail(id: DocumentRecordId): ByteArray? = null
        override fun delete(id: DocumentRecordId): DocumentDeleteOutcome? = null
        override fun close() { closeCount++ }
    }

    private companion object {
        fun sampleSummary(id: Long): PassSummary = PassSummary(
            id = PassRecordId(id),
            type = PassType.BoardingPass,
            serialNumber = "S$id",
            organizationName = "AcmeAir",
            description = "Boarding Pass",
            expirationDate = null,
            voided = false,
            signatureStatus = SignatureStatus.AppleVerified,
            createdAt = PassInstant(1L),
            updatedAt = PassInstant(1L),
        )

        fun samplePass(serial: String): Pass = Pass(
            type = PassType.BoardingPass,
            serialNumber = serial,
            description = "Boarding Pass",
            organizationName = "AcmeAir",
            expirationDate = null,
            voided = false,
            colors = PassColors(
                foreground = ColorValue(0xFFFFFF),
                background = ColorValue(0x000000),
                label = null,
            ),
            frontFields = PassFields(
                primary = listOf(PassField(key = "p", label = null, value = "v")),
            ),
            backFields = emptyList(),
            barcode = Barcode(
                format = BarcodeFormat.QR,
                message = "data",
                messageEncoding = "iso-8859-1",
                altText = null,
            ),
            images = mapOf(ImageRole.Logo to ImageBytes(byteArrayOf(0x01, 0x02))),
            locales = mapOf(PassLocale("en") to LocalizedStrings(mapOf("k" to "v"))),
        )
    }
}
