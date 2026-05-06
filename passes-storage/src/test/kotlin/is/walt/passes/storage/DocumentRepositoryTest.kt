package `is`.walt.passes.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.storage.internal.DeleteOutcome
import `is`.walt.passes.storage.internal.DocumentDeleteOutcome
import `is`.walt.passes.storage.internal.DocumentInsertOutcome
import `is`.walt.passes.storage.internal.DocumentInsertRequest
import `is`.walt.passes.storage.internal.DocumentStore
import `is`.walt.passes.storage.internal.PassStore
import `is`.walt.passes.storage.internal.UpsertOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric-backed verification of the document side of [PassRepository]. Exercises the
 * insert -> observe -> delete cycle plus the storage-side defense-in-depth bounds against
 * an in-memory fake [DocumentStore]. The SQLCipher round-trip lives in the instrumentation
 * suite (the wpass-x5l-style on-device tests).
 *
 * Properties locked here:
 *
 *  1. After `insertDocument`, `observeDocuments()` reflects the new row and
 *     `onDocumentImported(byteCount, pageCount)` is emitted afterward.
 *  2. After `deleteDocument`, the flow no longer carries that id and the store's CASCADE
 *     fired (the fake mirrors the SQL ON DELETE CASCADE behavior on the thumbnail row).
 *  3. `byteCount > MAX_BYTES` is rejected with `OversizedAtStorage` and the row never
 *     reaches the store.
 *  4. `pageCount > MAX_PAGES` is rejected with `TooManyPagesAtStorage` and the row never
 *     reaches the store.
 *  5. Exactly the documented bounds (25 MB, 10 pages) are accepted, not their
 *     +1-byte / +1-page neighbors.
 *  6. `loadDocumentBytes` and `loadDocumentThumbnail` round-trip the stored bytes
 *     unchanged: storage never decodes them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DocumentRepositoryTest {

    @Test
    fun insertUpdatesObserveFlowAndEmitsImportedTelemetryAfter() = runTest {
        val docs = FakeDocumentStore()
        val telemetry = RecordingGuard()
        val repo = repo(docs, telemetry)

        val pdf = ByteArray(1024) { 0x25 }
        val thumb = ByteArray(64) { 0x10 }

        val result = repo.insertDocument(
            label = "boarding-pass.pdf",
            pdfBytes = pdf,
            byteCount = pdf.size.toLong(),
            pageCount = 2,
            thumbnailBytes = thumb,
        )

        check(result is StorageResult.Success)
        val rows = repo.observeDocuments().first()
        assertThat(rows).hasSize(1)
        assertThat(rows[0].displayLabel).isEqualTo("boarding-pass.pdf")
        assertThat(rows[0].byteCount).isEqualTo(1024)
        assertThat(rows[0].pageCount).isEqualTo(2)
        assertThat(telemetry.events).containsExactly(
            "init:Tee",
            "doc-imported:1024:2",
        ).inOrder()
    }

    @Test
    fun deleteRemovesFromObserveFlowAndEmitsDeletedTelemetryAfter() = runTest {
        val docs = FakeDocumentStore()
        val telemetry = RecordingGuard()
        val repo = repo(docs, telemetry)

        val insert = repo.insertDocument(
            label = "x.pdf",
            pdfBytes = ByteArray(2048),
            byteCount = 2048L,
            pageCount = 1,
            thumbnailBytes = ByteArray(32),
        )
        check(insert is StorageResult.Success)
        val id = insert.value

        val deleteResult = repo.deleteDocument(id)
        check(deleteResult is StorageResult.Success)

        assertThat(repo.observeDocuments().first()).isEmpty()
        // CASCADE: the thumbnail row was dropped alongside the document row.
        assertThat(docs.thumbnailExists(id)).isFalse()
        assertThat(telemetry.events).containsExactly(
            "init:Tee",
            "doc-imported:2048:1",
            "doc-deleted:2048",
        ).inOrder()
    }

    @Test
    fun deleteOfUnknownIdReturnsIntegrityViolationAndEmitsFailureNotDeleted() = runTest {
        val docs = FakeDocumentStore()
        val telemetry = RecordingGuard()
        val repo = repo(docs, telemetry)

        val result = repo.deleteDocument(404L)

        check(result is StorageResult.Failure)
        check(result.error is StorageError.IntegrityViolation)
        assertThat(telemetry.events).containsExactly(
            "init:Tee",
            "failure:IntegrityViolation:n/a",
        ).inOrder()
    }

    @Test
    fun maxBytesExactBoundaryIsAcceptedAndPlusOneIsRejected() = runTest {
        val docs = FakeDocumentStore()
        val telemetry = RecordingGuard()
        val repo = repo(docs, telemetry)

        // 25 MB exact passes.
        val ok = repo.insertDocument(
            label = "a",
            pdfBytes = ByteArray(0),
            byteCount = DocumentBounds.MAX_BYTES,
            pageCount = 1,
            thumbnailBytes = ByteArray(0),
        )
        check(ok is StorageResult.Success)

        // 25 MB + 1 byte rejected. The PDF bytes themselves are not allocated; only the
        // byteCount header check matters here.
        val rejected = repo.insertDocument(
            label = "b",
            pdfBytes = ByteArray(0),
            byteCount = DocumentBounds.MAX_BYTES + 1,
            pageCount = 1,
            thumbnailBytes = ByteArray(0),
        )
        check(rejected is StorageResult.Failure)
        check(rejected.error is StorageError.Unknown)

        assertThat(telemetry.events).contains("doc-rejected:OversizedAtStorage")
        // Only the accepted row was persisted.
        assertThat(repo.observeDocuments().first()).hasSize(1)
    }

    @Test
    fun maxPagesExactBoundaryIsAcceptedAndPlusOneIsRejected() = runTest {
        val docs = FakeDocumentStore()
        val telemetry = RecordingGuard()
        val repo = repo(docs, telemetry)

        // 10 pages exact passes.
        val ok = repo.insertDocument(
            label = "a",
            pdfBytes = ByteArray(0),
            byteCount = 1L,
            pageCount = DocumentBounds.MAX_PAGES,
            thumbnailBytes = ByteArray(0),
        )
        check(ok is StorageResult.Success)

        // 11 pages rejected.
        val rejected = repo.insertDocument(
            label = "b",
            pdfBytes = ByteArray(0),
            byteCount = 1L,
            pageCount = DocumentBounds.MAX_PAGES + 1,
            thumbnailBytes = ByteArray(0),
        )
        check(rejected is StorageResult.Failure)
        check(rejected.error is StorageError.Unknown)

        assertThat(telemetry.events).contains("doc-rejected:TooManyPagesAtStorage")
        assertThat(repo.observeDocuments().first()).hasSize(1)
    }

    @Test
    fun loadDocumentBytesAndThumbnailRoundTripUntouched() = runTest {
        val docs = FakeDocumentStore()
        val telemetry = RecordingGuard()
        val repo = repo(docs, telemetry)

        val pdf = ByteArray(256) { (it * 7).toByte() }
        val thumb = ByteArray(128) { (it * 11).toByte() }
        val insert = repo.insertDocument(
            label = "foo.pdf",
            pdfBytes = pdf,
            byteCount = pdf.size.toLong(),
            pageCount = 1,
            thumbnailBytes = thumb,
        )
        check(insert is StorageResult.Success)
        val id = insert.value

        val loaded = repo.loadDocumentBytes(id)
        check(loaded is StorageResult.Success)
        assertThat(loaded.value).isEqualTo(pdf)

        val loadedThumb = repo.loadDocumentThumbnail(id)
        check(loadedThumb is StorageResult.Success)
        assertThat(loadedThumb.value).isEqualTo(thumb)
    }

    @Test
    fun loadOfUnknownIdReturnsIntegrityViolation() = runTest {
        val repo = repo(FakeDocumentStore(), RecordingGuard())
        val result = repo.loadDocumentBytes(999L)
        check(result is StorageResult.Failure)
        check(result.error is StorageError.IntegrityViolation)
    }

    private fun repo(docs: FakeDocumentStore, telemetry: StorageTelemetryGuard): SqlCipherPassRepository =
        SqlCipherPassRepository(
            store = NoOpPassStore,
            documentStore = docs,
            telemetryGuard = telemetry,
            ioDispatcher = UnconfinedTestDispatcher(),
            clock = { 1_000L },
            keyBacking = KeyBacking.Tee,
        )

    /**
     * In-memory document store. Mirrors the SQL contract: rows are sorted by
     * `imported_at_epoch_ms DESC, id DESC`, and `delete` cascades through to the
     * thumbnail entry (the production schema's ON DELETE CASCADE fires for the same
     * effect).
     */
    private class FakeDocumentStore : DocumentStore {
        private data class Entry(
            val row: DocumentRow,
            val pdfBytes: ByteArray,
            val thumbnailBytes: ByteArray,
        )

        private val entries: LinkedHashMap<Long, Entry> = LinkedHashMap()
        private var nextId: Long = 0L

        fun thumbnailExists(id: Long): Boolean = entries.containsKey(id)

        override fun listRows(): List<DocumentRow> =
            entries.values.map { it.row }
                .sortedWith(
                    compareByDescending<DocumentRow> { it.importedAtEpochMs }
                        .thenByDescending { it.id },
                )

        override fun insert(request: DocumentInsertRequest): DocumentInsertOutcome {
            val id = ++nextId
            val row = DocumentRow(
                id = id,
                displayLabel = request.displayLabel,
                byteCount = request.byteCount,
                pageCount = request.pageCount,
                importedAtEpochMs = request.nowEpochMs,
            )
            entries[id] = Entry(row, request.pdfBytes.copyOf(), request.thumbnailBytes.copyOf())
            return DocumentInsertOutcome(id = id, row = row)
        }

        override fun loadBytes(id: Long): ByteArray? = entries[id]?.pdfBytes?.copyOf()

        override fun loadThumbnail(id: Long): ByteArray? = entries[id]?.thumbnailBytes?.copyOf()

        override fun delete(id: Long): DocumentDeleteOutcome? {
            val entry = entries.remove(id) ?: return null
            return DocumentDeleteOutcome(byteCount = entry.row.byteCount)
        }
    }

    /**
     * Pass-side store stub: the document tests do not exercise pass-side code paths.
     */
    private object NoOpPassStore : PassStore {
        override fun listSummaries(): List<PassSummary> = emptyList()
        override fun loadById(id: PassRecordId): StoredPass? = null
        override fun summaryById(id: PassRecordId): PassSummary? = null
        override fun upsert(
            pass: Pass,
            signatureStatus: SignatureStatus,
            nowEpochMs: Long,
        ): UpsertOutcome = error("unused in document tests")
        override fun delete(id: PassRecordId): DeleteOutcome? = null
        override fun close() = Unit
    }

    private class RecordingGuard : StorageTelemetryGuard {
        val events: MutableList<String> = mutableListOf()
        override fun onKeyProviderInitialized(backing: KeyBacking) {
            events += "init:${backing.name}"
        }
        override fun onPassUpserted(
            type: `is`.walt.passes.core.PassType,
            signatureStatus: SignatureStatusKind,
            wasReplacement: Boolean,
        ) = Unit
        override fun onPassDeleted(
            type: `is`.walt.passes.core.PassType,
            signatureStatus: SignatureStatusKind,
        ) = Unit
        override fun onMigrationRowDropped(kind: MigrationFailureKind) = Unit
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
}
