package `is`.walt.passes.storage

import android.content.Context
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.core.toKind
import `is`.walt.passes.storage.internal.PassStore
import `is`.walt.passes.storage.internal.SqlCipherDatabaseFactory
import `is`.walt.passes.storage.internal.SqlCipherPassStore
import `is`.walt.passes.storage.internal.toFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
    private val telemetryGuard: StorageTelemetryGuard,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    keyBacking: KeyBacking,
) : PassRepository {

    private val writeMutex = Mutex()
    private val closed = AtomicBoolean(false)

    private val _passes: MutableStateFlow<List<PassSummary>> = MutableStateFlow(store.listSummaries())
    override val passes: StateFlow<List<PassSummary>> = _passes.asStateFlow()

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

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            store.close()
        }
    }

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
            val repo = SqlCipherPassRepository(
                store = store,
                telemetryGuard = telemetryGuard,
                ioDispatcher = ioDispatcher,
                clock = clock,
                keyBacking = keyProvider.keyBacking,
            )
            return StorageResult.Success(repo)
        }
    }
}
