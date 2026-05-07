package `is`.walt.passes.core.internal

import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.core.TamperReason
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.BeforeClass
import org.junit.Test
import java.security.Provider
import java.security.Security
import java.security.cert.TrustAnchor

/**
 * Behavior tests for [verifySignature]. Every fixture is built in-memory at test
 * time so a reviewer can read each test top-to-bottom and see exactly which arm of
 * [SignatureVerifyResult] is being exercised. The test "Apple" trust anchor is a
 * synthesized CA — Apple's real WWDR private key is unavailable, so the production
 * `verifySignature(sigBytes, manifestBytes, config)` entrypoint is exercised
 * indirectly through [verifySignatureAgainstAnchorsForTesting], which lets the test substitute its
 * own anchor for the bundled set.
 *
 * The wider "the bundled Apple anchors load and chain shape" assertion lives in
 * [AppleTrustAnchorsTest]; together the two suites cover both sides without
 * needing Apple's signing key.
 */
class SignatureVerifierTest {
    @Test
    fun appleVerifiedWhenChainTerminatesAtBundledRoot() {
        val rootKey = newRsaKeyPair()
        val rootCert = selfSignedCertificate(rootKey, "CN=Test Apple Root")
        val leafKey = newRsaKeyPair()
        val leafCert = signLeafCertificate(leafKey.public, rootKey, rootCert, "CN=Test Apple Leaf")
        val manifest = "{\"pass.json\":\"abcd\"}".toByteArray()
        val signature = cmsDetachedSignature(manifest, leafCert, leafKey.private, listOf(leafCert, rootCert))

        val result =
            verifySignatureAgainstAnchorsForTesting(
                signatureBytes = signature,
                manifestBytes = manifest,
                config = ParserConfig(),
                trustAnchors = setOf(TrustAnchor(rootCert, null)),
                knownIntermediates = emptySet(),
            )

        assertOk(result, SignatureStatus.AppleVerified)
    }

    @Test
    fun strictAcceptsAppleVerified() {
        // Lock the classifier branch ordering: ParserConfig.Strict must NOT block a
        // chain that reaches a bundled anchor — strict mode rejects unsigned and
        // self-signed inputs only. Without this test, a future reorder of the four-
        // arm `when` in classifyChain that puts the strict-rejection branch ahead of
        // chainReachesAnchor would silently fail every Apple-signed pass under
        // strict mode.
        val rootKey = newRsaKeyPair()
        val rootCert = selfSignedCertificate(rootKey, "CN=Test Apple Root")
        val leafKey = newRsaKeyPair()
        val leafCert = signLeafCertificate(leafKey.public, rootKey, rootCert, "CN=Test Apple Leaf")
        val manifest = "{\"pass.json\":\"abcd\"}".toByteArray()
        val signature = cmsDetachedSignature(manifest, leafCert, leafKey.private, listOf(leafCert, rootCert))

        val result =
            verifySignatureAgainstAnchorsForTesting(
                signatureBytes = signature,
                manifestBytes = manifest,
                config = ParserConfig.Strict,
                trustAnchors = setOf(TrustAnchor(rootCert, null)),
                knownIntermediates = emptySet(),
            )

        assertOk(result, SignatureStatus.AppleVerified)
    }

    @Test
    fun selfSignedLeafLeniently() {
        val key = newRsaKeyPair()
        val leaf = selfSignedCertificate(key, "CN=Self Signed Leaf")
        val manifest = "{}".toByteArray()
        // The signature blob carries only the self-signed leaf; no intermediates.
        val signature = cmsDetachedSignature(manifest, leaf, key.private, listOf(leaf))

        val result = runWithFakeAnchor(signature, manifest, ParserConfig())

        assertOk(result, SignatureStatus.SelfSigned)
    }

    @Test
    fun certChainIncompleteWhenIntermediatePresentButNoKnownRoot() {
        // Two-level chain that does NOT terminate at our test anchor — the test
        // anchor here is some unrelated CA. The chain extends (leaf + intermediate
        // signed by an out-of-band root) but does not reach the trusted set.
        val unrelatedKey = newRsaKeyPair()
        val unrelatedAnchor = selfSignedCertificate(unrelatedKey, "CN=Unrelated Anchor")
        val intermediateKey = newRsaKeyPair()
        val intermediate = selfSignedCertificate(intermediateKey, "CN=Stranger Intermediate")
        val leafKey = newRsaKeyPair()
        val leaf = signLeafCertificate(leafKey.public, intermediateKey, intermediate, "CN=Stranger Leaf")
        val manifest = "{\"k\":\"v\"}".toByteArray()
        val signature = cmsDetachedSignature(manifest, leaf, leafKey.private, listOf(leaf, intermediate))

        val result =
            verifySignatureAgainstAnchorsForTesting(
                signatureBytes = signature,
                manifestBytes = manifest,
                config = ParserConfig(),
                trustAnchors = setOf(TrustAnchor(unrelatedAnchor, null)),
                knownIntermediates = emptySet(),
            )

        assertOk(result, SignatureStatus.CertChainIncomplete)
    }

    @Test
    fun strictRejectsSelfSigned() {
        val key = newRsaKeyPair()
        val leaf = selfSignedCertificate(key, "CN=Self Signed Leaf")
        val manifest = "{}".toByteArray()
        val signature = cmsDetachedSignature(manifest, leaf, key.private, listOf(leaf))

        val result = runWithFakeAnchor(signature, manifest, ParserConfig.Strict)

        assertFailed(result, TamperReason.SignatureCryptoFailure)
    }

    @Test
    fun strictRejectsCertChainIncomplete() {
        val unrelatedKey = newRsaKeyPair()
        val unrelatedAnchor = selfSignedCertificate(unrelatedKey, "CN=Unrelated Anchor")
        val intermediateKey = newRsaKeyPair()
        val intermediate = selfSignedCertificate(intermediateKey, "CN=Intermediate")
        val leafKey = newRsaKeyPair()
        val leaf = signLeafCertificate(leafKey.public, intermediateKey, intermediate, "CN=Leaf")
        val manifest = "{}".toByteArray()
        val signature = cmsDetachedSignature(manifest, leaf, leafKey.private, listOf(leaf, intermediate))

        val result =
            verifySignatureAgainstAnchorsForTesting(
                signatureBytes = signature,
                manifestBytes = manifest,
                config = ParserConfig.Strict,
                trustAnchors = setOf(TrustAnchor(unrelatedAnchor, null)),
                knownIntermediates = emptySet(),
            )

        assertFailed(result, TamperReason.SignatureCryptoFailure)
    }

    @Test
    fun manifestSignatureMismatchWhenManifestBytesTamperedByOneByte() {
        val key = newRsaKeyPair()
        val leaf = selfSignedCertificate(key, "CN=Self")
        val original = "{\"pass.json\":\"abcd\"}".toByteArray()
        val signature = cmsDetachedSignature(original, leaf, key.private, listOf(leaf))
        // Mutate exactly one byte.
        val tampered = original.copyOf().also { it[1] = 0x20 }

        val result = runWithFakeAnchor(signature, tampered, ParserConfig())

        assertFailed(result, TamperReason.ManifestSignatureMismatch)
    }

    @Test
    fun signerCertificateMissingWhenSignerIdMatchesNoCertInEnvelope() {
        // Mint two leaves under the same fake CA. Sign with leafA but embed only leafB
        // in the CMS envelope's certificate set. The SignerInfo's SignerIdentifier then
        // points at leafA (whichever flavor — IssuerAndSerialNumber here), but
        // holderStore().getMatches returns empty, so firstSignerWithCert returns null
        // and the verifier surfaces TamperReason.SignerCertificateMissing — separately
        // from SignatureCryptoFailure (a structural-corruption signal). This is the
        // behavior the wpass-4js post-mortem called for: distinguishing "envelope
        // parses but signer cert is absent" from "envelope is garbage" in telemetry.
        val rootKey = newRsaKeyPair()
        val rootCert = selfSignedCertificate(rootKey, "CN=Root")
        val leafAKey = newRsaKeyPair()
        val leafA = signLeafCertificate(leafAKey.public, rootKey, rootCert, "CN=Leaf A (signs)")
        val leafBKey = newRsaKeyPair()
        val leafB = signLeafCertificate(leafBKey.public, rootKey, rootCert, "CN=Leaf B (in envelope)")
        val manifest = "{\"pass.json\":\"abcd\"}".toByteArray()
        val signature = cmsDetachedSignature(manifest, leafA, leafAKey.private, listOf(leafB, rootCert))

        val result = runWithFakeAnchor(signature, manifest, ParserConfig())

        assertFailed(result, TamperReason.SignerCertificateMissing)
    }

    @Test
    fun signatureCryptoFailureForGarbageBlob() {
        val garbage = ByteArray(64) { it.toByte() }
        val manifest = "{}".toByteArray()

        val result = runWithFakeAnchor(garbage, manifest, ParserConfig())

        assertFailed(result, TamperReason.SignatureCryptoFailure)
    }

    @Test
    fun signatureCryptoFailureForEmptyBlob() {
        val result = runWithFakeAnchor(ByteArray(0), "{}".toByteArray(), ParserConfig())
        assertFailed(result, TamperReason.SignatureCryptoFailure)
    }

    @Test
    fun productionEntrypointRoutesGarbageToCryptoFailure() {
        // Exercises the `verifySignature(sig, manifest, config)` entrypoint that
        // loads bundled Apple anchors. Garbage input never depends on which anchors
        // are loaded, so this is the only assertion safe to make against the
        // production entrypoint without Apple's private key.
        val result =
            verifySignature(
                signatureBytes = ByteArray(8),
                manifestBytes = "{}".toByteArray(),
                config = ParserConfig(),
            )
        assertFailed(result, TamperReason.SignatureCryptoFailure)
    }

    @Test
    fun appleVerifiedBeatsLeafThatIsAlsoSelfSigned() {
        // Self-issued root and a leaf signed by it. The leaf is NOT self-signed
        // (issuer=root, subject=leaf), but a misclassifier that checked self-
        // signedness before trying chain validation would surface this as
        // SelfSigned. The classifier must try the anchor first.
        val rootKey = newRsaKeyPair()
        val root = selfSignedCertificate(rootKey, "CN=Test Apple Root")
        val leafKey = newRsaKeyPair()
        val leaf = signLeafCertificate(leafKey.public, rootKey, root, "CN=Apple Leaf")
        val manifest = "x".toByteArray()
        val signature = cmsDetachedSignature(manifest, leaf, leafKey.private, listOf(leaf, root))

        val result =
            verifySignatureAgainstAnchorsForTesting(
                signature,
                manifest,
                ParserConfig(),
                setOf(TrustAnchor(root, null)),
                emptySet(),
            )
        assertOk(result, SignatureStatus.AppleVerified)
    }

    @Test
    fun realAppleSignedPkpassVerifiesEvenWhenBcSlotHoldsAStrippedProvider() {
        // Regression coverage for wpass-4js, simulating the Android shape on JVM.
        // Android ships a stripped-down BouncyCastle under the "BC" provider name; it
        // omits the CMS / PKCS#7 packages we need. Before the fix, SignatureVerifier
        // looked BC up by name (`setProvider("BC")`), which resolved to whatever the
        // host process had registered under "BC" — Android's stripped instance, not
        // our bundled bcprov-jdk18on:1.79. CMS verification then failed and every
        // Apple-signed pkpass surfaced as Tampered. The fix is to hold a
        // BouncyCastleProvider INSTANCE and pass it to BC builders directly. This
        // test parks a non-BC provider in the "BC" slot before invoking the verifier;
        // a name-lookup-based implementation would resolve to the fake and fail. We
        // restore the original "BC" registration in a finally so downstream tests in
        // the same JVM are unaffected.
        val savedBc = Security.getProvider("BC")
        Security.removeProvider("BC")
        Security.addProvider(StrippedFakeBcProvider())
        try {
            val (manifest, signature) = loadAppleSignedFixture()

            val result = verifySignature(signature, manifest, ParserConfig())

            assertOk(result, SignatureStatus.AppleVerified)
        } finally {
            Security.removeProvider("BC")
            // ensureBouncyCastleProvider() in @BeforeClass already added BC for the
            // suite; restore that exact instance so other tests see the registry they
            // expect.
            if (savedBc != null) {
                Security.addProvider(savedBc)
            } else {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    /**
     * Stand-in for the Android-shipped BC: registers under the same `"BC"` name slot
     * but advertises no algorithms. A name-lookup implementation that resolves
     * `setProvider("BC")` against [Security.getProvider] picks this up and fails fast;
     * a fixed implementation that holds its own [BouncyCastleProvider] reference does
     * not see this provider at all.
     */
    private class StrippedFakeBcProvider : Provider("BC", 1.0, "Fake stripped BC for wpass-4js regression test")

    @Test
    fun realAppleSignedPkpassFixtureVerifies() {
        // Regression coverage for wpass-4js. The fixture is the manifest + signature
        // pair from a real Apple-signed pkpass (Tixly Chroniques ticket; Pass Type ID
        // pass.com.tixly, team A6DLLVNVR7, signed under WWDR G4). It's the smallest
        // possible end-to-end check against the bundled Apple anchor set: the synthetic
        // SKI test uses `verifySignatureAgainstAnchorsForTesting`, which lets us invent
        // our own root, but only the production `verifySignature(...)` entrypoint
        // exercises both the SKI signer-ID path AND the Apple Root CA bundling path
        // simultaneously — which is the combination that broke in the field.
        val (manifest, signature) = loadAppleSignedFixture()

        val result = verifySignature(signature, manifest, ParserConfig())

        assertOk(result, SignatureStatus.AppleVerified)
    }

    private fun loadAppleSignedFixture(): Pair<ByteArray, ByteArray> {
        val cls = SignatureVerifierTest::class.java
        val manifest =
            cls.getResourceAsStream("fixtures/apple-signed/manifest.json")?.use { it.readBytes() }
                ?: error("Fixture missing: fixtures/apple-signed/manifest.json")
        val signature =
            cls.getResourceAsStream("fixtures/apple-signed/signature")?.use { it.readBytes() }
                ?: error("Fixture missing: fixtures/apple-signed/signature")
        return manifest to signature
    }

    @Test
    fun appleVerifiedWhenSignerIdIsSubjectKeyIdentifier() {
        // Regression coverage for wpass-4js. Apple's PassKit signer references the leaf
        // by SubjectKeyIdentifier (every Pass Type ID certificate Apple issues uses SKI,
        // never IssuerAndSerialNumber), and prior to the fix `firstSignerWithCert`
        // returned null for this signer-ID flavor — collapsing every Apple-signed pkpass
        // to ParseResult.Tampered. The synthetic chain here is structurally what Apple
        // ships: leaf with an SKI extension, signer.sid pointing at that SKI.
        val rootKey = newRsaKeyPair()
        val rootCert = selfSignedCertificate(rootKey, "CN=Test Apple Root")
        val leafKey = newRsaKeyPair()
        val leafCert = signLeafCertificate(leafKey.public, rootKey, rootCert, "CN=Test Apple Leaf")
        val manifest = "{\"pass.json\":\"abcd\"}".toByteArray()
        val signature = cmsDetachedSignatureWithSki(manifest, leafCert, leafKey.private, listOf(leafCert, rootCert))

        val result =
            verifySignatureAgainstAnchorsForTesting(
                signatureBytes = signature,
                manifestBytes = manifest,
                config = ParserConfig(),
                trustAnchors = setOf(TrustAnchor(rootCert, null)),
                knownIntermediates = emptySet(),
            )

        assertOk(result, SignatureStatus.AppleVerified)
    }

    @Test
    fun manifestSignatureMismatchForSkiSignerWithTamperedManifest() {
        // SKI-signer-ID counterpart to manifestSignatureMismatchWhenManifestBytesTamperedByOneByte.
        // Once firstSignerWithCert returns the leaf for an SKI sid, the digest path must
        // still classify a tampered manifest correctly — without this case a regression
        // could trade one wrong arm (Tampered for valid passes) for another (Ok for
        // tampered passes).
        val key = newRsaKeyPair()
        val leaf = selfSignedCertificate(key, "CN=Self")
        val original = "{\"pass.json\":\"abcd\"}".toByteArray()
        val signature = cmsDetachedSignatureWithSki(original, leaf, key.private, listOf(leaf))
        val tampered = original.copyOf().also { it[1] = 0x20 }

        val result = runWithFakeAnchor(signature, tampered, ParserConfig())

        assertFailed(result, TamperReason.ManifestSignatureMismatch)
    }

    @Test
    fun knownIntermediateLetsBlobOmitItAndStillReachAnchor() {
        // The signature blob includes only the leaf, not the intermediate. The
        // bundled `knownIntermediates` set fills the gap so the chain still
        // resolves to the trust anchor. This exercises the WWDR-G6-bundled path.
        val rootKey = newRsaKeyPair()
        val root = selfSignedCertificate(rootKey, "CN=Test Apple Root")
        val intermediateKey = newRsaKeyPair()
        val intermediate = signLeafCertificate(intermediateKey.public, rootKey, root, "CN=Test WWDR", isCa = true)
        val leafKey = newRsaKeyPair()
        val leaf = signLeafCertificate(leafKey.public, intermediateKey, intermediate, "CN=Test Apple Leaf")
        val manifest = "{\"k\":\"v\"}".toByteArray()
        val signature = cmsDetachedSignature(manifest, leaf, leafKey.private, listOf(leaf))

        val result =
            verifySignatureAgainstAnchorsForTesting(
                signature,
                manifest,
                ParserConfig(),
                setOf(TrustAnchor(root, null)),
                setOf(intermediate),
            )

        assertOk(result, SignatureStatus.AppleVerified)
    }

    private fun runWithFakeAnchor(
        signature: ByteArray,
        manifest: ByteArray,
        config: ParserConfig,
    ): SignatureVerifyResult {
        val anchorKey = newRsaKeyPair()
        val anchor = selfSignedCertificate(anchorKey, "CN=Unrelated Anchor")
        return verifySignatureAgainstAnchorsForTesting(
            signature,
            manifest,
            config,
            setOf(TrustAnchor(anchor, null)),
            emptySet(),
        )
    }

    private fun assertOk(
        result: SignatureVerifyResult,
        expected: SignatureStatus,
    ) {
        assertThat(result).isInstanceOf(SignatureVerifyResult.Ok::class.java)
        Truth.assertWithMessage("expected Ok($expected)")
            .that((result as SignatureVerifyResult.Ok).status)
            .isEqualTo(expected)
    }

    private fun assertFailed(
        result: SignatureVerifyResult,
        expected: TamperReason,
    ) {
        assertThat(result).isInstanceOf(SignatureVerifyResult.Failed::class.java)
        Truth.assertWithMessage("expected Failed($expected)")
            .that((result as SignatureVerifyResult.Failed).reason)
            .isEqualTo(expected)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun installBouncyCastle() {
            ensureBouncyCastleProvider()
        }
    }
}
