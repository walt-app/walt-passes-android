package `is`.walt.passes.storage

import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.SignatureStatus
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
 * Auto-incremented primary-key surrogate for a row in the `passes` table. Distinct from
 * the PKPASS identity tuple (`type`, `serialNumber`, `organizationName`) because the same
 * identity may legitimately be re-imported across the same `id` over time.
 */
@JvmInline
public value class PassRecordId(public val value: Long)
