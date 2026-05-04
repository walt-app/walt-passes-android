package `is`.walt.passes.ui

import `is`.walt.passes.core.PassType

/**
 * The PII-disciplined telemetry surface that `passes-ui` emits. Mirrors the
 * `TelemetryGuard` in `passes-core` and `StorageTelemetryGuard` in `passes-storage`:
 * every event method takes enums and primitives only, never free-form `String`s or
 * `Pass` / `PassField` instances.
 *
 * Adding a `String` (or `PassField`, or `Pass`) parameter to any method below is a
 * security-policy change, not an API addition. The `PublicApiSurfaceTest` pins this
 * shape so a casual addition fails to compile.
 *
 * Trust-claim relevance: walt-android wants to know how often the security
 * confirmation sheets are shown vs. confirmed vs. dismissed, so it can detect a
 * spike in dismissals (possible UX regression or social-engineering pattern). It
 * must NOT learn which URLs were shown or which pass field they came from — that
 * would re-introduce the PII leak the sheet was built to prevent.
 */
public interface UiTelemetryGuard {

    /**
     * A pass front rendered. Useful as a baseline against which sheet-display rates
     * can be normalized.
     */
    public fun onPassRendered(type: PassType, signatureBand: SignatureBand)

    /**
     * The user opened the back of a pass. The number of fields revealed is a
     * coarse-grained signal of pass complexity, not user identity.
     */
    public fun onPassBackOpened(type: PassType)

    /**
     * A security confirmation sheet was displayed in response to a tap on a
     * back-field link. [intentKind] tells the host which of url / phone / email
     * was the trigger; the actual target string never leaves this module.
     */
    public fun onSecuritySheetShown(intentKind: SecurityIntentKind, type: PassType)

    /**
     * The user confirmed the action. The host's outbound `Intent` is the next thing
     * to fire after this event.
     */
    public fun onSecuritySheetConfirmed(intentKind: SecurityIntentKind, type: PassType)

    /**
     * The user dismissed the sheet without confirming. A sustained rise in this
     * rate is the signal walt-android wants to see.
     */
    public fun onSecuritySheetDismissed(intentKind: SecurityIntentKind, type: PassType)

    /**
     * An image-decode attempt was rejected by [ImageRenderBounds]. The host shows
     * a placeholder instead of the requested asset; the event lets the host
     * detect either a malformed pass archive or a bounds threshold that needs
     * tuning.
     */
    public fun onImageDecodeRejected(reason: ImageDecodeRejection)
}

/**
 * Coarse trust band, derived from `passes-core`'s `SignatureStatusKind`. Kept in the
 * UI module because the trust band is what the UI displays; the storage module
 * already exposes the underlying kind.
 */
public enum class SignatureBand {
    Untrusted,
    SelfSigned,
    AppleVerified,
    Incomplete,
}

/**
 * Which of the three security intent families opened a sheet. Mirrors the three
 * arms of [SecurityIntent] at the telemetry boundary so the host learns the kind
 * without learning the value.
 */
public enum class SecurityIntentKind {
    Url,
    Phone,
    Email,
}

/**
 * Why an image-decode attempt was refused. The five buckets cover the cases the
 * decode pipeline can encounter; "Other" is the catch-all for unexpected failures
 * surfaced from the underlying Android `ImageDecoder`.
 */
public enum class ImageDecodeRejection {
    ExceedsWidth,
    ExceedsHeight,
    ExceedsArea,
    Malformed,
    Other,
}

/**
 * No-op default for hosts that have not (yet) wired a guard. Convenience-only; the
 * production walt-android wiring supplies a real guard.
 */
public object NoopUiTelemetryGuard : UiTelemetryGuard {
    override fun onPassRendered(type: PassType, signatureBand: SignatureBand): Unit = Unit
    override fun onPassBackOpened(type: PassType): Unit = Unit
    override fun onSecuritySheetShown(intentKind: SecurityIntentKind, type: PassType): Unit = Unit
    override fun onSecuritySheetConfirmed(intentKind: SecurityIntentKind, type: PassType): Unit = Unit
    override fun onSecuritySheetDismissed(intentKind: SecurityIntentKind, type: PassType): Unit = Unit
    override fun onImageDecodeRejected(reason: ImageDecodeRejection): Unit = Unit
}
