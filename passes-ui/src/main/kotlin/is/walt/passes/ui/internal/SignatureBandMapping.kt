package `is`.walt.passes.ui.internal

import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.ui.SignatureBand

/**
 * The single mapping from `passes-core`'s [SignatureStatus] sealed type to the
 * UI-facing [SignatureBand] enum. Lifted out of any specific composable so the
 * mapping is in exactly one place — a security-load-bearing rule (the band the
 * user sees IS the trust claim for that pass) does not tolerate two copies that
 * could drift on a `s/Incomplete/SelfSigned/` rename in a single file.
 *
 * Adding an arm to either type is still a compile error here; the win is that
 * a *semantic* edit (mapping changes, not arm adds) only happens in one spot.
 */
internal fun SignatureStatus.toBand(): SignatureBand = when (this) {
    SignatureStatus.Unsigned -> SignatureBand.Untrusted
    SignatureStatus.SelfSigned -> SignatureBand.SelfSigned
    SignatureStatus.AppleVerified -> SignatureBand.AppleVerified
    SignatureStatus.CertChainIncomplete -> SignatureBand.Incomplete
}
