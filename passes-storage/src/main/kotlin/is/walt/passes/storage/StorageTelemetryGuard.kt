package `is`.walt.passes.storage

import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.core.SignatureStatusKind as CoreSignatureStatusKind

/**
 * Re-exports `passes-core`'s [SignatureStatusKind][CoreSignatureStatusKind] under the
 * storage package so consumers binding [StorageTelemetryGuard] do not need a second import.
 * The arms are authoritative in `passes-core`; storage MUST NOT shadow or fork them.
 */
public typealias SignatureStatusKind = CoreSignatureStatusKind

/**
 * The storage-side counterpart to `passes-core`'s `TelemetryGuard`. Carries the same
 * structural-PII-prevention discipline: every method takes only enums, counts, and
 * durations. There is no `String`, `Pass`, `PassField`, or `ByteArray` parameter
 * anywhere in this interface.
 *
 * The trust claim "pass content never appears in storage telemetry" is delivered by the
 * shape of these methods, not by documentation. Reviewers MUST treat any future addition
 * of a free-form parameter as a security-policy change.
 */
// ComplexInterface: the surface intentionally carries every storage-side telemetry
// event in one place — that cohesion is the trust claim. Splitting would fragment the
// no-PII-in-telemetry guarantee across multiple bindings.
@Suppress("ComplexInterface")
public interface StorageTelemetryGuard {
    /**
     * Emitted once per database open after the key provider is initialized. Reports the
     * actual Keystore backing chosen so the wallet can surface hardware-backing status
     * without inspecting strings.
     */
    public fun onKeyProviderInitialized(backing: KeyBacking)

    /**
     * A pass was inserted or replaced. Carries the type and trust band only.
     */
    public fun onPassUpserted(
        type: PassType,
        signatureStatus: SignatureStatusKind,
        wasReplacement: Boolean,
    )

    /**
     * A pass row was deleted. Emitted after the transaction commits and after the
     * StateFlow is updated.
     */
    public fun onPassDeleted(type: PassType, signatureStatus: SignatureStatusKind)

    /**
     * A row failed to deserialize during a load or schema migration and was dropped.
     * Carries only the failure kind; the row identity is intentionally NOT part of the
     * event because the row is gone.
     */
    public fun onMigrationRowDropped(kind: MigrationFailureKind)

    /**
     * A storage call returned [StorageResult.Failure]. The [kind] is the structural
     * arm of [StorageError]; for [StorageError.Unknown] cases the [unknownKind] carries
     * the [UnknownStorageFailureKind].
     */
    public fun onStorageFailure(
        kind: StorageFailureKind,
        unknownKind: UnknownStorageFailureKind?,
    )

    /**
     * A PDF document row was inserted. Emitted after the transaction commits and after
     * the document StateFlow is updated. Carries byte and page counts only. The display
     * label, the PDF bytes, and the assigned id are intentionally NOT part of the event;
     * the same PII discipline as `onPassUpserted` applies.
     */
    public fun onDocumentImported(event: DocumentImportedEvent)

    /**
     * A document insertion was rejected by the storage-side defense-in-depth check
     * (ADR 0005 D7). Emitted before the row is created. The row never reaches disk.
     */
    public fun onDocumentRejected(kind: DocumentStorageRejectedKind)

    /**
     * A document row was deleted. Emitted after the transaction commits and after the
     * document StateFlow is updated. Carries the byte count of the deleted PDF only.
     */
    public fun onDocumentDeleted(event: DocumentDeletedEvent)

    /**
     * A user-generated scannable card row was inserted. Carries the format only — the
     * payload, label, and color are user-typed free-form and are intentionally NOT part
     * of the event. The same no-PII discipline as `onPassUpserted` applies.
     */
    public fun onScannableCardCreated(format: ScannableFormat)

    /**
     * A scannable card row was deleted. Emitted after the transaction commits and after
     * the observe flow is updated. Carries the format only.
     */
    public fun onScannableCardDeleted(format: ScannableFormat)

    /**
     * A scannable-card insert was refused by the kernel validator. Emitted before any
     * row is created. The structural [kind] is what flows through telemetry; the
     * underlying kernel rejection reason is preserved on the typed
     * [StorageError.ScannableCardRejected] for the consumer's error UI but is NOT
     * carried here (it would re-link telemetry to user input — bidi-marker offsets,
     * offending characters, etc.).
     */
    public fun onScannableCardRejected(kind: ScannableCardRejectedKind)
}

/**
 * Why a scannable-card insert was refused, projected to a stable telemetry vocabulary.
 * Two arms only — the kernel's richer rejection reasons (offending char, length, check
 * digit, etc.) carry information derived from user input and stay off telemetry.
 */
public enum class ScannableCardRejectedKind {
    LabelInvalid,
    PayloadInvalid,
}

/**
 * Telemetry event emitted on a successful document insert. Intentionally narrow: byte
 * count and page count are the only signals storage observes about the document. The
 * display label is excluded because it is user-facing free-form text (filename or
 * date-derived fallback) and would defeat the no-PII-in-telemetry trust claim.
 */
public data class DocumentImportedEvent(
    public val byteCount: Long,
    public val pageCount: Int,
)

/**
 * Why a storage-side document insert was rejected. The two arms mirror the renderer
 * service's import-time checks; storage refuses to land out-of-bounds rows so a future
 * caller bug cannot bypass the cap. The arms are deliberately suffixed `AtStorage` so
 * they cannot be confused with `passes-pdf-core`'s import-time `DocumentRejectedKind`,
 * which fires before bytes ever reach the storage layer.
 */
public enum class DocumentStorageRejectedKind {
    OversizedAtStorage,
    TooManyPagesAtStorage,
    LabelTooLongAtStorage,
}

/**
 * Telemetry event emitted on a successful document delete. Carries the byte count of
 * the deleted PDF; the id and label are intentionally omitted (the row is gone, and
 * carrying a label here would re-link telemetry to user data).
 */
public data class DocumentDeletedEvent(
    public val byteCount: Long,
)

/**
 * Stable telemetry projection of [StorageError]. Mirrors the sealed-interface arms
 * one-for-one. New arms in [StorageError] require new arms here.
 */
public enum class StorageFailureKind {
    KeyUnavailable,
    KeyUnwrapFailed,
    DatabaseLocked,
    IntegrityViolation,
    Unsupported,
    Unknown,
    DocumentRejected,
    ScannableCardRejected,
}

public enum class MigrationFailureKind {
    JsonShapeMismatch,
    UnknownEnumValue,
    BlobTruncated,
    Other,
}
