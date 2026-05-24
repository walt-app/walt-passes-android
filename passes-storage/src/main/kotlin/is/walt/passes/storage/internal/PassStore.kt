package `is`.walt.passes.storage.internal

import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.storage.PassRecordId
import `is`.walt.passes.storage.PassSummary
import `is`.walt.passes.storage.StoredPass

/**
 * Internal persistence boundary inside `passes-storage`. Sits between the public
 * `PassRepository` (which owns `StateFlow` plumbing and telemetry sequencing) and the actual
 * storage backend (SQLCipher in production, an in-memory fake on Robolectric / JVM CI).
 *
 * The interface is deliberately blocking and synchronous: the repository wraps each call in
 * the IO dispatcher. Pushing coroutines into the store would force the SQLite cursor APIs
 * (which are inherently blocking and not cancellable mid-cursor) to pretend at suspendability.
 */
internal interface PassStore {
    fun listSummaries(): List<PassSummary>
    fun loadById(id: PassRecordId): StoredPass?
    fun summaryById(id: PassRecordId): PassSummary?
    fun upsert(pass: Pass, signatureStatus: SignatureStatus, nowEpochMs: Long): UpsertOutcome
    fun delete(id: PassRecordId): DeleteOutcome?

    /**
     * Sets or clears the `user_label` column on the row matching [id]. Returns the
     * outcome (carrying the pass type and whether a prior label was present, for
     * telemetry) or `null` when no row matches. The caller is responsible for trimming
     * and cap-checking [label] before invocation; the store treats [label] as already
     * normalized.
     */
    fun updateUserLabel(id: PassRecordId, label: String?): UpdateUserLabelOutcome?
    fun close()
}

internal data class UpsertOutcome(
    val recordId: PassRecordId,
    val summary: PassSummary,
    val wasReplacement: Boolean,
)

internal data class DeleteOutcome(
    val summary: PassSummary,
)

internal data class UpdateUserLabelOutcome(
    val summary: PassSummary,
    val hadPriorLabel: Boolean,
)
