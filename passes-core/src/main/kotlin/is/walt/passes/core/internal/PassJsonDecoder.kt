package `is`.walt.passes.core.internal

import `is`.walt.passes.core.Barcode
import `is`.walt.passes.core.BarcodeFormat
import `is`.walt.passes.core.ColorValue
import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassColors
import `is`.walt.passes.core.PassField
import `is`.walt.passes.core.PassFields
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.TextAlignment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.time.OffsetDateTime

/**
 * Deserializes `pass.json` from the entries map produced by [extractSafely] into the
 * subset of the public [Pass] model that pass.json is responsible for. Pure function:
 * no I/O, no mutation of [entries], no allocation outside the [Pass] it returns.
 *
 * The returned [Pass] has `images = emptyMap()` and `locales = emptyMap()` — those come
 * from other entries in the archive (image bytes and `<locale>.lproj/pass.strings`
 * respectively) and are wired in by the parser-glue bead that owns image bounding and
 * `.strings` parsing. Surfacing them as empty here keeps this slice focused on the
 * pass.json layer; the parser-glue bead will `pass.copy(images = ..., locales = ...)`.
 *
 * **Defense-in-depth ordering.** The function runs three layers, in order, and the
 * earliest-firing arm wins:
 *
 *  1. Missing-entry check — fail fast with [PassJsonFailure.Missing]; nothing else can
 *     run if the bytes are absent.
 *  2. Pre-pass tokenizer ([enforceJsonLimits]) — enforces
 *     [ParserConfig.maxJsonDepth] / [ParserConfig.maxJsonStringBytes] before
 *     kotlinx.serialization gets the bytes. kotlinx does not enforce these natively,
 *     and post-validating a parsed tree is too late: a 1 GiB string or a 10 000-deep
 *     nesting has already allocated by the time the tree exists.
 *  3. Strict kotlinx parse + structural mapping — `isLenient = false`,
 *     `ignoreUnknownKeys = true`. Lenient mode is off so unquoted keys / single quotes
 *     fail as [PassJsonFailure.InvalidJson]; unknown-keys is on so PKPASS spec
 *     additions (Apple has added top-level keys over the format's lifetime) do not
 *     break consumers.
 *
 * **Dangerous fields.** `nfc`, `webServiceURL`, `authenticationToken`, `personalization`
 * and `personalizationToken` are intentionally not surfaced on the returned [Pass] per
 * decision-wlt-0tn-q1: parsed (so they cannot trip strict-mode failures) but discarded
 * (so consumers cannot accidentally render or transmit them). The "parsed and dropped"
 * shape is structural — there are simply no fields on [Pass] for them — rather than a
 * runtime stripping pass, which keeps the trust claim auditable from the data model
 * alone.
 *
 * **Unknown style key.** When `pass.json` is structurally valid but declares no top-
 * level pass style this parser implements (a hypothetical future `ssoPass` etc.), the
 * function returns [PassJsonFailure.UnknownPassStyle] with the first non-allowlisted
 * object-valued top-level key. The allowlist of "known non-style object-valued keys"
 * ([KNOWN_NON_STYLE_OBJECT_KEYS]) is intentionally conservative — only those keys we
 * have observed in real PKPASS payloads or the Apple spec — so a new metadata key
 * spec'd by Apple in future could initially surface as `UnknownPassStyle`. That is
 * acceptable: a false positive surfaces as "this parser is too old"; a false negative
 * would lose a future style entirely.
 */
internal fun decodePassJson(
    entries: Map<String, ByteArray>,
    config: ParserConfig,
): PassJsonDecodeResult {
    val bytes =
        entries[PASS_JSON_FILE_NAME]
            ?: return PassJsonDecodeResult.Failed(PassJsonFailure.Missing)
    val limitFailure = enforceJsonLimits(bytes, config)
    return limitFailure?.let { PassJsonDecodeResult.Failed(it) }
        ?: parseAndMap(bytes)
}

private fun parseAndMap(bytes: ByteArray): PassJsonDecodeResult {
    val root =
        runCatching { PASS_JSON.parseToJsonElement(bytes.decodeToString()) as? JsonObject }
            .getOrNull()
            ?: return PassJsonDecodeResult.Failed(PassJsonFailure.InvalidJson)
    val versionFailure = checkVersion(root)
    return versionFailure?.let { PassJsonDecodeResult.Failed(it) }
        ?: resolveAndAssemble(root)
}

private fun checkVersion(root: JsonObject): PassJsonFailure? {
    val version = (root[FIELD_FORMAT_VERSION] as? JsonPrimitive)?.intOrNull
    return if (version == PKPASS_FORMAT_VERSION) null else PassJsonFailure.UnknownFormatVersion(version ?: 0)
}

private fun resolveAndAssemble(root: JsonObject): PassJsonDecodeResult =
    when (val style = resolveStyle(root)) {
        is StyleResolution.Multiple -> PassJsonDecodeResult.Failed(PassJsonFailure.InvalidShape)
        is StyleResolution.Unknown -> PassJsonDecodeResult.Failed(PassJsonFailure.UnknownPassStyle(style.raw))
        is StyleResolution.Found ->
            assemblePass(root, style)
                ?.let { PassJsonDecodeResult.Ok(it) }
                ?: PassJsonDecodeResult.Failed(PassJsonFailure.InvalidShape)
    }

private fun assemblePass(
    root: JsonObject,
    style: StyleResolution.Found,
): Pass? {
    val serial = root.stringFieldOrNull(FIELD_SERIAL_NUMBER)
    val description = root.stringFieldOrNull(FIELD_DESCRIPTION)
    val organization = root.stringFieldOrNull(FIELD_ORGANIZATION_NAME)
    val expiration = parseExpiration(root[FIELD_EXPIRATION_DATE])
    if (expiration is ExpirationParse.Malformed) return null
    // Three-condition guard kept under detekt's complexity threshold by splitting the
    // expiration check above; the smart-cast branch below is single-expression so
    // ReturnCount stays at 2.
    return if (serial != null && description != null && organization != null) {
        Pass(
            type = style.type,
            serialNumber = serial,
            description = description,
            organizationName = organization,
            expirationDate = (expiration as? ExpirationParse.Ok)?.instant,
            voided = (root[FIELD_VOIDED] as? JsonPrimitive)?.booleanOrNull ?: false,
            colors =
                PassColors(
                    foreground = parseColor(root.stringFieldOrNull(FIELD_FOREGROUND_COLOR)),
                    background = parseColor(root.stringFieldOrNull(FIELD_BACKGROUND_COLOR)),
                    label = parseColor(root.stringFieldOrNull(FIELD_LABEL_COLOR)),
                ),
            frontFields =
                PassFields(
                    header = parseFieldList(style.node[FIELD_HEADER_FIELDS]),
                    primary = parseFieldList(style.node[FIELD_PRIMARY_FIELDS]),
                    secondary = parseFieldList(style.node[FIELD_SECONDARY_FIELDS]),
                    auxiliary = parseFieldList(style.node[FIELD_AUXILIARY_FIELDS]),
                ),
            backFields = parseFieldList(style.node[FIELD_BACK_FIELDS]),
            barcode = parseBarcode(root),
            images = emptyMap(),
            locales = emptyMap(),
        )
    } else {
        null
    }
}

/**
 * Returns the resolved style with its sub-object, or a discriminated reason it could
 * not be resolved. Keeping the four-state outcome (Found / Multiple / Unknown-with-hint
 * / no-hint) in one type lets the caller map cleanly to the right [PassJsonFailure]
 * arm without re-scanning the JSON.
 *
 * In the no-style branch, the first object-valued top-level key not on the known-style
 * or known-non-style allowlist is surfaced as the unknown-style hint. `firstNotNullOf`
 * over the entries gives the first-in-iteration-order semantics required for stable
 * UnknownPassStyle reporting and avoids a multi-jump loop body.
 */
private fun resolveStyle(root: JsonObject): StyleResolution {
    val present = STYLE_KEY_TO_TYPE.entries.filter { root[it.key] is JsonObject }
    return when {
        present.size == 1 -> {
            val entry = present.single()
            StyleResolution.Found(entry.value, root[entry.key] as JsonObject)
        }
        present.size > 1 -> StyleResolution.Multiple
        else -> {
            val hint =
                root.entries.firstNotNullOfOrNull { (k, v) ->
                    val isStyleCandidate =
                        v is JsonObject && k !in STYLE_KEY_TO_TYPE && k !in KNOWN_NON_STYLE_OBJECT_KEYS
                    if (isStyleCandidate) k else null
                } ?: ""
            StyleResolution.Unknown(hint)
        }
    }
}

/**
 * Three-state expiration parse: distinguishes "absent" from "present-but-malformed",
 * which [assemblePass] needs in order to fail an unparseable expirationDate as
 * [PassJsonFailure.InvalidShape] (rather than silently dropping a security-relevant
 * validity window).
 */
private fun parseExpiration(probe: JsonElement?): ExpirationParse {
    if (probe == null || probe is JsonNull) return ExpirationParse.Absent
    val text = (probe as? JsonPrimitive)?.takeIf { it.isString }?.content
    return text?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            .map { ms -> ExpirationParse.Ok(PassInstant(ms)) as ExpirationParse }
            .getOrDefault(ExpirationParse.Malformed)
    } ?: ExpirationParse.Malformed
}

/**
 * Accepts both `rgb(R,G,B)` (Apple's preferred encoding for `*Color` fields) and
 * `#RRGGBB` (legacy / hand-authored passes). Returns `null` for `null` input or any
 * unrecognized form so the caller can default to "no color set" without a try/catch.
 */
private fun parseColor(text: String?): ColorValue? {
    val trimmed = text?.trim() ?: return null
    val rgb = RGB_REGEX.matchEntire(trimmed)?.let(::rgbMatchToColor)
    return rgb
        ?: HEX_REGEX.matchEntire(trimmed)?.groupValues?.get(1)?.toIntOrNull(HEX_RADIX)?.let(::ColorValue)
}

private fun rgbMatchToColor(match: MatchResult): ColorValue? {
    val r = match.groupValues[1].toIntOrNull()?.takeIf { it in 0..MAX_COLOR_COMPONENT }
    val g = match.groupValues[2].toIntOrNull()?.takeIf { it in 0..MAX_COLOR_COMPONENT }
    val b = match.groupValues[3].toIntOrNull()?.takeIf { it in 0..MAX_COLOR_COMPONENT }
    if (r == null || g == null || b == null) return null
    val red = r shl RED_SHIFT
    val green = g shl GREEN_SHIFT
    return ColorValue(red or green or b)
}

private fun parseFieldList(node: JsonElement?): List<PassField> {
    val array = node as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject
        val key = obj?.stringFieldOrNull(FIELD_KEY)
        val rawValue = obj?.get(FIELD_VALUE) as? JsonPrimitive
        val value = rawValue?.takeIf { it !is JsonNull }?.content
        if (obj == null || key == null || value == null) {
            null
        } else {
            PassField(
                key = key,
                label = obj.stringFieldOrNull(FIELD_LABEL),
                value = value,
                textAlignment =
                    obj.stringFieldOrNull(FIELD_TEXT_ALIGNMENT)
                        ?.let(TEXT_ALIGNMENT_MAP::get)
                        ?: TextAlignment.Natural,
            )
        }
    }
}

/**
 * Prefers the modern `barcodes[0]` (PKPASS spec since iOS 9) over the legacy `barcode`
 * scalar. A barcode entry that fails to map (unknown format, missing required field)
 * is silently dropped from `barcodes` so a single bad entry does not kill the whole
 * pass; the legacy `barcode` scalar acts as a fallback. If neither resolves, the pass
 * surfaces with `barcode = null`.
 */
private fun parseBarcode(root: JsonObject): Barcode? {
    val fromArray =
        (root[FIELD_BARCODES] as? JsonArray)
            ?.firstNotNullOfOrNull { (it as? JsonObject)?.let(::parseBarcodeNode) }
    return fromArray ?: (root[FIELD_BARCODE] as? JsonObject)?.let(::parseBarcodeNode)
}

private fun parseBarcodeNode(node: JsonObject): Barcode? {
    val format = node.stringFieldOrNull(FIELD_FORMAT)?.let(BARCODE_FORMAT_MAP::get)
    val message = node.stringFieldOrNull(FIELD_MESSAGE)
    val encoding = node.stringFieldOrNull(FIELD_MESSAGE_ENCODING)
    if (format == null || message == null || encoding == null) return null
    return Barcode(
        format = format,
        message = message,
        messageEncoding = encoding,
        altText = node.stringFieldOrNull(FIELD_ALT_TEXT),
    )
}

private fun JsonObject.stringFieldOrNull(name: String): String? {
    val v = this[name] as? JsonPrimitive ?: return null
    return if (v.isString) v.content else null
}

private sealed interface StyleResolution {
    data class Found(val type: PassType, val node: JsonObject) : StyleResolution

    data class Unknown(val raw: String) : StyleResolution

    data object Multiple : StyleResolution
}

private sealed interface ExpirationParse {
    data object Absent : ExpirationParse

    data object Malformed : ExpirationParse

    data class Ok(val instant: PassInstant) : ExpirationParse
}

/**
 * Defensive ceiling check the kotlinx parser does not natively enforce. Iterates
 * source bytes once, tracking nesting depth and the byte length of in-progress JSON
 * string tokens. ASCII-byte scanning is safe over UTF-8: continuation bytes
 * (`0x80..0xBF`) cannot collide with `{`, `}`, `[`, `]`, `"`, or `\`.
 *
 * String byte-counting uses source bytes (overcount by escape-sequence shrinkage),
 * not decoded character bytes — the guard's intent is to bound JSON-bomb expansion
 * before allocation, and the overcount is conservative (never lets an over-budget
 * string through). Returns `null` on success, or the first arm that tripped. JSON
 * well-formedness is intentionally not verified here — kotlinx.serialization handles
 * that downstream — so an unbalanced bracket pair sails through here and surfaces as
 * [PassJsonFailure.InvalidJson] from the typed parse.
 */
private fun enforceJsonLimits(
    bytes: ByteArray,
    config: ParserConfig,
): PassJsonFailure? {
    val state = JsonLimitTokenizer(maxDepth = config.maxJsonDepth, maxStringBytes = config.maxJsonStringBytes)
    var i = 0
    while (i < bytes.size && state.failure == null) {
        state.consume(bytes[i])
        i++
    }
    return state.failure
}

private class JsonLimitTokenizer(
    private val maxDepth: Int,
    private val maxStringBytes: Int,
) {
    var failure: PassJsonFailure? = null
        private set

    private var depth = 0
    private var inString = false
    private var stringByteCount = 0
    private var escape = false

    fun consume(b: Byte) {
        if (inString) consumeInString(b) else consumeOutsideString(b)
    }

    private fun consumeInString(b: Byte) {
        when {
            escape -> {
                escape = false
                bumpStringByte()
            }
            b == BACKSLASH -> {
                escape = true
                bumpStringByte()
            }
            b == DOUBLE_QUOTE -> inString = false
            else -> bumpStringByte()
        }
    }

    private fun consumeOutsideString(b: Byte) {
        when (b) {
            DOUBLE_QUOTE -> {
                inString = true
                stringByteCount = 0
            }
            LBRACE, LBRACKET -> {
                depth++
                if (depth > maxDepth) failure = PassJsonFailure.JsonDepthExceeded
            }
            RBRACE, RBRACKET -> depth--
        }
    }

    private fun bumpStringByte() {
        stringByteCount++
        if (stringByteCount > maxStringBytes) failure = PassJsonFailure.JsonStringTooLong
    }
}

private val PASS_JSON: Json =
    Json {
        isLenient = false
        ignoreUnknownKeys = true
    }

private val STYLE_KEY_TO_TYPE: Map<String, PassType> =
    linkedMapOf(
        "boardingPass" to PassType.BoardingPass,
        "eventTicket" to PassType.EventTicket,
        "coupon" to PassType.Coupon,
        "storeCard" to PassType.StoreCard,
        "generic" to PassType.Generic,
    )

/**
 * Top-level pass.json keys whose values are JSON objects but which are not pass-style
 * keys. Used to skip past these when scanning for an unknown-style key, so a payload
 * that omits all five known styles surfaces a meaningful raw key in
 * [PassJsonFailure.UnknownPassStyle] rather than e.g. "userInfo".
 *
 * The list is intentionally narrow: only keys we know to be object-valued in real
 * PKPASS payloads. A future Apple-spec'd object-valued metadata key not on the list
 * would surface here as an unknown style — a tolerable false positive (UI says
 * "this parser is too old"), and far less harmful than the false-negative direction
 * (silently lose a future pass style entirely).
 */
private val KNOWN_NON_STYLE_OBJECT_KEYS: Set<String> =
    setOf(
        "nfc",
        "personalization",
        "personalizationToken",
        "userInfo",
        "semantics",
        "barcode",
    )

private val BARCODE_FORMAT_MAP: Map<String, BarcodeFormat> =
    mapOf(
        "PKBarcodeFormatQR" to BarcodeFormat.QR,
        "PKBarcodeFormatPDF417" to BarcodeFormat.PDF417,
        "PKBarcodeFormatAztec" to BarcodeFormat.Aztec,
        "PKBarcodeFormatCode128" to BarcodeFormat.Code128,
    )

private val TEXT_ALIGNMENT_MAP: Map<String, TextAlignment> =
    mapOf(
        "PKTextAlignmentLeft" to TextAlignment.Left,
        "PKTextAlignmentCenter" to TextAlignment.Center,
        "PKTextAlignmentRight" to TextAlignment.Right,
        "PKTextAlignmentNatural" to TextAlignment.Natural,
    )

private val RGB_REGEX = Regex("""rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)""")
private val HEX_REGEX = Regex("""#([0-9a-fA-F]{6})""")

private const val PASS_JSON_FILE_NAME = "pass.json"
private const val PKPASS_FORMAT_VERSION = 1
private const val MAX_COLOR_COMPONENT = 255
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val HEX_RADIX = 16

private const val FIELD_FORMAT_VERSION = "formatVersion"
private const val FIELD_SERIAL_NUMBER = "serialNumber"
private const val FIELD_DESCRIPTION = "description"
private const val FIELD_ORGANIZATION_NAME = "organizationName"
private const val FIELD_EXPIRATION_DATE = "expirationDate"
private const val FIELD_VOIDED = "voided"
private const val FIELD_FOREGROUND_COLOR = "foregroundColor"
private const val FIELD_BACKGROUND_COLOR = "backgroundColor"
private const val FIELD_LABEL_COLOR = "labelColor"
private const val FIELD_HEADER_FIELDS = "headerFields"
private const val FIELD_PRIMARY_FIELDS = "primaryFields"
private const val FIELD_SECONDARY_FIELDS = "secondaryFields"
private const val FIELD_AUXILIARY_FIELDS = "auxiliaryFields"
private const val FIELD_BACK_FIELDS = "backFields"
private const val FIELD_KEY = "key"
private const val FIELD_VALUE = "value"
private const val FIELD_LABEL = "label"
private const val FIELD_TEXT_ALIGNMENT = "textAlignment"
private const val FIELD_BARCODE = "barcode"
private const val FIELD_BARCODES = "barcodes"
private const val FIELD_FORMAT = "format"
private const val FIELD_MESSAGE = "message"
private const val FIELD_MESSAGE_ENCODING = "messageEncoding"
private const val FIELD_ALT_TEXT = "altText"

private const val DOUBLE_QUOTE: Byte = 0x22
private const val BACKSLASH: Byte = 0x5C
private const val LBRACE: Byte = 0x7B
private const val RBRACE: Byte = 0x7D
private const val LBRACKET: Byte = 0x5B
private const val RBRACKET: Byte = 0x5D
