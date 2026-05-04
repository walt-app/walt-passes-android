package `is`.walt.passes.storage.internal

import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.storage.PassRecordId
import `is`.walt.passes.storage.PassSummary
import `is`.walt.passes.storage.StorageError
import `is`.walt.passes.storage.StoredPass

/**
 * Internal persistence boundary inside `passes-storage`. Sits between the public
 * `PassRepository` (which owns `StateFlow` plumbing and telemetry sequencing) and the actual
 * storage backend (SQLCipher in production, an in-memory fake for the StateFlow + delete
 * contract test on Robolectric / JVM CI).
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

/**
 * Open-result for a [PassStore] factory. Carries a typed failure arm so the repository can
 * surface `KeyUnavailable` / `KeyUnwrapFailed` distinctly without pretending exceptions
 * survived the API boundary.
 */
internal sealed interface PassStoreOpenResult {
    data class Success(val store: PassStore) : PassStoreOpenResult
    data class Failure(val error: StorageError) : PassStoreOpenResult
}
