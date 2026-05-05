package `is`.walt.passes.core.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.MessageDigest

/**
 * Locks the bundled Apple trust anchors and known WWDR intermediates. The fingerprints
 * here mirror `certs/SECURITY-CERTS.md`; rotating an anchor must update this test in
 * lockstep so an audit reviewer can see, in code, which trust anchors `passes-core`
 * is enforcing without re-reading the keystore.
 */
class AppleTrustAnchorsTest {
    @Test
    fun trustAnchorsLoadFromResources() {
        val anchors = AppleTrustAnchors.trustAnchors()
        assertThat(anchors).isNotEmpty()
        for (anchor in anchors) {
            assertThat(anchor.trustedCert).isNotNull()
        }
    }

    @Test
    fun trustAnchorFingerprintsMatchDocumentation() {
        val fingerprints =
            AppleTrustAnchors.trustAnchors().map { sha256(it.trustedCert.encoded) }.toSet()
        assertThat(fingerprints).containsExactly(
            APPLE_ROOT_CA_SHA256,
            APPLE_ROOT_CA_G2_SHA256,
            APPLE_ROOT_CA_G3_SHA256,
        )
    }

    @Test
    fun knownIntermediatesLoadFromResources() {
        val intermediates = AppleTrustAnchors.knownIntermediates()
        val fingerprints = intermediates.map { sha256(it.encoded) }.toSet()
        assertThat(fingerprints).containsExactly(
            APPLE_WWDR_G3_SHA256,
            APPLE_WWDR_G6_SHA256,
        )
    }

    @Test
    fun knownIntermediatesAreSignedByABundledRoot() {
        // Defense-in-depth: the bundled WWDR intermediates must verify under one
        // of the bundled roots. A drift here means we're shipping an intermediate
        // whose root we don't trust, which would never chain successfully.
        val anchors = AppleTrustAnchors.trustAnchors().map { it.trustedCert }
        for (intermediate in AppleTrustAnchors.knownIntermediates()) {
            val matchingRoot =
                anchors.firstOrNull { root ->
                    runCatching { intermediate.verify(root.publicKey) }.isSuccess
                }
            assertThat(matchingRoot).isNotNull()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(":") { "%02X".format(it) }
}

private const val APPLE_ROOT_CA_SHA256 =
    "B0:B1:73:0E:CB:C7:FF:45:05:14:2C:49:F1:29:5E:6E:DA:6B:CA:ED:7E:2C:68:C5:BE:91:B5:A1:10:01:F0:24"
private const val APPLE_ROOT_CA_G2_SHA256 =
    "C2:B9:B0:42:DD:57:83:0E:7D:11:7D:AC:55:AC:8A:E1:94:07:D3:8E:41:D8:8F:32:15:BC:3A:89:04:44:A0:50"
private const val APPLE_ROOT_CA_G3_SHA256 =
    "63:34:3A:BF:B8:9A:6A:03:EB:B5:7E:9B:3F:5F:A7:BE:7C:4F:5C:75:6F:30:17:B3:A8:C4:88:C3:65:3E:91:79"
private const val APPLE_WWDR_G3_SHA256 =
    "DC:F2:18:78:C7:7F:41:98:E4:B4:61:4F:03:D6:96:D8:9C:66:C6:60:08:D4:24:4E:1B:99:16:1A:AC:91:60:1F"
private const val APPLE_WWDR_G6_SHA256 =
    "BD:D4:ED:6E:74:69:1F:0C:2B:FD:01:BE:02:96:19:7A:F1:37:9E:04:18:E2:D3:00:EF:A9:C3:BE:F6:42:CA:30"
