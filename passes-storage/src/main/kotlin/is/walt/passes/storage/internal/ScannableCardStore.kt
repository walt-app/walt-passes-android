package `is`.walt.passes.storage.internal

import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.storage.ScannableCardRecordId

/**
 * Internal persistence boundary for the `scannable_cards` table. Same shape as
 * [PassStore] and [DocumentStore]: blocking, synchronous, called by the repository
 * inside an IO dispatcher. A fake impl in `ScannableCardRepositoryTest` exercises the
 * repo-layer plumbing without an Android runtime.
 *
 * Materialization of a [ScannableCard] is delegated to the kernel validator on load —
 * passes-core's `ScannableCard` constructor is module-internal, and the validator is
 * the only public mint path. Re-running validation on every load also acts as
 * defense-in-depth against on-disk tampering: if a row's bytes were altered to violate
 * the format's charset / length rules, the row is dropped via
 * `onMigrationRowDropped(Other)` rather than surfacing as a fake-trusted artifact.
 */
internal interface ScannableCardStore {
    fun listAll(): List<ScannableCard>
    fun loadById(id: ScannableCardRecordId): ScannableCard?
    fun insert(request: ScannableCardInsertRequest): ScannableCardInsertOutcome
    fun delete(id: ScannableCardRecordId): ScannableCardDeleteOutcome?
    fun close()
}

/**
 * Pre-validated request to persist a scannable card. The repository runs the kernel
 * validator first and feeds the trimmed payload and label into this request; the
 * store does NOT re-run validation on insert.
 */
internal data class ScannableCardInsertRequest(
    val payload: String,
    val format: ScannableFormat,
    val label: String,
    val nowEpochMs: Long,
)

internal data class ScannableCardInsertOutcome(
    val id: ScannableCardRecordId,
)

internal data class ScannableCardDeleteOutcome(
    val format: ScannableFormat,
)
