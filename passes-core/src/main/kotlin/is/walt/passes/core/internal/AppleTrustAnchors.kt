package `is`.walt.passes.core.internal

import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate

/**
 * Loads the Apple trust anchors and known WWDR intermediates bundled under
 * `passes-core/src/main/resources/is/walt/passes/core/internal/certs/`. The files are
 * resolved by **absolute** classpath name (see [RESOURCE_DIR]) so the lookup is
 * independent of any package renaming an R8/ProGuard consumer build applies to this
 * class. Loaded once on first access and held in [BundledCerts]. The fingerprints and
 * provenance of every certificate are documented in `certs/SECURITY-CERTS.md`; an
 * auditor or downstream security reviewer can verify the bundled set without running
 * the parser.
 *
 * **Why bundled, not platform-trusted.** The JVM truststore is mutable at runtime
 * (system properties, `keytool`, OS-level CA changes). Walt's trust claim is "this
 * pass chains to Apple," not "this pass chains to whatever JVM trust store this
 * particular device happens to ship today." Bundling pins the trust set to whatever
 * shipped with `passes-core` and lets the parser surface
 * [`is`.walt.passes.core.SignatureStatus.CertChainIncomplete] for a chain that
 * _the JVM_ might happen to trust but that does not reach an Apple root.
 *
 * **Why no network fetches.** Some PKCS#7 envelopes embed an issuer URL in their
 * Authority Information Access extension; fetching from that URL would let a
 * signature blob influence which issuers we contact. The `CertChainIncomplete` arm
 * is the explicit fallback for "leaf math validates but the chain we have on hand
 * does not reach a known root" — chasing the AIA URL would replace that surface
 * with a network footgun.
 */
internal object AppleTrustAnchors {
    // `internal`, not `private`, so AppleTrustAnchorsTest consumes these directly
    // instead of mirroring them — the test then exercises the real bundled set and
    // the real resource path, and adding a future anchor cannot leave a stale copy.
    internal val BUNDLED_TRUST_ANCHOR_FILENAMES: List<String> =
        listOf(
            "apple-root-ca.cer",
            "apple-root-ca-g2.cer",
            "apple-root-ca-g3.cer",
        )

    internal val BUNDLED_INTERMEDIATE_FILENAMES: List<String> =
        listOf(
            "apple-wwdr-g3.cer",
            "apple-wwdr-g6.cer",
        )

    private val cache: BundledCerts by lazy { loadFromResources() }

    /** The Apple roots, wrapped as JCE [TrustAnchor]s for `PKIXBuilderParameters`. */
    fun trustAnchors(): Set<TrustAnchor> = cache.trustAnchors

    /**
     * Bundled WWDR intermediates. Added to the path-builder cert store so a signature
     * blob that omits its intermediate (occasionally observed) can still chain to a
     * bundled root; never trust anchors on their own.
     */
    fun knownIntermediates(): Set<X509Certificate> = cache.intermediates

    private fun loadFromResources(): BundledCerts {
        val factory = CertificateFactory.getInstance(X509_TYPE)
        val anchors =
            BUNDLED_TRUST_ANCHOR_FILENAMES.mapTo(LinkedHashSet()) {
                TrustAnchor(loadResource(factory, it), null)
            }
        val intermediates =
            BUNDLED_INTERMEDIATE_FILENAMES.mapTo(LinkedHashSet()) {
                loadResource(factory, it)
            }
        return BundledCerts(anchors, intermediates)
    }

    private fun loadResource(
        factory: CertificateFactory,
        filename: String,
    ): X509Certificate {
        val stream =
            AppleTrustAnchors::class.java.getResourceAsStream("$RESOURCE_DIR/$filename")
                ?: error("Bundled cert missing from resources: $RESOURCE_DIR/$filename")
        return stream.use { factory.generateCertificate(it) as X509Certificate }
    }

    private data class BundledCerts(
        val trustAnchors: Set<TrustAnchor>,
        val intermediates: Set<X509Certificate>,
    )

    private const val X509_TYPE = "X.509"

    /**
     * Absolute, classpath-rooted directory for the bundled `.cer` files. MUST stay
     * absolute (leading `/`): a package-relative name is resolved by
     * [Class.getResourceAsStream] against the class's *runtime* package, and a
     * minified consumer build (walt-android, R8 `isMinifyEnabled = true`) repackages
     * `AppleTrustAnchors` to a synthetic package — a relative lookup then resolves
     * against the wrong package, returns `null`, and collapses every Apple-signed
     * pkpass onto `Failed(SignatureCryptoFailure)`. R8 relocates classes, not java
     * resources, so the absolute path is stable across minification.
     *
     * `internal` (not `private`) so `AppleTrustAnchorsTest` resolves against this
     * exact constant rather than a hand-kept copy; if this directory moves, the
     * constant moves with it and the test follows.
     */
    internal const val RESOURCE_DIR: String = "/is/walt/passes/core/internal/certs"
}
