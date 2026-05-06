package `is`.walt.passes.storage

import android.content.Context
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.core.toKind
import `is`.walt.passes.storage.internal.DocumentInsertRequest
import `is`.walt.passes.storage.internal.DocumentStore
import `is`.walt.passes.storage.internal.PassStore
import `is`.walt.passes.storage.internal.SqlCipherDatabaseFactory
import `is`.walt.passes.storage.internal.SqlCipherDocumentStore
import `is`.walt.passes.storage.internal.SqlCipherPassStore
import `is`.walt.passes.storage.internal.toFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production [PassRepository]. The StateFlow, delete sequencing, and telemetry sequencing
 * live here so they can be exercised against an in-memory fake [PassStore]. The constructor
 * is internal; [create] is the only construction path.
 */
public class SqlCipherPassRepository internal constructor(
    private val store: PassStore,
    private val documentStore: DocumentStore,
    private val telemetryGuard: StorageTelemetryGuard,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    keyBacking: KeyBacking,
) : PassRepository {

    private val writeMutex = Mutex()
    private val closed = AtomicBoolean(false)

    private val _passes: MutableStateFlow<List<PassSummary>> = MutableStateFlow(store.listSummaries())
    override val passes: StateFlow<List<PassSummary>> = _passes.asStateFlow()

    private val _documents: MutableStateFlow<List<DocumentRow>> =
        MutableStateFlow(documentStore.listRows())

    init {
        telemetryGuard.onKeyProviderInitialized(keyBacking)
    }

    override suspend fun upsert(
        pass: Pass,
        signatureStatus: SignatureStatus,
    ): StorageResult<PassRecordId> = runIo {
        val outcome = writeMutex.withLock {
            val o = store.upsert(pass, signatureStatus, clock())
            _passes.value = store.listSummaries()
            o
        }
        telemetryGuard.onPassUpserted(
            type = pass.type,
            signatureStatus = signatureStatus.toKind(),
            wasReplacement = outcome.wasReplacement,
        )
        StorageResult.Success(outcome.recordId)
    }

    override suspend fun load(id: PassRecordId): StorageResult<StoredPass> = runIo {
        val stored = store.loadById(id)
            ?: return@runIo failure(StorageError.IntegrityViolation(id))
        StorageResult.Success(stored)
    }

    override suspend fun summaryOf(id: PassRecordId): StorageResult<PassSummary> = runIo {
        val summary = store.summaryById(id)
            ?: return@runIo failure(StorageError.IntegrityViolation(id))
        StorageResult.Success(summary)
    }

    override suspend fun delete(id: PassRecordId): StorageResult<Unit> = runIo {
        val deleted = writeMutex.withLock {
            val outcome = store.delete(id) ?: return@withLock null
            _passes.value = _passes.value.filterNot { it.id == id }
            outcome
        } ?: return@runIo failure(StorageError.IntegrityViolation(id))

        telemetryGuard.onPassDeleted(
            type = deleted.summary.type,
            signatureStatus = deleted.summary.signatureStatus.toKind(),
        )
        StorageResult.Success(Unit)
    }

    override suspend fun insertDocument(
        label: String,
        pdfBytes: ByteArray,
        pageCount: Int,
        thumbnailBytes: ByteArray,
    ): StorageResult<DocumentRecordId> = runIo {
        // byteCount is derived, not caller-asserted, so a stale size header cannot
        // bypass the cap. The cost is a single property read.
        val byteCount = pdfBytes.size.toLong()

        rejectionKindOrNull(label, byteCount, pageCount)?.let { kind ->
            return@runIo rejectDocument(kind)
        }

        val outcome = writeMutex.withLock {
            val o = documentStore.insert(
                DocumentInsertRequest(
                    displayLabel = label,
                    pdfBytes = pdfBytes,
                    pageCount = pageCount,
                    thumbnailBytes = thumbnailBytes,
                    nowEpochMs = clock(),
                ),
            )
            // Documents are insert-only and ordered by `imported_at_epoch_ms DESC, id DESC`;
            // because [clock] is monotonic and the new id is the largest, prepending the
            // freshly-returned row matches the SQL ordering without re-issuing listRows().
            _documents.value = listOf(o.row) + _documents.value
            o
        }
        telemetryGuard.onDocumentImported(
            DocumentImportedEvent(byteCount = byteCount, pageCount = pageCount),
        )
        StorageResult.Success(outcome.id)
    }

    override fun observeDocuments(): Flow<List<DocumentRow>> = _documents.asStateFlow()

    override suspend fun loadDocumentBytes(id: DocumentRecordId): StorageResult<ByteArray> = runIo {
        val bytes = documentStore.loadBytes(id)
            ?: return@runIo failure(StorageError.IntegrityViolation(id))
        StorageResult.Success(bytes)
    }

    override suspend fun loadDocumentThumbnail(id: DocumentRecordId): StorageResult<ByteArray> = runIo {
        val bytes = documentStore.loadThumbnail(id)
            ?: return@runIo failure(StorageError.IntegrityViolation(id))
        StorageResult.Success(bytes)
    }

    override suspend fun deleteDocument(id: DocumentRecordId): StorageResult<Unit> = runIo {
        val deleted = writeMutex.withLock {
            val outcome = documentStore.delete(id) ?: return@withLock null
            _documents.value = _documents.value.filterNot { it.id == id }
            outcome
        } ?: return@runIo failure(StorageError.IntegrityViolation(id))

        telemetryGuard.onDocumentDeleted(DocumentDeletedEvent(byteCount = deleted.byteCount))
        StorageResult.Success(Unit)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            documentStore.close()
            store.close()
        }
    }

    /**
     * Returns the storage-side rejection kind for an insert, or null if all caps pass.
     * Order is byte count, then label length, then page count: the cheaper checks first
     * so a single oversized blob doesn't cost a string scan.
     */
    private fun rejectionKindOrNull(
        label: String,
        byteCount: Long,
        pageCount: Int,
    ): DocumentStorageRejectedKind? = when {
        byteCount > DocumentBounds.MAX_BYTES -> DocumentStorageRejectedKind.OversizedAtStorage
        pageCount > DocumentBounds.MAX_PAGES -> DocumentStorageRejectedKind.TooManyPagesAtStorage
        label.length > DocumentBounds.MAX_LABEL_CHARS ->
            DocumentStorageRejectedKind.LabelTooLongAtStorage
        else -> null
    }

    /**
     * Single emission point for a defensive document rejection. Emits
     * `onDocumentRejected(kind)` and returns the typed [StorageError.DocumentRejected]
     * arm without going through [failure]: a rejection is not a generic storage failure,
     * so [StorageTelemetryGuard.onStorageFailure] should NOT also fire. Routing through
     * [failure] would double-emit and obscure the distinction between "we said no" and
     * "the database errored."
     */
    private fun <T> rejectDocument(kind: DocumentStorageRejectedKind): StorageResult<T> {
        telemetryGuard.onDocumentRejected(kind)
        return StorageResult.Failure(StorageError.DocumentRejected(kind))
    }

    /**
     * Single emission point for `StorageResult.Failure`. Every failure-returning code path
     * routes through here so `onStorageFailure` fires exactly once per Failure with the
     * structural arm and (for [StorageError.Unknown]) the open-ended kind.
     */
    private fun <T> failure(error: StorageError): StorageResult<T> {
        telemetryGuard.onStorageFailure(
            kind = error.toFailureKind(),
            unknownKind = (error as? StorageError.Unknown)?.kind,
        )
        return StorageResult.Failure(error)
    }

    private suspend inline fun <T> runIo(crossinline block: suspend () -> StorageResult<T>): StorageResult<T> =
        withContext(ioDispatcher) {
            if (closed.get()) return@withContext failure(StorageError.DatabaseLocked)
            try {
                block()
            } catch (e: CancellationException) {
                // Honor structured cancellation; swallowing would silently break callers
                // that rely on it to abort an in-flight load/save.
                throw e
            } catch (e: Throwable) {
                failure(
                    StorageError.Unknown(
                        kind = UnknownStorageFailureKind.Other,
                        cause = e,
                    ),
                )
            }
        }

    public companion object {
        /**
         * Provisions SQLCipher with the `keyProvider`'s database key, runs [Schema.DDL] on
         * first open, and returns a [PassRepository] ready for use.
         */
        @JvmStatic
        public fun create(
            context: Context,
            keyProvider: PassKeyProvider,
            telemetryGuard: StorageTelemetryGuard,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            clock: () -> Long = { System.currentTimeMillis() },
        ): StorageResult<SqlCipherPassRepository> {
            val keyResult = keyProvider.provideDatabaseKey()
            val key = when (keyResult) {
                is StorageResult.Success -> keyResult.value
                is StorageResult.Failure -> {
                    telemetryGuard.onStorageFailure(
                        kind = keyResult.error.toFailureKind(),
                        unknownKind = (keyResult.error as? StorageError.Unknown)?.kind,
                    )
                    return StorageResult.Failure(keyResult.error)
                }
            }
            val openResult = try {
                SqlCipherDatabaseFactory.openOrCreate(context, key)
            } catch (e: Throwable) {
                val unknown = StorageError.Unknown(
                    kind = UnknownStorageFailureKind.DatabaseCorrupt,
                    cause = e,
                )
                telemetryGuard.onStorageFailure(
                    kind = unknown.toFailureKind(),
                    unknownKind = unknown.kind,
                )
                return StorageResult.Failure(unknown)
            }
            val db = when (openResult) {
                is StorageResult.Success -> openResult.value
                is StorageResult.Failure -> {
                    telemetryGuard.onStorageFailure(
                        kind = openResult.error.toFailureKind(),
                        unknownKind = (openResult.error as? StorageError.Unknown)?.kind,
                    )
                    return StorageResult.Failure(openResult.error)
                }
            }
            val store = SqlCipherPassStore(db, telemetryGuard)
            val documentStore = SqlCipherDocumentStore(db)
            val repo = SqlCipherPassRepository(
                store = store,
                documentStore = documentStore,
                telemetryGuard = telemetryGuard,
                ioDispatcher = ioDispatcher,
                clock = clock,
                keyBacking = keyProvider.keyBacking,
            )
            return StorageResult.Success(repo)
        }
    }
}
