package `is`.walt.passes.core.internal

import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.core.TamperReason
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignerDigestMismatchException
import org.bouncycastle.cms.SignerId
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.Selector
import org.bouncycastle.util.Store
import java.security.cert.CertPathBuilder
import java.security.cert.CertStore
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate

/**
 * Verifies a detached PKCS#7 / CMS signature blob against the bytes of a PKPASS
 * archive's `manifest.json`. Pure function: no I/O, no temp files. The verifier is
 * invoked only when the archive carries a `signature` entry — the parser-glue bead
 * routes the missing-entry case to [SignatureStatus.Unsigned] (and gates that on
 * [ParserConfig.acceptUnsignedArchives]) without ever calling here.
 *
 * **Trust posture (decision-wlt-0tn-q1, lenient default).** This layer surfaces
 * three of the four [SignatureStatus] arms: [SignatureStatus.AppleVerified],
 * [SignatureStatus.SelfSigned], and [SignatureStatus.CertChainIncomplete]. Whether
 * the latter two are surfaced as [Ok] or coerced into [Failed] depends on
 * [ParserConfig.acceptSelfSignedCertificates]. A mathematical mismatch between the
 * signature and `manifestBytes` is always a tampering signal, regardless of the
 * trust toggle.
 *
 * **Four failure shapes, mapped to three [TamperReason] arms:**
 *
 *  1. CMS structurally well-formed and the signed math evaluates against
 *     `manifestBytes` to "doesn't match" — either [SignerInformation.verify] returns
 *     `false` or BC raises [CMSSignerDigestMismatchException]. → [Failed]
 *     ([TamperReason.ManifestSignatureMismatch]).
 *  2. CMS structurally malformed, an unexpected BouncyCastle exception, or any
 *     cryptographic operation fails outside the digest-mismatch path. → [Failed]
 *     ([TamperReason.SignatureCryptoFailure]). The outer `runCatching` is the
 *     load-bearing safety net here: `verifySignature` must never propagate an
 *     exception out, since the parser-glue bead's `when` cannot surface one.
 *  3. CMS parses but the first SignerInfo's identifier (IssuerAndSerialNumber or
 *     SubjectKeyIdentifier) finds no matching certificate in the envelope's cert
 *     set. → [Failed] ([TamperReason.SignerCertificateMissing]). Bucketed apart
 *     from [SignatureCryptoFailure] so telemetry can distinguish a malformed
 *     envelope from a wrong-signer-ID-shape regression (wpass-4js).
 *  4. CMS verifies but the cert chain does not reach a bundled Apple anchor — the
 *     [ParserConfig.acceptSelfSignedCertificates] toggle decides between
 *     [SignatureStatus.SelfSigned] / [SignatureStatus.CertChainIncomplete] (lenient,
 *     default) or [TamperReason.SignatureCryptoFailure] (strict).
 *
 * **Constant-time digest comparison.** `SignerInformation.verify` delegates digest
 * comparison to [java.security.MessageDigest.isEqual] internally (BC verifies via
 * the JCE Signature object, which uses the constant-time comparator), so we do not
 * re-roll our own. The manifest-vs-signature math is the only digest comparison
 * happening here; the per-file SHA-1 chain lives in [verifyManifest].
 *
 * **No revocation checking.** [PKIXBuilderParameters.isRevocationEnabled] is set to
 * `false`. Revocation requires either an out-of-band CRL fetch (network) or an
 * embedded CRL the signature blob can choose to omit. Walt's trust claim is "this
 * pass chains to Apple at parse time," not "we re-verified Apple's revocation
 * stance just now"; the latter is impossible without a network round-trip we have
 * declined to take.
 *
 * **No date-shift override.** The path builder's date defaults to "now," so a
 * valid Apple-signed pass with an expired leaf cert downgrades to
 * [SignatureStatus.CertChainIncomplete] (lenient default still surfaces the pass).
 * That matches Walt's broader stance: expiry is information for the UI, not a
 * reason to drop trust-validated content. A future bead can revisit by snapshotting
 * the chain build at the signing time embedded in the CMS attributes.
 */
internal fun verifySignature(
    signatureBytes: ByteArray,
    manifestBytes: ByteArray,
    config: ParserConfig,
): SignatureVerifyResult {
    // Anchor lookups MUST live inside the runCatching: AppleTrustAnchors.trustAnchors()
    // calls into resource I/O and CertificateFactory.generateCertificate, both of which
    // can still throw on a genuinely stripped JAR or a corrupted .cer file. (The loader
    // resolves the bundled certs by absolute classpath name, so it is itself robust to
    // an R8/ProGuard consumer build *repackaging* this class — see AppleTrustAnchors.)
    // Kotlin's default `by lazy` (LazyThreadSafetyMode.SYNCHRONIZED)
    // does NOT cache the exception: each call retries the initializer and re-throws on
    // failure. The end-user effect is the same (every parse fails until the JAR is
    // rebuilt), but the mechanic is "retry and re-throw," not "first call poisons the
    // cache." Treating the loader as part of the verification operation collapses that
    // failure mode onto the documented Failed(SignatureCryptoFailure) arm.
    return runCatching {
        val ctx =
            VerifyContext(
                config,
                AppleTrustAnchors.trustAnchors(),
                AppleTrustAnchors.knownIntermediates(),
            )
        verifyAndClassify(signatureBytes, manifestBytes, ctx)
    }.getOrElse { SignatureVerifyResult.Failed(TamperReason.SignatureCryptoFailure) }
}

/**
 * **Test seam — DO NOT call from production code.** Apple's WWDR private key is
 * unavailable to tests, so the synthesized chains every test builds need a stand-in
 * trust anchor. Production callers MUST go through [verifySignature]; passing
 * [emptySet] anchors here silently downgrades every Apple-signed input to
 * [SignatureStatus.SelfSigned] / [SignatureStatus.CertChainIncomplete] (lenient) or
 * [TamperReason.SignatureCryptoFailure] (strict) — a trust-degradation footgun no
 * test in this suite would catch, since the suite itself routes through here. The
 * `ForTesting` suffix is the conventional flag.
 *
 * Otherwise identical to the production path: the same lazily-constructed
 * [BouncyCastleProvider] instance is reused, exceptions are caught, and the policy
 * mapping is the same.
 */
internal fun verifySignatureAgainstAnchorsForTesting(
    signatureBytes: ByteArray,
    manifestBytes: ByteArray,
    config: ParserConfig,
    trustAnchors: Set<TrustAnchor>,
    knownIntermediates: Set<X509Certificate>,
): SignatureVerifyResult {
    val ctx = VerifyContext(config, trustAnchors, knownIntermediates)
    return runCatching { verifyAndClassify(signatureBytes, manifestBytes, ctx) }
        .getOrElse { SignatureVerifyResult.Failed(TamperReason.SignatureCryptoFailure) }
}

private fun verifyAndClassify(
    signatureBytes: ByteArray,
    manifestBytes: ByteArray,
    ctx: VerifyContext,
): SignatureVerifyResult {
    val signedData = CMSSignedData(CMSProcessableByteArray(manifestBytes), signatureBytes)
    val signerCert =
        firstSignerWithCert(signedData)
            ?: return SignatureVerifyResult.Failed(TamperReason.SignerCertificateMissing)
    return finalizeVerification(signerCert, signedData, ctx)
}

private fun finalizeVerification(
    signerCert: SignerWithHolder,
    signedData: CMSSignedData,
    ctx: VerifyContext,
): SignatureVerifyResult {
    if (!verifyMath(signerCert.signer, signerCert.holder)) {
        return SignatureVerifyResult.Failed(TamperReason.ManifestSignatureMismatch)
    }
    val converter = JcaX509CertificateConverter().setProvider(BC_PROVIDER)
    val leaf = converter.getCertificate(signerCert.holder)
    val included = collectIncludedCerts(signedData, converter)
    return classifyChain(leaf, included, ctx)
}

/**
 * The BouncyCastle [`org.bouncycastle.jce.provider.BouncyCastleProvider`] instance
 * passed to every BC builder in this file. **Why an instance, not the `"BC"` provider
 * name** (wpass-4js): on-device probing confirmed AOSP ships a stripped-down
 * BouncyCastle 1.77 fork under the `"BC"` slot whose 105 services do not include
 * `Signature.SHA256withRSA`. Passing the string `"BC"` to BC builders resolves it via
 * [java.security.Security.getProvider], which returns Android's stripped instance
 * rather than the `bcprov-jdk18on:1.79` we ship in our APK; the verifier's
 * `Signature.getInstance("SHA256withRSA", "BC")` lookup then throws
 * [java.security.NoSuchAlgorithmException], the outer `runCatching` absorbs it, and
 * every Apple-signed pkpass surfaces as [SignatureVerifyResult.Failed]
 * ([TamperReason.SignatureCryptoFailure]). Holding our own instance and passing it
 * directly to provider-instance overloads of the BC builders bypasses the JCE name
 * registry entirely — the answer to "which BC are we using?" is no longer a function
 * of how the system Security registry happens to be ordered. The instance is created
 * lazily; this layer never registers it under [java.security.Security.addProvider],
 * both because none of the call sites here require registry lookup and because
 * trampling whatever the host process registered under `"BC"` would surprise other
 * code in the same process.
 *
 * **Minified consumers (wpass-at6).** Holding our own instance is necessary but not
 * sufficient in a release build: `BouncyCastleProvider`'s constructor registers its
 * algorithms by reflectively loading `<pkg>.<Alg>$Mappings` classes, which R8 strips
 * as unreferenced unless kept. `passes-core` ships the required keep rules in
 * `META-INF/proguard/passes-core.pro`; without them this provider constructs but
 * registers almost nothing, and every Apple-signed pkpass collapses onto
 * [TamperReason.SignatureCryptoFailure].
 */
private val BC_PROVIDER: BouncyCastleProvider by lazy { BouncyCastleProvider() }

/**
 * Returns the first SignerInfo paired with its certificate, or `null` if either no
 * SignerInfo is present or the SignerInfo's identifier matches no certificate in the
 * envelope's cert set.
 *
 * **Single-signer by design.** Apple's PassKit emits exactly one SignerInfo per
 * pkpass, and that is the only signer-shape this kernel claims to verify (per the
 * trust posture in the file-level docblock). A multi-signer CMS — legal in the spec
 * but never produced by Apple — would have only its first SignerInfo inspected here;
 * any later signers are silently ignored. If a future bead extends the trust claim
 * to multi-signer envelopes, this is the entry point that needs to fan out.
 */
private fun firstSignerWithCert(signedData: CMSSignedData): SignerWithHolder? {
    val signer = signedData.signerInfos.signers.firstOrNull() ?: return null
    val store = signedData.holderStore()
    val holder = store.getMatches(signer.sid.asHolderSelector()).firstOrNull()
    return holder?.let { SignerWithHolder(signer, it) }
}

private fun verifyMath(
    signer: SignerInformation,
    leafHolder: X509CertificateHolder,
): Boolean {
    val verifier =
        JcaSimpleSignerInfoVerifierBuilder()
            .setProvider(BC_PROVIDER)
            .build(leafHolder)
    return try {
        signer.verify(verifier)
    } catch (_: CMSSignerDigestMismatchException) {
        // BC raises this when the signed digest attribute doesn't match the recomputed
        // digest of `manifestBytes`. That is exactly the mismatch arm; collapsing it
        // back to a boolean keeps the policy mapping above readable.
        false
    }
}

private fun collectIncludedCerts(
    signedData: CMSSignedData,
    converter: JcaX509CertificateConverter,
): List<X509Certificate> {
    val holders = signedData.holderStore().getMatches(null)
    return holders.map(converter::getCertificate)
}

@Suppress("UNCHECKED_CAST")
private fun CMSSignedData.holderStore(): Store<X509CertificateHolder> = certificates as Store<X509CertificateHolder>

@Suppress("UNCHECKED_CAST")
private fun SignerId.asHolderSelector(): Selector<X509CertificateHolder> = this as Selector<X509CertificateHolder>

/**
 * The four-state classifier. The order of branches is the policy order from
 * decision-wlt-0tn-q1: a chain that reaches Apple is the strongest finding and
 * supersedes the lenient toggle; strict mode overrides everything else; the
 * `SelfSigned` arm is reserved for the literal "leaf only, self-issued" shape so
 * `CertChainIncomplete` stays meaningful as "extends but does not reach a known
 * root."
 */
private fun classifyChain(
    leaf: X509Certificate,
    included: List<X509Certificate>,
    ctx: VerifyContext,
): SignatureVerifyResult =
    when {
        chainReachesAnchor(leaf, included, ctx) ->
            SignatureVerifyResult.Ok(SignatureStatus.AppleVerified)
        !ctx.config.acceptSelfSignedCertificates ->
            SignatureVerifyResult.Failed(TamperReason.SignatureCryptoFailure)
        isLeafOnlyAndSelfSigned(leaf, included) ->
            SignatureVerifyResult.Ok(SignatureStatus.SelfSigned)
        else ->
            SignatureVerifyResult.Ok(SignatureStatus.CertChainIncomplete)
    }

private fun chainReachesAnchor(
    leaf: X509Certificate,
    included: List<X509Certificate>,
    ctx: VerifyContext,
): Boolean {
    if (ctx.trustAnchors.isEmpty()) return false
    val pool = (included + ctx.knownIntermediates).distinct()
    val selector = X509CertSelector().apply { certificate = leaf }
    val params =
        PKIXBuilderParameters(ctx.trustAnchors, selector).apply {
            addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(pool)))
            isRevocationEnabled = false
        }
    return runCatching { CertPathBuilder.getInstance("PKIX", BC_PROVIDER).build(params) }
        .isSuccess
}

private fun isLeafOnlyAndSelfSigned(
    leaf: X509Certificate,
    included: List<X509Certificate>,
): Boolean {
    val nonLeafIncluded = included.any { it != leaf }
    if (nonLeafIncluded) return false
    return isSelfSigned(leaf)
}

private fun isSelfSigned(cert: X509Certificate): Boolean {
    if (cert.subjectX500Principal != cert.issuerX500Principal) return false
    return runCatching { cert.verify(cert.publicKey) }.isSuccess
}

private data class SignerWithHolder(
    val signer: SignerInformation,
    val holder: X509CertificateHolder,
)

private data class VerifyContext(
    val config: ParserConfig,
    val trustAnchors: Set<TrustAnchor>,
    val knownIntermediates: Set<X509Certificate>,
)
