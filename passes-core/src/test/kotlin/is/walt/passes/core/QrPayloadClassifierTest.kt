package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behavior lock for [QrPayloadClassifier]. Every arm of [QrPayloadKind] is covered, plus
 * the security-sensitive edge cases:
 *
 *  - bare numeric strings are PlainText (not Phone)
 *  - WIFI passwords are never carried into the kind
 *  - Punycode / RTL hostnames are passed through verbatim (no IDN conversion in core)
 *  - upstream-hostile chars (whitespace, control codes) are not silently fixed up
 */
class QrPayloadClassifierTest {
    @Test
    fun emptyStringIsPlainText() {
        assertThat(QrPayloadClassifier.classify("")).isEqualTo(QrPayloadKind.PlainText)
    }

    @Test
    fun bareNumericStringIsPlainTextNotPhone() {
        // Regression: a 10-digit member number must not be classified as a tel: URI just
        // because its glyphs are dial-able. The scheme regex requires a leading ALPHA and
        // a trailing `:`, so this is locked by construction; the test pins the property.
        assertThat(QrPayloadClassifier.classify("1234567890")).isEqualTo(QrPayloadKind.PlainText)
    }

    @Test
    fun arbitraryTextWithoutSchemeIsPlainText() {
        assertThat(QrPayloadClassifier.classify("hello world")).isEqualTo(QrPayloadKind.PlainText)
    }

    @Test
    fun newlineSeparatedTextIsPlainText() {
        // "line1\nline2" has no leading scheme (no `:` follows an ALPHA-led prefix before
        // the newline), so this short-circuits at the scheme regex and lands in PlainText.
        // Documented behavior: classifier does NOT split multi-line payloads.
        assertThat(QrPayloadClassifier.classify("line1\nline2")).isEqualTo(QrPayloadKind.PlainText)
    }

    @Test
    fun httpsUrlExposesScheme_HostAndRaw() {
        val raw = "https://walt.is/example"
        val kind = QrPayloadClassifier.classify(raw)
        assertThat(kind).isEqualTo(QrPayloadKind.Url(scheme = "https", host = "walt.is", raw = raw))
    }

    @Test
    fun httpUrlExposesScheme_HostAndRaw() {
        val raw = "http://example.com/path?q=1"
        val kind = QrPayloadClassifier.classify(raw)
        assertThat(kind).isEqualTo(QrPayloadKind.Url(scheme = "http", host = "example.com", raw = raw))
    }

    @Test
    fun httpsUrlWithUserInfoUsesAuthorityHost() {
        val raw = "https://user:pass@host.example/"
        val kind = QrPayloadClassifier.classify(raw) as QrPayloadKind.Url
        assertThat(kind.host).isEqualTo("host.example")
        assertThat(kind.raw).isEqualTo(raw)
    }

    @Test
    fun httpsUrlWithMixedScriptHostnameClassifiesWithoutCrash() {
        // U+0440 CYRILLIC SMALL LETTER ER, U+03B3 GREEK SMALL LETTER GAMMA inside an
        // otherwise Latin-looking hostname. Classifier must NOT homoglyph-detect or
        // normalize; that's the preview UI's job to surface visibly.
        //
        // JDK's java.net.URI considers non-ASCII characters illegal in the host portion of
        // an absolute hierarchical URI: the parse succeeds but `host` comes back as null.
        // Walt's classifier surfaces what the JDK gives us; downstream UI uses [raw] (which
        // contains the original glyphs) to render what the user actually typed. The trust
        // claim is preserved: the user sees their string verbatim, not a sanitized version.
        val raw = "https://паγpal.com/"
        val kind = QrPayloadClassifier.classify(raw)
        assertThat(kind).isInstanceOf(QrPayloadKind.Url::class.java)
        val url = kind as QrPayloadKind.Url
        assertThat(url.scheme).isEqualTo("https")
        assertThat(url.raw).isEqualTo(raw)
    }

    @Test
    fun punycodeHostnameIsLeftVerbatim() {
        val raw = "https://xn--80ak6aa92e.com/"
        val kind = QrPayloadClassifier.classify(raw) as QrPayloadKind.Url
        assertThat(kind.host).isEqualTo("xn--80ak6aa92e.com")
        assertThat(kind.raw).isEqualTo(raw)
    }

    @Test
    fun trailingWhitespaceIsNotSilentlyFixed() {
        // Upstream validator rejects whitespace; if it slipped through, the raw payload
        // must survive verbatim into the preview so the user sees what would be encoded.
        val raw = "https://attacker.example/  "
        val kind = QrPayloadClassifier.classify(raw)
        // URI parsing may or may not accept the trailing whitespace depending on JDK;
        // either way, raw must be preserved if Url is returned.
        if (kind is QrPayloadKind.Url) {
            assertThat(kind.raw).isEqualTo(raw)
        } else {
            assertThat(kind).isInstanceOf(QrPayloadKind.UnknownScheme::class.java)
            assertThat((kind as QrPayloadKind.UnknownScheme).raw).isEqualTo(raw)
        }
    }

    @Test
    fun telUriIsPhone() {
        assertThat(QrPayloadClassifier.classify("tel:+15551234567"))
            .isEqualTo(QrPayloadKind.Phone("+15551234567"))
    }

    @Test
    fun telUriStripsQueryTail() {
        // Mirrors the sms/mailto/bitcoin behavior so the preview dialog renders a clean
        // dialable number rather than `+155?foo`. `tel:` per RFC 3966 uses `;` for params,
        // not `?`, but consistency with the other "phone-like" arms wins over RFC purity.
        assertThat(QrPayloadClassifier.classify("tel:+15551234567?foo=bar"))
            .isEqualTo(QrPayloadKind.Phone("+15551234567"))
    }

    @Test
    fun smsUriStripsQueryTail() {
        assertThat(QrPayloadClassifier.classify("sms:+15551234567?body=hi"))
            .isEqualTo(QrPayloadKind.Sms("+15551234567"))
    }

    @Test
    fun mailtoUriStripsQueryTail() {
        assertThat(QrPayloadClassifier.classify("mailto:a@b.com?subject=hello"))
            .isEqualTo(QrPayloadKind.Mailto("a@b.com"))
    }

    @Test
    fun geoUriExposesCoords() {
        assertThat(QrPayloadClassifier.classify("geo:37.7749,-122.4194"))
            .isEqualTo(QrPayloadKind.Geo("37.7749,-122.4194"))
    }

    @Test
    fun wifiUriExposesSsidButNeverPassword() {
        val payload = "WIFI:T:WPA;S:my-network;P:hunter2;;"
        val kind = QrPayloadClassifier.classify(payload) as QrPayloadKind.Wifi
        assertThat(kind.ssid).isEqualTo("my-network")
        // Belt-and-suspenders: no field on the result type carries the password substring.
        // The Wifi data class has exactly one property (ssid); this assertion locks the
        // shape against a future "let's add a password field for convenience" PR.
        assertThat(kind.toString()).doesNotContain("hunter2")
    }

    @Test
    fun wifiUriWithoutSsidReturnsNullSsid() {
        val payload = "WIFI:T:nopass;;"
        assertThat(QrPayloadClassifier.classify(payload)).isEqualTo(QrPayloadKind.Wifi(ssid = null))
    }

    @Test
    fun wifiUriWithEscapedSemicolonInSsidUnescapes() {
        // SSID is `weird;name`. Escaped `\;` should produce a literal `;` and not terminate
        // the field.
        val payload = """WIFI:S:weird\;name;T:WPA;P:secret;;"""
        val kind = QrPayloadClassifier.classify(payload) as QrPayloadKind.Wifi
        assertThat(kind.ssid).isEqualTo("weird;name")
        assertThat(kind.toString()).doesNotContain("secret")
    }

    @Test
    fun wifiUriWithFakeSsidKeyInsideEscapedPasswordDoesNotSpoof() {
        // Field-boundary detection must honor escapes: the `\;` inside the password value
        // here is NOT a real field separator, so the `S:` substring following it is NOT a
        // real SSID-field key. The validator must read the real `S:realnet` field, not the
        // decoy `S:secret` substring sitting inside the password's literal text.
        val payload = """WIFI:T:WPA;P:my\;S:secret;S:realnet;;"""
        val kind = QrPayloadClassifier.classify(payload) as QrPayloadKind.Wifi
        assertThat(kind.ssid).isEqualTo("realnet")
        // And the password substring still never surfaces, escape parsing aside.
        assertThat(kind.toString()).doesNotContain("my")
    }

    @Test
    fun bitcoinUriStripsAmountTail() {
        assertThat(QrPayloadClassifier.classify("bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?amount=0.5"))
            .isEqualTo(QrPayloadKind.Bitcoin("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"))
    }

    @Test
    fun ethereumUriStripsValueTail() {
        assertThat(QrPayloadClassifier.classify("ethereum:0xabc?value=1"))
            .isEqualTo(QrPayloadKind.Ethereum("0xabc"))
    }

    @Test
    fun magnetUriClassifies() {
        assertThat(QrPayloadClassifier.classify("magnet:?xt=urn:btih:abc"))
            .isEqualTo(QrPayloadKind.Magnet)
    }

    @Test
    fun marketUriWithAuthoritySliceExposesProductId() {
        assertThat(QrPayloadClassifier.classify("market://details?id=com.example.app"))
            .isEqualTo(QrPayloadKind.Market("details?id=com.example.app"))
    }

    @Test
    fun marketUriWithoutAuthoritySliceExposesProductId() {
        assertThat(QrPayloadClassifier.classify("market:details?id=com.example.app"))
            .isEqualTo(QrPayloadKind.Market("details?id=com.example.app"))
    }

    @Test
    fun intentUriKeepsRawString() {
        val raw = "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end"
        assertThat(QrPayloadClassifier.classify(raw)).isEqualTo(QrPayloadKind.Intent(raw))
    }

    @Test
    fun uppercaseSchemeIsNormalizedBeforeDispatch() {
        // Schemes are case-insensitive per RFC 3986. The kind dispatches on lowercase,
        // and the Bitcoin arm carries the address verbatim from after the scheme.
        val payload = "BITCOIN:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
        assertThat(QrPayloadClassifier.classify(payload))
            .isEqualTo(QrPayloadKind.Bitcoin("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"))
    }

    @Test
    fun unrecognizedSchemeReturnsUnknownScheme() {
        val raw = "foo://bar"
        assertThat(QrPayloadClassifier.classify(raw))
            .isEqualTo(QrPayloadKind.UnknownScheme(scheme = "foo", raw = raw))
    }

    @Test
    fun unknownSchemeNormalizesToLowercase() {
        val raw = "FOO://bar"
        assertThat(QrPayloadClassifier.classify(raw))
            .isEqualTo(QrPayloadKind.UnknownScheme(scheme = "foo", raw = raw))
    }

    @Test
    fun allKindArmsAreReachableViaWhen() {
        // Drift detection: removing an arm breaks compilation in [branchName] before it
        // breaks any downstream `when`. Mirrors PublicApiSurfaceTest conventions.
        val kinds: List<QrPayloadKind> =
            listOf(
                QrPayloadKind.PlainText,
                QrPayloadKind.Url(scheme = "https", host = "x", raw = "https://x"),
                QrPayloadKind.Phone("1"),
                QrPayloadKind.Sms("1"),
                QrPayloadKind.Mailto("a@b"),
                QrPayloadKind.Geo("0,0"),
                QrPayloadKind.Wifi(ssid = null),
                QrPayloadKind.Bitcoin("a"),
                QrPayloadKind.Ethereum("0x"),
                QrPayloadKind.Magnet,
                QrPayloadKind.Market("x"),
                QrPayloadKind.Intent("intent://x"),
                QrPayloadKind.UnknownScheme("foo", "foo:x"),
            )
        val branches = kinds.map(::branchName).toSet()
        assertThat(branches).hasSize(kinds.size)
    }

    private fun branchName(k: QrPayloadKind): String =
        when (k) {
            QrPayloadKind.PlainText -> "plain"
            is QrPayloadKind.Url -> "url"
            is QrPayloadKind.Phone -> "phone"
            is QrPayloadKind.Sms -> "sms"
            is QrPayloadKind.Mailto -> "mailto"
            is QrPayloadKind.Geo -> "geo"
            is QrPayloadKind.Wifi -> "wifi"
            is QrPayloadKind.Bitcoin -> "btc"
            is QrPayloadKind.Ethereum -> "eth"
            QrPayloadKind.Magnet -> "magnet"
            is QrPayloadKind.Market -> "market"
            is QrPayloadKind.Intent -> "intent"
            is QrPayloadKind.UnknownScheme -> "unknown"
        }
}
