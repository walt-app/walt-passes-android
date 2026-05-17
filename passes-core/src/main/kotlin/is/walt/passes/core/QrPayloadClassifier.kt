package `is`.walt.passes.core

import java.net.URI
import java.net.URISyntaxException

/**
 * Classifies a QR payload string by its URI scheme so the create-time preview dialog
 * (sibling wpass-lzi.9) can warn the user about what a future scanner phone would do.
 *
 * Pure logic. No network calls, no IDN normalization, no redirect following. Never
 * rejects an input — any string maps to *some* arm of [QrPayloadKind], because the
 * structural-hazard guard (bidi controls, length caps) is upstream's job (wpass-lzi.4).
 * The classifier is deliberately conservative: when a per-scheme parse fails, the result
 * falls back to [QrPayloadKind.UnknownScheme] (or [QrPayloadKind.PlainText] when no
 * scheme matched at all) rather than guessing.
 *
 * Scheme matching is case-insensitive per RFC 3986; the matched scheme is normalized to
 * lowercase before dispatch.
 */
public object QrPayloadClassifier {
    // RFC 3986: scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ) ":"
    // Anchored at the start; the trailing `:` is required so a bare numeric string like
    // "1234567890" does NOT match as scheme "1234567890" (it starts with a digit anyway,
    // but the leading-ALPHA rule plus the `:` requirement together close the door).
    private val schemeRegex = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):")

    public fun classify(payload: String): QrPayloadKind {
        val match = schemeRegex.find(payload) ?: return QrPayloadKind.PlainText
        val scheme = match.groupValues[1].lowercase()
        // Strip the scheme + `:` from the payload using the match length, not a string
        // literal, so uppercase / mixed-case schemes like `BITCOIN:` are handled correctly.
        val afterScheme = payload.substring(match.range.last + 1)

        return when (scheme) {
            "http", "https" -> classifyHttp(scheme, payload)
            "tel" -> QrPayloadKind.Phone(afterScheme.substringBefore("?"))
            "sms", "smsto" -> QrPayloadKind.Sms(afterScheme.substringBefore("?"))
            "mailto" -> QrPayloadKind.Mailto(afterScheme.substringBefore("?"))
            "geo" -> QrPayloadKind.Geo(afterScheme)
            "wifi" -> classifyWifi(afterScheme)
            "bitcoin" -> QrPayloadKind.Bitcoin(afterScheme.substringBefore("?"))
            "ethereum" -> QrPayloadKind.Ethereum(afterScheme.substringBefore("?"))
            "magnet" -> QrPayloadKind.Magnet
            "market" -> QrPayloadKind.Market(afterScheme.removePrefix("//"))
            "intent" -> QrPayloadKind.Intent(payload)
            else -> QrPayloadKind.UnknownScheme(scheme, payload)
        }
    }

    private fun classifyHttp(
        scheme: String,
        payload: String,
    ): QrPayloadKind =
        try {
            val uri = URI(payload)
            QrPayloadKind.Url(scheme = scheme, host = uri.host, raw = payload)
        } catch (_: URISyntaxException) {
            // Malformed http(s) URI still belongs in the "URL-ish" warning bucket conceptually,
            // but the classifier has no host to surface, so it downgrades to UnknownScheme.
            // The preview dialog will show the scheme and the raw string verbatim.
            QrPayloadKind.UnknownScheme(scheme, payload)
        }

    // WIFI:T:<auth>;S:<ssid>;P:<password>;H:<hidden>;;
    // SSID and password may be escaped with `\` before `;`, `,`, `:`, `\`, `"`. Field-start
    // detection walks the body with the same escape state machine [readUntilUnescapedSemicolon]
    // uses, so an escaped `\;` inside (say) a password value cannot be mistaken for a real
    // field separator and cause an `S:` substring inside the password to surface as the SSID.
    private fun classifyWifi(body: String): QrPayloadKind {
        // [body] is everything after `WIFI:` (case-insensitive prefix already stripped by
        // the caller). Locate the `S:` field and read until the next *unescaped* `;`.
        val ssidStart = findFieldStart(body, "S:") ?: return QrPayloadKind.Wifi(ssid = null)
        val ssid = readUntilUnescapedSemicolon(body, ssidStart)
        return QrPayloadKind.Wifi(ssid = ssid)
    }

    private fun findFieldStart(
        body: String,
        key: String,
    ): Int? {
        // Walk the body honoring backslash escapes so `body[i - 1] == ';'` is only treated as
        // a field boundary when the `;` wasn't itself escaped. Without this, an SSID-key
        // substring inside an escaped value (e.g. `P:my\;S:secret;S:realnet;;`) would match
        // and surface the wrong network name in the preview.
        var i = 0
        var prevWasUnescapedSemicolon = false
        while (i < body.length) {
            val atBoundary = i == 0 || prevWasUnescapedSemicolon
            val fits = i + key.length <= body.length
            if (atBoundary && fits && body.regionMatches(i, key, 0, key.length, ignoreCase = true)) {
                return i + key.length
            }
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                prevWasUnescapedSemicolon = false
                i += 2
            } else {
                prevWasUnescapedSemicolon = c == ';'
                i++
            }
        }
        return null
    }

    private fun readUntilUnescapedSemicolon(
        body: String,
        start: Int,
    ): String {
        val out = StringBuilder()
        var i = start
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                out.append(body[i + 1])
                i += 2
                continue
            }
            if (c == ';') return out.toString()
            out.append(c)
            i++
        }
        return out.toString()
    }
}
