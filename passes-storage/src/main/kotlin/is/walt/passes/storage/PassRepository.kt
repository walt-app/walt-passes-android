package `is`.walt.passes.storage

import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.core.ScannableCardCreateInput
import `is`.walt.passes.core.SignatureStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single repository contract that walt-android's `core/data-passes` Hilt module binds
 * against. Backed by a SQLCipher database with a Keystore-wrapped key (see ADR 0002).
 *
 * All trust-claim-bearing storage logic lives behind this interface: encryption-at-rest,
 * backup exclusion, irreversible deletion, decoded summary maintenance.
 */
public interface PassRepository {
    /**
     * Hot list of pass summaries, sorted by `created_at_epoch_ms` descending. Backed by the
     * `passes` row's query columns; image and locale data are NOT loaded here.
     */
    public val passes: StateFlow<List<PassSummary>>

    /**
     * Insert a parsed pass, or replace an existing row whose
     * `(type, serial_number, organization_name)` identity matches. Returns the assigned
     * [PassRecordId] in [StorageResult.Success.value].
     *
     * On replacement the existing image and locale rows are atomically replaced inside the
     * same transaction. The decoded summary is recomputed from [pass]; callers do not pass
     * a separate summary.
     */
    public suspend fun upsert(
        pass: Pass,
        signatureStatus: SignatureStatus,
    ): StorageResult<PassRecordId>

    /**
     * Load a stored pass with all images and locales materialized. Use [summaryOf] for the
     * list view; [load] is the detail-view path.
     */
    public suspend fun load(id: PassRecordId): StorageResult<StoredPass>

    /**
     * Single-pass summary lookup without materializing image/locale rows.
     */
    public suspend fun summaryOf(id: PassRecordId): StorageResult<PassSummary>

    /**
     * Irreversible delete (ADR 0002 D6). Deletes the `passes` row and its cascaded image
     * and locale rows in one transaction, updates the [passes] StateFlow, then emits the
     * `onPassDeleted` telemetry event. No undo, no soft-delete, no VACUUM.
     *
     * Confirmation UI is the caller's responsibility; the repository trusts the call.
     */
    public suspend fun delete(id: PassRecordId): StorageResult<Unit>

    /**
     * Insert a stored PDF document. Bytes and thumbnail bytes are written into the
     * `documents` and `document_thumbnails` tables in the same transaction; the assigned
     * row id is returned. The repository never decodes [pdfBytes] or [thumbnailBytes];
     * they round-trip as opaque BLOBs. The persisted `byte_count` is `pdfBytes.size` —
     * derived rather than caller-asserted, so a stale or zero size header from a future
     * caller cannot bypass the cap.
     *
     * Defense in depth (ADR 0005 D7): rejects PDFs whose size exceeds
     * [DocumentBounds.MAX_BYTES] with [DocumentStorageRejectedKind.OversizedAtStorage],
     * page counts exceeding [DocumentBounds.MAX_PAGES] with
     * [DocumentStorageRejectedKind.TooManyPagesAtStorage], and labels longer than
     * [DocumentBounds.MAX_LABEL_CHARS] with
     * [DocumentStorageRejectedKind.LabelTooLongAtStorage]. The renderer service in
     * `passes-pdf-core` already enforces the size and page caps; storage carries them
     * again so a future caller bug cannot land an oversized row. The label cap exists
     * only at this layer: nothing upstream bounds the consumer-supplied display label.
     *
     * Returns [StorageError.DocumentRejected] when any cap is violated; the typed arm
     * lets callers distinguish a defensive-rejection from a transient infra failure
     * without listening to telemetry.
     */
    public suspend fun insertDocument(
        label: String,
        pdfBytes: ByteArray,
        pageCount: Int,
        thumbnailBytes: ByteArray,
    ): StorageResult<DocumentRecordId>

    /**
     * Cold flow of document list-view rows, sorted by `imported_at_epoch_ms` descending.
     * Emits the current snapshot on collect and re-emits when documents are inserted or
     * deleted. The PDF and thumbnail blobs are NOT loaded by this flow; consumers fetch
     * them with [loadDocumentBytes] / [loadDocumentThumbnail] on demand.
     */
    public fun observeDocuments(): Flow<List<DocumentRow>>

    /**
     * Loads the raw PDF bytes for the document with [id]. The bytes are returned to the
     * caller untouched; the storage layer never parses, sniffs, decodes, or otherwise
     * inspects them (ADR 0005 D4).
     */
    public suspend fun loadDocumentBytes(id: DocumentRecordId): StorageResult<ByteArray>

    /**
     * Loads the rendered thumbnail bytes for the document with [id]. Thumbnails are
     * generated upstream by the isolated renderer service (ADR 0005 D3) and stored as
     * opaque BLOBs.
     */
    public suspend fun loadDocumentThumbnail(id: DocumentRecordId): StorageResult<ByteArray>

    /**
     * Irreversible delete of a document row and its cascaded thumbnail row in one
     * transaction (ADR 0002 D6). Mirrors [delete] for passes: no undo, no soft-delete,
     * no VACUUM. After the transaction commits, the document StateFlow is updated and
     * `onDocumentDeleted` is emitted.
     */
    public suspend fun deleteDocument(id: DocumentRecordId): StorageResult<Unit>

    /**
     * Mints a [ScannableCard] from raw [input], persists it, and returns the assigned
     * [ScannableCardRecordId]. The kernel validator
     * (`ScannableCardInputValidator.validate`) is the single choke point that decides
     * whether the input passes: a validation rejection bubbles up as
     * [StorageError.ScannableCardRejected] with the typed kernel reason preserved, never
     * as a generic infra failure. The row never reaches disk in that case.
     *
     * Storage owns the id and timestamp: the [ScannableCardId][`is`.walt.passes.core.ScannableCardId]
     * the consumer observes on the materialized [ScannableCard] is the stringified row
     * id, and `createdAt` is taken from the repository's injected clock at insert time.
     * The kernel does not mint these (see KDoc on `ScannableCardId`); centralizing here
     * keeps multiple consumer call sites from inventing their own id schemes.
     */
    public suspend fun createScannableCard(
        input: ScannableCardCreateInput,
    ): StorageResult<ScannableCardRecordId>

    /**
     * Loads a stored [ScannableCard] by row id. Returns [StorageError.IntegrityViolation]
     * if no row matches.
     */
    public suspend fun loadScannableCard(
        id: ScannableCardRecordId,
    ): StorageResult<ScannableCard>

    /**
     * Irreversible delete (ADR 0002 D6). Mirrors [delete] for passes: removes the row
     * in one transaction, updates the [observeScannableCards] flow, then emits
     * `onScannableCardDeleted`. No undo, no soft-delete, no VACUUM.
     */
    public suspend fun deleteScannableCard(
        id: ScannableCardRecordId,
    ): StorageResult<Unit>

    /**
     * Cold flow of [ScannableCard] rows sorted by `created_at_epoch_ms` descending.
     * Emits the current snapshot on collect and re-emits on insert / delete. Unlike the
     * pass and document lanes, the full card materializes here — there are no large
     * blob columns to defer, and the consumer's tile renderer needs the payload to
     * re-encode the barcode at render time.
     */
    public fun observeScannableCards(): Flow<List<ScannableCard>>

    /**
     * Releases the underlying database connection. Idempotent: calling [close] more than
     * once is a no-op, and method calls after [close] return [StorageError.DatabaseLocked]
     * rather than throwing. Intended for consumer paths where the repository's lifetime is
     * shorter than the process (logout, multi-user switching, instrumentation tear-down).
     *
     * The default Hilt-singleton wiring in walt-android does not call [close]; the process
     * exit reclaims the handle. This method exists so the contract permits explicit
     * teardown rather than relying on process lifetime alone.
     */
    public fun close()
}

/**
 * The list-view projection of a stored pass. Mirrors the indexed columns of the `passes`
 * table so the wallet list does not pay for image or locale I/O.
 */
public data class PassSummary(
    public val id: PassRecordId,
    public val type: PassType,
    public val serialNumber: String,
    public val organizationName: String,
    public val description: String,
    public val expirationDate: PassInstant?,
    public val voided: Boolean,
    public val signatureStatus: SignatureStatus,
    public val createdAt: PassInstant,
    public val updatedAt: PassInstant,
)

/**
 * The detail-view projection. The fully materialized [Pass] from passes-core, plus the
 * trust band recorded at import time and the storage timestamps. Re-rendering uses
 * [Pass] directly; [signatureStatus] drives the trust badge without re-running PKCS#7
 * verification.
 */
public data class StoredPass(
    public val id: PassRecordId,
    public val pass: Pass,
    public val signatureStatus: SignatureStatus,
    public val createdAt: PassInstant,
    public val updatedAt: PassInstant,
)

/**
 * Common surrogate-key surface shared by [PassRecordId] and [DocumentRecordId]. Carrying
 * a sealed wrapper rather than a raw `Long` keeps [StorageError.IntegrityViolation] honest:
 * the arm names which table the unknown id belongs to, so a future telemetry consumer or
 * unit test cannot misread a document id as a pass id.
 */
public sealed interface RecordId {
    public val value: Long
}

/**
 * Auto-incremented primary-key surrogate for a row in the `passes` table. Distinct from
 * the PKPASS identity tuple (`type`, `serialNumber`, `organizationName`) because the same
 * identity may legitimately be re-imported across the same `id` over time.
 */
@JvmInline
public value class PassRecordId(public override val value: Long) : RecordId

/**
 * Auto-incremented primary-key surrogate for a row in the `documents` table. Mirrors
 * [PassRecordId]'s role for the pass side. Wrapping the id in a value class prevents an
 * accidental cross-domain substitution (e.g., passing a `PassRecordId` to
 * [PassRepository.loadDocumentBytes]) at compile time rather than runtime.
 */
@JvmInline
public value class DocumentRecordId(public override val value: Long) : RecordId

/**
 * Auto-incremented primary-key surrogate for a row in the `scannable_cards` table.
 * Distinct from [`is`.walt.passes.core.ScannableCardId] (which is the kernel's opaque
 * string identifier exposed on [ScannableCard]) so that a row id cannot be silently
 * substituted for any other [RecordId] arm at compile time.
 */
@JvmInline
public value class ScannableCardRecordId(public override val value: Long) : RecordId
