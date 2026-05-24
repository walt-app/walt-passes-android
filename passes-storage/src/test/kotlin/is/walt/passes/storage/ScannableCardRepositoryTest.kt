package `is`.walt.passes.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.core.ScannableCardCreateInput
import `is`.walt.passes.core.ScannableCardId
import `is`.walt.passes.core.ScannableCardInputValidator
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.storage.internal.DeleteOutcome
import `is`.walt.passes.storage.internal.DocumentDeleteOutcome
import `is`.walt.passes.storage.internal.DocumentInsertOutcome
import `is`.walt.passes.storage.internal.DocumentInsertRequest
import `is`.walt.passes.storage.internal.DocumentStore
import `is`.walt.passes.storage.internal.PassStore
import `is`.walt.passes.storage.internal.ScannableCardDeleteOutcome
import `is`.walt.passes.storage.internal.ScannableCardInsertOutcome
import `is`.walt.passes.storage.internal.ScannableCardInsertRequest
import `is`.walt.passes.storage.internal.ScannableCardStore
import `is`.walt.passes.storage.internal.ScannableCardUpdateRequest
import `is`.walt.passes.storage.internal.UpsertOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric-backed verification of the scannable-card side of [PassRepository].
 * Exercises the create -> observe -> delete cycle plus the validator rejection paths
 * against an in-memory fake [ScannableCardStore]. The SQLCipher round-trip lives in
 * the instrumentation suite.
 *
 * Properties locked here:
 *
 *  1. A valid `createScannableCard` call inserts the row, the observe flow reflects
 *     it, and `onScannableCardCreated(format)` fires AFTER the flow updates.
 *  2. A validator-rejected payload (control character) surfaces as
 *     [StorageError.ScannableCardRejected] with the typed kernel reason, emits
 *     `onScannableCardRejected(PayloadInvalid)`, and the row never reaches disk.
 *  3. A validator-rejected label (empty after trim) surfaces as
 *     [StorageError.ScannableCardRejected] with the typed kernel reason and emits
 *     `onScannableCardRejected(LabelInvalid)`.
 *  4. A scannable-card validator rejection does NOT also emit
 *     `onStorageFailure(ScannableCardRejected,...)` — rejection and infra failure are
 *     distinct telemetry channels, mirroring the document-side discipline.
 *  5. `deleteScannableCard` removes the row from the observe flow and emits
 *     `onScannableCardDeleted(format)` afterward.
 *  6. `deleteScannableCard` of an unknown id returns [StorageError.IntegrityViolation]
 *     carrying a [ScannableCardRecordId] and emits `onStorageFailure` only.
 *  7. `loadScannableCard` round-trips the stored fields exactly (trimmed by the
 *     validator) and re-mints the [ScannableCardId] as the stringified row id.
 *  8. `close()` releases the scannable-card store along with the pass and document
 *     stores; the call is idempotent.
 *  9. `updateScannableCard` re-runs the validator before any write, re-emits the
 *     observe flow on success, emits no success telemetry, and shares the
 *     `onScannableCardRejected` channel with `createScannableCard` on rejection.
 * 10. `updateScannableCard` of an unknown id returns [StorageError.IntegrityViolation]
 *     when the input passed validation; an invalid input against an unknown id
 *     surfaces as [StorageError.ScannableCardRejected] (validation precedes the row
 *     lookup, mirroring `updateDocumentLabel`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScannableCardRepositoryTest {

    @Test
    fun createInsertsRowUpdatesObserveFlowAndEmitsCreatedTelemetryAfter() = runTest {
        val cards = FakeScannableCardStore()
        val telemetry = RecordingGuard()
        val repo = repo(cards, telemetry)

        val result = repo.createScannableCard(
            ScannableCardCreateInput(
                payload = "5012345678900", // valid EAN-13 with check digit
                format = ScannableFormat.Ean13,
                label = "Grocery loyalty",
            ),
        )

        check(result is StorageResult.Success)
        val rows = repo.observeScannableCards().first()
        assertThat(rows).hasSize(1)
        assertThat(rows[0].payload).isEqualTo("5012345678900")
        assertThat(rows[0].format).isEqualTo(ScannableFormat.Ean13)
        assertThat(rows[0].label).isEqualTo("Grocery loyalty")
        assertThat(telemetry.events).containsExactly(
            "init:Tee",
            "card-created:Ean13",
        ).inOrder()
    }

    @Test
    fun createWithControlCharInPayloadIsRejectedWithTypedReason() = runTest {
        val cards = FakeScannableCardStore()
        val telemetry = RecordingGuard()
        val repo = repo(cards, telemetry)

        val result = repo.createScannableCard(
            ScannableCardCreateInput(
                payload = "abcdef", // bell character — Cc category
                format = ScannableFormat.Code128,
                label = "Test",
            ),
        )

        check(result is StorageResult.Failure)
        val rejected = result.error as StorageError.ScannableCardRejected
        check(rejected.reason is ScannableCardRejectionReason.InvalidPayload)
        // The rejection emits exactly `card-rejected`; it does NOT also emit
        // `failure:ScannableCardRejected:...`. A validator rejection is not a generic
        // storage failure (mirrors the doc-side rejectDocument discipline).
        assertThat(telemetry.events).containsExactly(
            "init:Tee",
            "card-rejected:PayloadInvalid",
        ).inOrder()
        assertThat(repo.observeScannableCards().first()).isEmpty()
    }

    @Test
    fun createWithEmptyLabelIsRejectedWithTypedReason() = runTest {
        val cards = FakeScannableCardStore()
        val telemetry = RecordingGuard()
        val repo = repo(cards, telemetry)

        val result = repo.createScannableCard(
            ScannableCardCreateInput(
                payload = "5012345678900",
                format = ScannableFormat.Ean13,
                label = "   ", // whitespace-only, trims to empty
            ),
        )

        check(result is StorageResult.Failure)
        val rejected = result.error as StorageError.ScannableCardRejected
        check(rejected.reason is ScannableCardRejectionReason.InvalidLabel)
        assertThat(telemetry.events).containsExactly(
            "init:Tee",
            "card-rejected:LabelInvalid",
        ).inOrder()
        assertThat(repo.observeScannableCards().first()).isEmpty()
    }

    @Test
    fun deleteRemovesRowFromObserveFlowAndEmitsDeletedTelemetryAfter() = runTest {
        val cards = FakeScannableCardStore()
        val telemetry = RecordingGuard()
        val repo = repo(cards, telemetry)

        val create = repo.createScannableCard(
            ScannableCardCreateInput(
                payload = "HELLO WORLD",
                format = ScannableFormat.Code128,
                label = "x",
            ),
        )
        check(create is StorageResult.Success)
        val id = create.value

        val deleteResult = repo.deleteScannableCard(id)
        check(deleteResult is StorageResult.Success)

        assertThat(repo.observeScannableCards().first()).isEmpty()
        assertThat(telemetry.events).containsExactly(
            "init:Tee",
            "card-created:Code128",
            "card-deleted:Code128",
        ).inOrder()
    }

    @Test
    fun deleteOfUnknownIdReturnsIntegrityViolationAndEmitsFailureNotDeleted() = runTest {
        val cards = FakeScannableCardStore()
        val telemetry = RecordingGuard()
        val repo = repo(cards, telemetry)

        val result = repo.deleteScannableCard(ScannableCardRecordId(404L))

        check(result is StorageResult.Failure)
        val violation = result.error as StorageError.IntegrityViolation
        check(violation.recordId is ScannableCardRecordId)
        assertThat(violation.recordId.value).isEqualTo(404L)
        assertThat(telemetry.events).containsExactly(
            "init:Tee",
            "failure:IntegrityViolation:n/a",
        ).inOrder()
    }

    @Test
    fun updateReplacesFieldsPreservesRowPositionAndEmitsNoSuccessTelemetry() = runTest {
        val cards = FakeScannableCardStore()
        val telemetry = RecordingGuard()
        val repo = repo(cards, telemetry)

        val seed = repo.createScannableCard(
            ScannableCardCreateInput(
                payload = "5012345678900",
                format = ScannableFormat.Ean13,
                label = "old",
            ),
        )
        check(seed is StorageResult.Success)
        val id = seed.value

        val result = repo.updateScannableCard(
            id,
            ScannableCardCreateInput(
                payload = "HELLO WORLD",
                format = ScannableFormat.Code128,
                label = "new",
            ),
        )

        check(result is StorageResult.Success)
        val rows = repo.observeScannableCards().first()
        assertThat(rows).hasSize(1)
        assertThat(rows[0].id.value).isEqualTo(id.value.toString())
        assertThat(rows[0].payload).isEqualTo("HELLO WORLD")
        assertThat(rows[0].format).isEqualTo(ScannableFormat.Code128)
        assertThat(rows[0].label).isEqualTo("new")
        // Update emits no telemetry: nothing follows the original card-created event.
        assertThat(telemetry.events).containsExactly(
            "init:Tee",
            "card-created:Ean13",
        ).inOrder()
    }

    @Test
    fun updateOfUnknownIdWithValidInputReturnsIntegrityViolation() = runTest {
        val telemetry = RecordingGuard()
        val repo = repo(FakeScannableCardStore(), telemetry)

        val result = repo.updateScannableCard(
            ScannableCardRecordId(404L),
            ScannableCardCreateInput(
                payload = "5012345678900",
                format = ScannableFormat.Ean13,
                label = "Grocery",
            ),
        )

        check(result is StorageResult.Failure)
        val violation = result.error as StorageError.IntegrityViolation
        check(violation.recordId is ScannableCardRecordId)
        assertThat(violation.recordId.value).isEqualTo(404L)
        assertThat(telemetry.events).containsExactly(
            "init:Tee",
            "failure:IntegrityViolation:n/a",
        ).inOrder()
    }

    @Test
    fun updateWithControlCharInPayloadIsRejectedAndStoredRowIsUnchanged() = runTest {
        val cards = FakeScannableCardStore()
        val telemetry = RecordingGuard()
        val repo = repo(cards, telemetry)

        val seed = repo.createScannableCard(
            ScannableCardCreateInput(
                payload = "5012345678900",
                format = ScannableFormat.Ean13,
                label = "seed",
            ),
        )
        check(seed is StorageResult.Success)

        val result = repo.updateScannableCard(
            seed.value,
            ScannableCardCreateInput(
                payload = "abcdef", // bell character — Cc category
                format = ScannableFormat.Code128,
                label = "new",
            ),
        )

        check(result is StorageResult.Failure)
        val rejected = result.error as StorageError.ScannableCardRejected
        check(rejected.reason is ScannableCardRejectionReason.InvalidPayload)
        // Same telemetry channel as createScannableCard rejections; no `failure:...`.
        assertThat(telemetry.events).containsExactly(
            "init:Tee",
            "card-created:Ean13",
            "card-rejected:PayloadInvalid",
        ).inOrder()
        // Stored row was not overwritten by the rejected update.
        val rows = repo.observeScannableCards().first()
        assertThat(rows).hasSize(1)
        assertThat(rows[0].payload).isEqualTo("5012345678900")
        assertThat(rows[0].label).isEqualTo("seed")
    }

    @Test
    fun updateWithEmptyLabelIsRejectedWithTypedReason() = runTest {
        val cards = FakeScannableCardStore()
        val telemetry = RecordingGuard()
        val repo = repo(cards, telemetry)

        val seed = repo.createScannableCard(
            ScannableCardCreateInput(
                payload = "5012345678900",
                format = ScannableFormat.Ean13,
                label = "seed",
            ),
        )
        check(seed is StorageResult.Success)

        val result = repo.updateScannableCard(
            seed.value,
            ScannableCardCreateInput(
                payload = "5012345678900",
                format = ScannableFormat.Ean13,
                label = "   ", // whitespace-only, trims to empty
            ),
        )

        check(result is StorageResult.Failure)
        val rejected = result.error as StorageError.ScannableCardRejected
        check(rejected.reason is ScannableCardRejectionReason.InvalidLabel)
        assertThat(telemetry.events).containsExactly(
            "init:Tee",
            "card-created:Ean13",
            "card-rejected:LabelInvalid",
        ).inOrder()
    }

    @Test
    fun updateOfUnknownIdWithInvalidInputPrefersRejectionOverIntegrityViolation() = runTest {
        // Validation runs before the row is loaded; an invalid input against an unknown
        // id surfaces as ScannableCardRejected, mirroring updateDocumentLabel's
        // label-cap-before-load precedence.
        val telemetry = RecordingGuard()
        val repo = repo(FakeScannableCardStore(), telemetry)

        val result = repo.updateScannableCard(
            ScannableCardRecordId(999L),
            ScannableCardCreateInput(
                payload = "abcdef", // bell character — Cc category
                format = ScannableFormat.Code128,
                label = "x",
            ),
        )

        check(result is StorageResult.Failure)
        val rejected = result.error as StorageError.ScannableCardRejected
        check(rejected.reason is ScannableCardRejectionReason.InvalidPayload)
        assertThat(telemetry.events).containsExactly(
            "init:Tee",
            "card-rejected:PayloadInvalid",
        ).inOrder()
    }

    @Test
    fun loadRoundTripsStoredFields() = runTest {
        val cards = FakeScannableCardStore()
        val repo = repo(cards, RecordingGuard())

        val create = repo.createScannableCard(
            ScannableCardCreateInput(
                payload = "5012345678900",
                format = ScannableFormat.Ean13,
                label = "Grocery",
            ),
        )
        check(create is StorageResult.Success)
        val id = create.value

        val loaded = repo.loadScannableCard(id)
        check(loaded is StorageResult.Success)
        val card = loaded.value
        assertThat(card.payload).isEqualTo("5012345678900")
        assertThat(card.format).isEqualTo(ScannableFormat.Ean13)
        assertThat(card.label).isEqualTo("Grocery")
        // The kernel-visible id is the stringified row id minted by storage.
        assertThat(card.id.value).isEqualTo(id.value.toString())
    }

    @Test
    fun loadOfUnknownIdReturnsIntegrityViolation() = runTest {
        val repo = repo(FakeScannableCardStore(), RecordingGuard())
        val result = repo.loadScannableCard(ScannableCardRecordId(999L))
        check(result is StorageResult.Failure)
        val violation = result.error as StorageError.IntegrityViolation
        check(violation.recordId is ScannableCardRecordId)
    }

    @Test
    fun closeReleasesScannableCardStoreAlongWithPassAndDocumentStores() = runTest {
        val cards = FakeScannableCardStore()
        val repo = repo(cards, RecordingGuard())
        repo.close()
        repo.close() // idempotent
        assertThat(cards.closeCount).isEqualTo(1)
    }

    private fun repo(cards: FakeScannableCardStore, telemetry: StorageTelemetryGuard): SqlCipherPassRepository =
        SqlCipherPassRepository(
            store = NoOpPassStore,
            documentStore = NoOpDocumentStore,
            scannableCardStore = cards,
            telemetryGuard = telemetry,
            ioDispatcher = UnconfinedTestDispatcher(),
            clock = { 1_000L },
            keyBacking = KeyBacking.Tee,
        )

    /**
     * In-memory scannable-card store. Mirrors the SQL contract: rows sorted by
     * `created_at_epoch_ms DESC, id DESC`. The kernel validator runs at insert time
     * (orchestrated by the repository), so this fake takes pre-validated inputs and
     * round-trips them through the same `ScannableCardInputValidator` path the
     * production SqlCipher impl uses on load, to keep the trimmed-label / trimmed-payload
     * invariant honest in tests.
     */
    private class FakeScannableCardStore : ScannableCardStore {
        private data class Row(
            val id: Long,
            val payload: String,
            val format: ScannableFormat,
            val label: String,
            val createdAtEpochMs: Long,
        )

        private val rows: LinkedHashMap<Long, Row> = LinkedHashMap()
        private var nextId: Long = 0L
        var closeCount: Int = 0
            private set

        override fun listAll(): List<ScannableCard> =
            rows.values
                .sortedWith(
                    compareByDescending<Row> { it.createdAtEpochMs }.thenByDescending { it.id },
                )
                .mapNotNull(::toCardOrNull)

        override fun loadById(id: ScannableCardRecordId): ScannableCard? =
            rows[id.value]?.let(::toCardOrNull)

        override fun insert(request: ScannableCardInsertRequest): ScannableCardInsertOutcome {
            val rowId = ++nextId
            rows[rowId] = Row(
                id = rowId,
                payload = request.payload,
                format = request.format,
                label = request.label,
                createdAtEpochMs = request.nowEpochMs,
            )
            return ScannableCardInsertOutcome(id = ScannableCardRecordId(rowId))
        }

        override fun update(id: ScannableCardRecordId, request: ScannableCardUpdateRequest): Boolean {
            val row = rows[id.value] ?: return false
            // createdAtEpochMs is preserved so the row's position in listAll() is unchanged.
            rows[id.value] = row.copy(
                payload = request.payload,
                format = request.format,
                label = request.label,
            )
            return true
        }

        override fun delete(id: ScannableCardRecordId): ScannableCardDeleteOutcome? {
            val row = rows.remove(id.value) ?: return null
            return ScannableCardDeleteOutcome(format = row.format)
        }

        override fun close() { closeCount++ }

        private fun toCardOrNull(row: Row): ScannableCard? {
            val result = ScannableCardInputValidator.validate(
                input = ScannableCardCreateInput(
                    payload = row.payload,
                    format = row.format,
                    label = row.label,
                ),
                id = ScannableCardId(row.id.toString()),
                createdAt = PassInstant(row.createdAtEpochMs),
            )
            return (result as? `is`.walt.passes.core.ScannableCardCreateResult.Success)?.card
        }
    }

    private object NoOpPassStore : PassStore {
        override fun listSummaries(): List<PassSummary> = emptyList()
        override fun loadById(id: PassRecordId): StoredPass? = null
        override fun summaryById(id: PassRecordId): PassSummary? = null
        override fun upsert(
            pass: Pass,
            signatureStatus: SignatureStatus,
            nowEpochMs: Long,
        ): UpsertOutcome = error("unused in scannable-card tests")
        override fun delete(id: PassRecordId): DeleteOutcome? = null
        override fun updateUserLabel(
            id: PassRecordId,
            label: String?,
        ): `is`.walt.passes.storage.internal.UpdateUserLabelOutcome? = null
        override fun close() = Unit
    }

    private object NoOpDocumentStore : DocumentStore {
        override fun listRows(): List<DocumentRow> = emptyList()
        override fun insert(request: DocumentInsertRequest): DocumentInsertOutcome =
            error("unused in scannable-card tests")
        override fun loadBytes(id: DocumentRecordId): ByteArray? = null
        override fun loadThumbnail(id: DocumentRecordId): ByteArray? = null
        override fun updateLabel(id: DocumentRecordId, label: String): Boolean = false
        override fun delete(id: DocumentRecordId): DocumentDeleteOutcome? = null
        override fun close() = Unit
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
        ) = Unit
        override fun onPassDeleted(type: PassType, signatureStatus: SignatureStatusKind) = Unit
        override fun onMigrationRowDropped(kind: MigrationFailureKind) = Unit
        override fun onStorageFailure(
            kind: StorageFailureKind,
            unknownKind: UnknownStorageFailureKind?,
        ) {
            events += "failure:${kind.name}:${unknownKind?.name ?: "n/a"}"
        }
        override fun onDocumentImported(event: DocumentImportedEvent) = Unit
        override fun onDocumentRejected(kind: DocumentStorageRejectedKind) = Unit
        override fun onDocumentDeleted(event: DocumentDeletedEvent) = Unit
        override fun onScannableCardCreated(format: ScannableFormat) {
            events += "card-created:${format.name}"
        }
        override fun onScannableCardDeleted(format: ScannableFormat) {
            events += "card-deleted:${format.name}"
        }
        override fun onScannableCardRejected(kind: ScannableCardRejectedKind) {
            events += "card-rejected:${kind.name}"
        }
        override fun onUserLabelUpdated(
            type: PassType,
            hadPriorLabel: Boolean,
            clearing: Boolean,
        ) = Unit
        override fun onPassRejected(kind: PassUpdateRejectedKind) = Unit
    }
}
