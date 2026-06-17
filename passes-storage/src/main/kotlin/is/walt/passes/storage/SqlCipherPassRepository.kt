package `is`.walt.passes.storage

import android.content.Context
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.core.ScannableCardCreateInput
import `is`.walt.passes.core.ScannableCardCreateResult
import `is`.walt.passes.core.ScannableCardId
import `is`.walt.passes.core.ScannableCardInputValidator
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.core.toKind
import `is`.walt.passes.storage.internal.DocumentInsertRequest
import `is`.walt.passes.storage.internal.DocumentStore
import `is`.walt.passes.storage.internal.PassStore
import `is`.walt.passes.storage.internal.ScannableCardInsertRequest
import `is`.walt.passes.storage.internal.ScannableCardStore
import `is`.walt.passes.storage.internal.ScannableCardUpdateRequest
import `is`.walt.passes.storage.internal.SqlCipherDatabaseFactory
import `is`.walt.passes.storage.internal.SqlCipherDocumentStore
import `is`.walt.passes.storage.internal.SqlCipherPassStore
import `is`.walt.passes.storage.internal.SqlCipherScannableCardStore
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
// LongParameterList: the constructor carries one store per persistence tier (pass,
// document, scannable-card) plus the cross-cutting telemetry / dispatcher / clock /
// key-backing seams; collapsing them into a holder type would obscure the fan-out the
// `create()` factory has to wire up.
// TooManyFunctions: scales linearly with the number of tier-specific surface methods on
// PassRepository; the class is one coherent SQLCipher entry point per the repo contract
// (see PassRepository KDoc), and splitting it would scatter the shared write-mutex and
// closed-flag invariants.
@Suppress("LongParameterList", "TooManyFunctions")
public class SqlCipherPassRepository internal constructor(
    private val store: PassStore,
    private val documentStore: DocumentStore,
    private val scannableCardStore: ScannableCardStore,
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

    private val _scannableCards: MutableStateFlow<List<ScannableCard>> =
        MutableStateFlow(scannableCardStore.listAll())

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

    override suspend fun updatePassUserLabel(
        id: PassRecordId,
        label: String?,
    ): StorageResult<Unit> = runIo {
        // Normalize: trim, then collapse blank-after-trim to null (ADR 0007 D2). The
        // trimmed value is what the cap is measured against and what reaches the store.
        val trimmed = label?.trim()
        val normalized = if (trimmed.isNullOrEmpty()) null else trimmed
        val clearing = normalized == null

        if (normalized != null && normalized.length > PassUserLabelBounds.MAX_USER_LABEL_CHARS) {
            return@runIo rejectPass(PassUpdateRejectedKind.LabelTooLong)
        }

        val outcome = writeMutex.withLock {
            val o = store.updateUserLabel(id, normalized) ?: return@withLock null
            // Refresh the full ordered list rather than splicing: matches the other
            // mutating paths and avoids assuming the in-memory list and the store agree
            // on row presence (e.g. if the row was deleted in another writer between
            // the prior read and this write).
            _passes.value = store.listSummaries()
            o
        } ?: return@runIo failure(StorageError.IntegrityViolation(id))

        telemetryGuard.onUserLabelUpdated(
            type = outcome.summary.type,
            hadPriorLabel = outcome.hadPriorLabel,
            clearing = clearing,
        )
        StorageResult.Success(Unit)
    }

    override suspend fun insertDocument(
        insert: DocumentInsert,
    ): StorageResult<DocumentRecordId> = runIo {
        // byteCount is derived, not caller-asserted, so a stale size header cannot
        // bypass the cap. The cost is a single property read.
        val byteCount = insert.bytes.size.toLong()
        // page_count is the PDF page count; an image is a single page (1). The page cap
        // applies only to the PDF arm — images cannot exceed it by construction.
        val pageCount = when (insert) {
            is DocumentInsert.Pdf -> insert.pageCount
            is DocumentInsert.Image, is DocumentInsert.BarcodedImage -> IMAGE_PAGE_COUNT
        }
        val (format, widthPx, heightPx) = when (insert) {
            is DocumentInsert.Pdf -> Triple(DocumentFormat.Pdf, null, null)
            is DocumentInsert.Image -> Triple(insert.format, insert.widthPx, insert.heightPx)
            is DocumentInsert.BarcodedImage -> Triple(insert.format, insert.widthPx, insert.heightPx)
        }
        // Non-null only for the composite arm; a plain image / PDF persists NULL barcode columns.
        val barcodePayload = (insert as? DocumentInsert.BarcodedImage)?.barcodePayload
        val barcodeFormat = (insert as? DocumentInsert.BarcodedImage)?.barcodeFormat

        rejectionKindOrNull(insert, byteCount)?.let { kind ->
            return@runIo rejectDocument(kind)
        }

        val outcome = writeMutex.withLock {
            val o = documentStore.insert(
                DocumentInsertRequest(
                    displayLabel = insert.label,
                    bytes = insert.bytes,
                    format = format,
                    pageCount = pageCount,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    thumbnailBytes = insert.thumbnailBytes,
                    nowEpochMs = clock(),
                    barcodePayload = barcodePayload,
                    barcodeFormat = barcodeFormat,
                ),
            )
            // Re-read the full ordered list rather than prepending in-memory: matches the
            // pass-side `_passes.value = store.listSummaries()` pattern and avoids an
            // unwritten "clock must be monotonic" invariant. The cost is one SELECT per
            // insert, which is negligible for a non-hot path.
            _documents.value = documentStore.listRows()
            o
        }
        telemetryGuard.onDocumentImported(
            DocumentImportedEvent(byteCount = byteCount, pageCount = pageCount),
        )
        StorageResult.Success(outcome.id)
    }

    override suspend fun updateDocumentLabel(
        id: DocumentRecordId,
        label: String,
    ): StorageResult<Unit> = runIo {
        if (label.length > DocumentBounds.MAX_LABEL_CHARS) {
            return@runIo rejectDocument(DocumentStorageRejectedKind.LabelTooLongAtStorage)
        }
        val matched = writeMutex.withLock {
            val ok = documentStore.updateLabel(id, label)
            if (ok) _documents.value = documentStore.listRows()
            ok
        }
        if (!matched) return@runIo failure(StorageError.IntegrityViolation(id))
        StorageResult.Success(Unit)
    }

    override fun observeDocuments(): Flow<List<DocumentRow>> = _documents.asStateFlow()

    override suspend fun createScannableCard(
        input: ScannableCardCreateInput,
    ): StorageResult<ScannableCardRecordId> = runIo {
        // Validator does not use the id for validation; storage mints the real one
        // post-insert and listAll() rehydrates the StateFlow with the stringified row id.
        val validation = ScannableCardInputValidator.validate(
            input = input,
            id = ScannableCardId(""),
            createdAt = PassInstant(clock()),
        )
        val approved = when (validation) {
            is ScannableCardCreateResult.Success -> validation.card
            is ScannableCardCreateResult.InvalidLabel ->
                return@runIo rejectScannableCard(
                    ScannableCardRejectionReason.InvalidLabel(validation.reason),
                    telemetryKind = ScannableCardRejectedKind.LabelInvalid,
                )
            is ScannableCardCreateResult.InvalidPayload ->
                return@runIo rejectScannableCard(
                    ScannableCardRejectionReason.InvalidPayload(validation.reason),
                    telemetryKind = ScannableCardRejectedKind.PayloadInvalid,
                )
            is ScannableCardCreateResult.UnsupportedFormat ->
                return@runIo rejectScannableCard(
                    ScannableCardRejectionReason.UnsupportedFormat(validation.format),
                    telemetryKind = ScannableCardRejectedKind.FormatUnsupported,
                )
            is ScannableCardCreateResult.EncoderFailure ->
                return@runIo rejectScannableCard(
                    ScannableCardRejectionReason.EncoderFailure(validation.reason),
                    telemetryKind = ScannableCardRejectedKind.EncoderFailed,
                )
        }

        val outcome = writeMutex.withLock {
            // O(N) per insert: listAll() re-runs the validator on every row. Acceptable
            // because wallets hold O(10s) of cards; revisit if profiles flag it.
            val now = clock()
            val o = scannableCardStore.insert(
                ScannableCardInsertRequest(
                    payload = approved.payload,
                    format = approved.format,
                    label = approved.label,
                    nowEpochMs = now,
                ),
            )
            _scannableCards.value = scannableCardStore.listAll()
            o
        }
        telemetryGuard.onScannableCardCreated(approved.format)
        StorageResult.Success(outcome.id)
    }

    override suspend fun updateScannableCard(
        id: ScannableCardRecordId,
        input: ScannableCardCreateInput,
    ): StorageResult<Unit> = runIo {
        // Validator does not consume the id or timestamp for validation logic; storage
        // preserves the existing row's created_at_epoch_ms at the SQL layer, so the
        // placeholders below never reach disk.
        val validation = ScannableCardInputValidator.validate(
            input = input,
            id = ScannableCardId(id.value.toString()),
            createdAt = PassInstant(clock()),
        )
        val approved = when (validation) {
            is ScannableCardCreateResult.Success -> validation.card
            is ScannableCardCreateResult.InvalidLabel ->
                return@runIo rejectScannableCard(
                    ScannableCardRejectionReason.InvalidLabel(validation.reason),
                    telemetryKind = ScannableCardRejectedKind.LabelInvalid,
                )
            is ScannableCardCreateResult.InvalidPayload ->
                return@runIo rejectScannableCard(
                    ScannableCardRejectionReason.InvalidPayload(validation.reason),
                    telemetryKind = ScannableCardRejectedKind.PayloadInvalid,
                )
            is ScannableCardCreateResult.UnsupportedFormat ->
                return@runIo rejectScannableCard(
                    ScannableCardRejectionReason.UnsupportedFormat(validation.format),
                    telemetryKind = ScannableCardRejectedKind.FormatUnsupported,
                )
            is ScannableCardCreateResult.EncoderFailure ->
                return@runIo rejectScannableCard(
                    ScannableCardRejectionReason.EncoderFailure(validation.reason),
                    telemetryKind = ScannableCardRejectedKind.EncoderFailed,
                )
        }

        val matched = writeMutex.withLock {
            val ok = scannableCardStore.update(
                id,
                ScannableCardUpdateRequest(
                    payload = approved.payload,
                    format = approved.format,
                    label = approved.label,
                ),
            )
            if (ok) _scannableCards.value = scannableCardStore.listAll()
            ok
        }
        if (!matched) return@runIo failure(StorageError.IntegrityViolation(id))
        StorageResult.Success(Unit)
    }

    override suspend fun loadScannableCard(
        id: ScannableCardRecordId,
    ): StorageResult<ScannableCard> = runIo {
        val card = scannableCardStore.loadById(id)
            ?: return@runIo failure(StorageError.IntegrityViolation(id))
        StorageResult.Success(card)
    }

    override suspend fun deleteScannableCard(
        id: ScannableCardRecordId,
    ): StorageResult<Unit> = runIo {
        val deleted = writeMutex.withLock {
            val outcome = scannableCardStore.delete(id) ?: return@withLock null
            _scannableCards.value = _scannableCards.value.filterNot { it.id.value == id.value.toString() }
            outcome
        } ?: return@runIo failure(StorageError.IntegrityViolation(id))

        telemetryGuard.onScannableCardDeleted(deleted.format)
        StorageResult.Success(Unit)
    }

    override fun observeScannableCards(): Flow<List<ScannableCard>> =
        _scannableCards.asStateFlow()

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
            scannableCardStore.close()
            documentStore.close()
            store.close()
        }
    }

    /**
     * Returns the storage-side rejection kind for an insert, or null if all caps pass.
     * Checked in order: size, page count (PDF only), label length. The page cap is skipped
     * for the image arm, which is a single page and cannot exceed it.
     */
    private fun rejectionKindOrNull(
        insert: DocumentInsert,
        byteCount: Long,
    ): DocumentStorageRejectedKind? = when {
        byteCount > DocumentBounds.MAX_BYTES -> DocumentStorageRejectedKind.OversizedAtStorage
        insert is DocumentInsert.Pdf && insert.pageCount > DocumentBounds.MAX_PAGES ->
            DocumentStorageRejectedKind.TooManyPagesAtStorage
        insert.label.length > DocumentBounds.MAX_LABEL_CHARS ->
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
     * Single emission point for a scannable-card validator rejection. Same discipline
     * as [rejectDocument]: skip [failure] so `onStorageFailure` does not double-fire.
     */
    private fun <T> rejectScannableCard(
        reason: ScannableCardRejectionReason,
        telemetryKind: ScannableCardRejectedKind,
    ): StorageResult<T> {
        telemetryGuard.onScannableCardRejected(telemetryKind)
        return StorageResult.Failure(StorageError.ScannableCardRejected(reason))
    }

    /**
     * Single emission point for a pass-side defensive rejection (today: user_label
     * cap). Same discipline as [rejectDocument]: skip [failure] so `onStorageFailure`
     * does not double-fire alongside `onPassRejected`.
     */
    private fun <T> rejectPass(kind: PassUpdateRejectedKind): StorageResult<T> {
        telemetryGuard.onPassRejected(kind)
        return StorageResult.Failure(StorageError.PassRejected(kind))
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
        // page_count value persisted for image rows: an image is a single page. The column
        // stays NOT NULL (its historical v2 shape); `format` is the real discriminator.
        private const val IMAGE_PAGE_COUNT: Int = 1

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
            val opened = when (openResult) {
                is StorageResult.Success -> openResult.value
                is StorageResult.Failure -> {
                    telemetryGuard.onStorageFailure(
                        kind = openResult.error.toFailureKind(),
                        unknownKind = (openResult.error as? StorageError.Unknown)?.kind,
                    )
                    return StorageResult.Failure(openResult.error)
                }
            }
            val store = SqlCipherPassStore(opened.db, opened.keyHandle, telemetryGuard)
            val documentStore = SqlCipherDocumentStore(opened.db)
            val scannableCardStore = SqlCipherScannableCardStore(opened.db, telemetryGuard)
            val repo = SqlCipherPassRepository(
                store = store,
                documentStore = documentStore,
                scannableCardStore = scannableCardStore,
                telemetryGuard = telemetryGuard,
                ioDispatcher = ioDispatcher,
                clock = clock,
                keyBacking = keyProvider.keyBacking,
            )
            return StorageResult.Success(repo)
        }
    }
}
