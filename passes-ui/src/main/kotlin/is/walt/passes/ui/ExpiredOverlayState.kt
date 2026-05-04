package `is`.walt.passes.ui

import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassInstant

/**
 * The non-suppressible "this pass is expired" overlay state. Computed from a `Pass` at
 * render time. UI code cannot construct an `ExpiredOverlayState.None` for a pass that
 * meets the expired criteria — see [from] — so a host that wants to "hide the badge
 * for this one pass" has to deliberately bypass the API.
 *
 * The `voided` flag and `expirationDate` together capture both PKPASS expiry mechanisms:
 *
 * - **Voided** passes are passes the issuer marked invalid via the
 *   `voided: true` field in pass.json. The badge reads "Voided" and renders the same
 *   way as expired-by-date.
 * - **Expired** passes have `expirationDate < now`. The badge reads "Expired" with the
 *   localized expiration date below.
 *
 * If both apply, voided wins (the issuer's explicit invalidation supersedes the date).
 */
public sealed interface ExpiredOverlayState {

    /** The pass is currently valid; no overlay is rendered. */
    public data object None : ExpiredOverlayState

    /** The pass is past its `expirationDate`. */
    public data class Expired(public val expiredAt: PassInstant) : ExpiredOverlayState

    /** The pass was marked `voided` by the issuer. */
    public data object Voided : ExpiredOverlayState

    public companion object {

        /**
         * Compute the overlay state for a pass at the given moment. `nowEpochMillis`
         * is supplied by the host (typically `System.currentTimeMillis()` or, in tests,
         * a fake clock) so this function stays JVM-pure and deterministic.
         */
        @JvmStatic
        public fun from(pass: Pass, nowEpochMillis: Long): ExpiredOverlayState {
            if (pass.voided) return Voided
            val expiration = pass.expirationDate ?: return None
            return if (expiration.epochMillis <= nowEpochMillis) {
                Expired(expiration)
            } else {
                None
            }
        }
    }
}
