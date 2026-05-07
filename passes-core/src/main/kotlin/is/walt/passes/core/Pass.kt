package `is`.walt.passes.core

/**
 * A parsed PKPASS pass. All security-critical normalization (signature verification, hash
 * checks, resource-limit enforcement) has already happened by the time a [Pass] exists; the
 * surrounding [SignatureStatus] tells the caller what level of provenance the underlying
 * archive carried.
 *
 * Field-level content (label/value strings, organization name, etc.) is sourced from the
 * default locale variant of the archive. Localized variants are retained verbatim in [locales]
 * so the renderer can re-bind on locale change without re-parsing.
 */
public data class Pass(
    public val type: PassType,
    public val serialNumber: String,
    public val description: String,
    public val organizationName: String,
    public val expirationDate: PassInstant?,
    public val voided: Boolean,
    public val colors: PassColors,
    public val frontFields: PassFields,
    public val backFields: List<PassField>,
    public val barcode: Barcode?,
    public val images: Map<ImageRole, ImageBytes>,
    public val locales: Map<PassLocale, LocalizedStrings>,
)

/**
 * Wrapper around the raw bytes of a single pass image. Exists only to give [Pass] a sane
 * `equals`: a bare `Map<ImageRole, ByteArray>` would inherit `ByteArray`'s reference
 * equality, so two passes with byte-identical images would compare unequal. That would
 * silently break any consumer doing distinct-until-changed style diffing on `Pass`.
 *
 * The wrapper is opaque on purpose: callers that need to render the image hand the bytes
 * to a decoder; they should not be doing arithmetic on the array.
 */
public class ImageBytes(public val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = this === other || other is ImageBytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()
}

/**
 * The five PKPASS pass styles. Subtype labels (e.g. `boardingPass.transitType`) are surfaced
 * via the localized strings rather than enumerated here, per decision-wlt-0tn-q2.
 */
public enum class PassType {
    BoardingPass,
    EventTicket,
    Coupon,
    StoreCard,
    Generic,
}

/**
 * RGB color triplet sourced from the pass.json `foregroundColor` / `backgroundColor` /
 * `labelColor` fields. The parser normalizes both `rgb(R,G,B)` and `#RRGGBB` forms into a
 * single 24-bit packed integer before producing this value.
 */
@JvmInline
public value class ColorValue(public val rgb: Int)

public data class PassColors(
    public val foreground: ColorValue?,
    public val background: ColorValue?,
    public val label: ColorValue?,
)

/**
 * The four field rows that appear on the front of a pass. PKPASS does not require any
 * particular row to be populated; renderers must tolerate empty lists.
 */
public data class PassFields(
    public val header: List<PassField> = emptyList(),
    public val primary: List<PassField> = emptyList(),
    public val secondary: List<PassField> = emptyList(),
    public val auxiliary: List<PassField> = emptyList(),
)

public data class PassField(
    public val key: String,
    public val label: String?,
    public val value: String,
    public val textAlignment: TextAlignment = TextAlignment.Natural,
)

public enum class TextAlignment {
    Left,
    Center,
    Right,
    Natural,
}

public data class Barcode(
    public val format: BarcodeFormat,
    public val message: String,
    public val messageEncoding: String,
    public val altText: String?,
)

public enum class BarcodeFormat {
    QR,
    PDF417,
    Aztec,
    Code128,
}

/**
 * The asset roles PKPASS recognises. `Retina` and `SuperRetina` are the @2x / @3x variants;
 * the parser preserves whichever variants the archive provides without upscaling.
 */
public enum class ImageRole {
    Logo,
    LogoRetina,
    LogoSuperRetina,
    Icon,
    IconRetina,
    IconSuperRetina,
    Strip,
    StripRetina,
    StripSuperRetina,
    Background,
    BackgroundRetina,
    BackgroundSuperRetina,
    Thumbnail,
    ThumbnailRetina,
    ThumbnailSuperRetina,
    Footer,
    FooterRetina,
    FooterSuperRetina,
}

/** Contents of a single `<locale>.lproj/pass.strings` file. */
public data class LocalizedStrings(public val entries: Map<String, String>) {
    public companion object {
        /** Empty strings table. Used as the no-op fallback when a pass carries no locales. */
        public val Empty: LocalizedStrings = LocalizedStrings(emptyMap())
    }
}

/**
 * Looks [raw] up in this strings table; if absent (or [raw] is null), returns [raw]
 * unchanged. This is Apple's documented PKPASS behavior for `label`, `value`, and
 * `attributedValue`: the field's literal text is treated as the lookup key, and a
 * miss falls through to the raw text.
 *
 * The "fall through to raw" path is what makes the substitution idempotent and makes
 * dynamic field values (ticket numbers, dates, codes) safe to pipe through this
 * function: they will not match any key and emerge unchanged.
 */
public fun LocalizedStrings.lookupOrSelf(raw: String?): String? {
    if (raw == null) return null
    return entries[raw] ?: raw
}

/**
 * Resolves a [LocalizedStrings] from this pass's [Pass.locales] for [preferred], using
 * Apple's documented PKPASS locale-fallback chain:
 *
 *  1. Exact tag match (`en-US` finds `en-US.lproj`).
 *  2. Language-only fallback (`en-US` -> `en`, `sv-FI` -> `sv`). The split point is
 *     either `-` (BCP 47) or `_` (legacy `Locale.toString()` form), whichever the
 *     consumer hands in.
 *  3. The `en` table, when present. Apple treats English as the implicit project
 *     fallback.
 *  4. The first locale declared in archive order. PKPASS does not pin a "default"
 *     locale; this matches the order [DefaultPassParser.collectLocales] preserves
 *     and gives the consumer *some* localized substitution rather than reverting
 *     every label to its raw key.
 *  5. [LocalizedStrings.Empty] when the pass has no `.lproj/pass.strings` at all.
 *     The empty table makes [LocalizedStrings.lookupOrSelf] a pure passthrough so
 *     callers can substitute unconditionally.
 *
 * Pure function: passes-core never reads the device locale itself (it is JVM-only by
 * module boundary and KMP-portable by intent). The consumer module that knows the
 * platform locale APIs hands the chosen [PassLocale] in.
 */
public fun Pass.resolveLocalizedStrings(preferred: PassLocale): LocalizedStrings {
    if (locales.isEmpty()) return LocalizedStrings.Empty
    val language = preferred.tag.substringBefore('-').substringBefore('_')
    val languageFallback =
        language
            .takeIf { it.isNotEmpty() && it != preferred.tag }
            ?.let { locales[PassLocale(it)] }
    return locales[preferred]
        ?: languageFallback
        ?: locales[PassLocale("en")]
        ?: locales.values.first()
}

/**
 * BCP-47 language tag, e.g. `en-US`, `de`, `zh-Hant`. Parsing of the tag itself is left to
 * the consumer module that knows the platform's locale APIs; passes-core is JVM-only and
 * does not depend on Android or `java.util.Locale` to keep KMP portability open.
 */
@JvmInline
public value class PassLocale(public val tag: String)

/**
 * Epoch-millisecond timestamp. Avoids depending on `java.time` directly so consumers on
 * older Android API levels (without core library desugaring) and KMP targets can use this
 * module without conversion gymnastics.
 */
@JvmInline
public value class PassInstant(public val epochMillis: Long)
