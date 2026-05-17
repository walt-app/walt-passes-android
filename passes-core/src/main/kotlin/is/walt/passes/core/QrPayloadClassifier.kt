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
            "tel" -> QrPayloadKind.Phone(afterScheme)
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
    // SSID and password may be escaped with `\` before `;`, `,`, `:`, `\`, `"`.
    // v1 implementation: locate `;S:` (or `WIFI:S:`), then read until the next *unescaped*
    // `;`. TODO(wpass-lzi.5-followup): handle more exotic escape combinations (e.g. an SSID
    // ending in an escaped `;` immediately followed by a real one). v1 covers the common case;
    // the password is never surfaced regardless.
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
        // Match either at the very start of body or immediately after a `;`.
        var i = 0
        while (i < body.length) {
            val atStart = i == 0 || body[i - 1] == ';'
            val fits = i + key.length <= body.length
            if (atStart && fits && body.regionMatches(i, key, 0, key.length, ignoreCase = true)) {
                return i + key.length
            }
            i++
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
