package `is`.walt.passes.storage

import android.content.Context
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.storage.internal.PassStore
import `is`.walt.passes.storage.internal.SqlCipherDatabaseFactory
import `is`.walt.passes.storage.internal.SqlCipherPassStore
import `is`.walt.passes.storage.internal.toFailureKind
import `is`.walt.passes.storage.internal.toSignatureStatusKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production [PassRepository]. Backed by SQLCipher via [SqlCipherPassStore]; the StateFlow,
 * delete sequencing, and telemetry sequencing live here so they can be exercised in
 * isolation against a fake [PassStore] (see test sources).
 *
 * The constructor is internal so the only construction path is [create], which provisions
 * the database key, opens SQLCipher, runs the schema DDL, and seeds the StateFlow from
 * disk. Hilt modules in walt-android's `core/data-passes` bind [PassRepository] to the
 * value returned by this factory.
 */
public class SqlCipherPassRepository internal constructor(
    private val store: PassStore,
    private val telemetryGuard: StorageTelemetryGuard,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    keyBacking: KeyBacking,
) : PassRepository {

    private val writeMutex = Mutex()

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
            signatureStatus = signatureStatus.toSignatureStatusKind(),
            wasReplacement = outcome.wasReplacement,
        )
        StorageResult.Success(outcome.recordId)
    }

    override suspend fun load(id: PassRecordId): StorageResult<StoredPass> = runIo {
        val stored = store.loadById(id)
            ?: return@runIo StorageResult.Failure(StorageError.IntegrityViolation(id))
        StorageResult.Success(stored)
    }

    override suspend fun summaryOf(id: PassRecordId): StorageResult<PassSummary> = runIo {
        val summary = store.summaryById(id)
            ?: return@runIo StorageResult.Failure(StorageError.IntegrityViolation(id))
        StorageResult.Success(summary)
    }

    override suspend fun delete(id: PassRecordId): StorageResult<Unit> = runIo {
        val deleted = writeMutex.withLock {
            val outcome = store.delete(id) ?: return@withLock null
            _passes.value = _passes.value.filterNot { it.id == id }
            outcome
        } ?: return@runIo StorageResult.Failure(StorageError.IntegrityViolation(id))

        telemetryGuard.onPassDeleted(
            type = deleted.summary.type,
            signatureStatus = deleted.summary.signatureStatus.toSignatureStatusKind(),
        )
        StorageResult.Success(Unit)
    }

    private suspend inline fun <T> runIo(crossinline block: suspend () -> StorageResult<T>): StorageResult<T> =
        withContext(ioDispatcher) {
            try {
                block()
            } catch (e: Throwable) {
                val unknown = StorageError.Unknown(
                    kind = UnknownStorageFailureKind.Other,
                    cause = e,
                )
                telemetryGuard.onStorageFailure(
                    kind = unknown.toFailureKind(),
                    unknownKind = unknown.kind,
                )
                StorageResult.Failure(unknown)
            }
        }

    public companion object {
        /**
         * Provisions SQLCipher with the `keyProvider`'s database key, runs [Schema.DDL] on
         * first open, and returns a [PassRepository] ready for use. The returned scope-bound
         * repository should outlive the wallet activity stack; consumers typically hold the
         * binding inside a Hilt singleton scope.
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
                is StorageResult.Failure -> return StorageResult.Failure(keyResult.error)
            }
            return try {
                val db = SqlCipherDatabaseFactory.openOrCreate(context, key)
                val store = SqlCipherPassStore(db)
                val repo = SqlCipherPassRepository(
                    store = store,
                    telemetryGuard = telemetryGuard,
                    ioDispatcher = ioDispatcher,
                    clock = clock,
                    keyBacking = keyProvider.keyBacking,
                )
                StorageResult.Success(repo)
            } catch (e: Throwable) {
                val unknown = StorageError.Unknown(
                    kind = UnknownStorageFailureKind.DatabaseCorrupt,
                    cause = e,
                )
                telemetryGuard.onStorageFailure(
                    kind = unknown.toFailureKind(),
                    unknownKind = unknown.kind,
                )
                StorageResult.Failure(unknown)
            }
        }
    }
}
