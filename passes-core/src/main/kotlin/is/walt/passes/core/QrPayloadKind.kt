package `is`.walt.passes.core

/**
 * Classification of a QR code's payload string by its URI scheme (or absence thereof).
 *
 * Surface for the create-time preview dialog (sibling wpass-lzi.9): when a user enters a
 * QR payload, the consumer UI shows what a future scanner phone would interpret it as so
 * the user can confirm intent before saving the card. The classification is *advisory* —
 * Walt does not block any payload here. The downstream validator (wpass-lzi.4) rejects
 * structural hazards (bidi controls, length overruns); this classifier assumes a payload
 * that has already cleared that bar.
 *
 * Trust posture per arm:
 *
 *  - [PlainText]: nothing happens on scan beyond display. Lowest risk.
 *  - [Url]: third-party scanner phone's browser will offer to open. Phishing / drive-by
 *    download risk on the recipient device.
 *  - [Phone] / [Sms]: dialer / messaging app opens, pre-filled. Premium-rate dial fraud risk.
 *  - [Mailto]: mail app opens with recipient pre-filled.
 *  - [Geo]: maps app opens at coordinates.
 *  - [Wifi]: phone offers to join network. Note: password is parsed out of the source string
 *    but deliberately NOT carried in this kind — see [Wifi] for rationale.
 *  - [Bitcoin] / [Ethereum]: crypto wallet apps may auto-send. Address-substitution attack risk.
 *  - [Magnet]: torrent client may auto-add.
 *  - [Market]: Play Store opens a listing.
 *  - [Intent]: arbitrary Android intent URI. Most dangerous — can target named components,
 *    pass extras, bypass user-visible scheme prompts. Walt surfaces these as "Android intent"
 *    with no further dissection.
 *  - [UnknownScheme]: scheme matches RFC 3986 syntax but is not in the recognized roster.
 */
public sealed interface QrPayloadKind {
    /** No URI scheme detected. The QR holds opaque text. */
    public object PlainText : QrPayloadKind

    /**
     * `http` or `https` URL. [host] may be null even on URIs that parse cleanly (e.g. a
     * scheme-only string like `http://`). [raw] preserves the original string verbatim —
     * no normalization, no IDN conversion, no Punycode unwrapping — so the preview UI shows
     * the user exactly what a future scanner would receive.
     */
    public data class Url(val scheme: String, val host: String?, val raw: String) : QrPayloadKind

    /** `tel:` payload. [number] is the raw substring after the scheme. */
    public data class Phone(val number: String) : QrPayloadKind

    /** `sms:` payload. [number] is the raw substring after the scheme, stripped of any `?` query tail. */
    public data class Sms(val number: String) : QrPayloadKind

    /** `mailto:` payload. [address] is the raw substring after the scheme, stripped of any `?` query tail. */
    public data class Mailto(val address: String) : QrPayloadKind

    /** `geo:` payload. [coords] is the raw substring after the scheme. */
    public data class Geo(val coords: String) : QrPayloadKind

    /**
     * `WIFI:` payload. [ssid] is the network name; null if the payload omits the `S:` field.
     *
     * CRITICAL: the password field (`P:`) is deliberately NOT modeled here even though it
     * is present in the source string. The classifier output flows to a preview dialog
     * the user might screenshot, copy, or share. Surfacing the password through this
     * data class would let it leak into UI state, screenshots, accessibility tree, and
     * any telemetry that flattens kind instances. Parsing-and-dropping is a trust choice:
     * the user already knows their own wifi password if they typed this in; the scanner
     * recipient is the one who needs the password, and they get it from the QR — not from
     * Walt's preview surface.
     */
    public data class Wifi(val ssid: String?) : QrPayloadKind

    /** `bitcoin:` payment URI. [address] is the bare address, with any `?amount=...` tail stripped. */
    public data class Bitcoin(val address: String) : QrPayloadKind

    /** `ethereum:` payment URI. [address] is the bare address, with any `?value=...` tail stripped. */
    public data class Ethereum(val address: String) : QrPayloadKind

    /** `magnet:` torrent link. Raw payload not surfaced — the magnet xt hash is rarely user-meaningful. */
    public object Magnet : QrPayloadKind

    /**
     * Android Play Store URI (`market:` or `market://`). [productId] is whatever follows
     * the scheme (typically `details?id=com.example`).
     */
    public data class Market(val productId: String) : QrPayloadKind

    /**
     * Android intent URI (`intent:`). Carries [raw] — these URIs are too dangerous to
     * dissect in the preview surface. The user gets a generic "Android intent" warning
     * and the verbatim string; pretending to parse a structured shape would invite
     * mis-classification.
     */
    public data class Intent(val raw: String) : QrPayloadKind

    /** Some other RFC 3986 scheme. The user should see both [scheme] and [raw]. */
    public data class UnknownScheme(val scheme: String, val raw: String) : QrPayloadKind
}
