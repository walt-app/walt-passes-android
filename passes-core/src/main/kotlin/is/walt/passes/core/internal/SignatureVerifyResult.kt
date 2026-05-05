package `is`.walt.passes.core.internal

import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.core.TamperReason

/**
 * Outcome of running [verifySignature] over a detached PKCS#7 / CMS signature blob and
 * the manifest bytes it claims to sign. Internal only: the parser-glue bead lifts a
 * [Failed] into the right [`is`.walt.passes.core.ParseResult] arm and an [Ok] into
 * [`is`.walt.passes.core.ParseResult.Success] with the contained [SignatureStatus].
 *
 * Mirrors [ManifestVerifyResult]'s and [PassJsonDecodeResult]'s split. Public failure
 * surface stays tight ([TamperReason] has only the two arms that this layer can produce
 * — [TamperReason.ManifestSignatureMismatch] and [TamperReason.SignatureCryptoFailure])
 * but routing happens here in terms of the same arms a parser-glue `when` will fan out
 * across, so adding a new policy outcome can never silently bypass the trust UI.
 *
 * The [SignatureStatus] inside [Ok] is constrained at the type level only by the public
 * sealed interface; this layer is documented to never produce
 * [SignatureStatus.Unsigned] (that arm comes from the parser-glue bead when the
 * `signature` archive entry is absent). The verifier is invoked only when a signature
 * blob is present, so [Unsigned] would be a category error here.
 */
internal sealed interface SignatureVerifyResult {
    data class Ok(val status: SignatureStatus) : SignatureVerifyResult

    data class Failed(val reason: TamperReason) : SignatureVerifyResult
}
