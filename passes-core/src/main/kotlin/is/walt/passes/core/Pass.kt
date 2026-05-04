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
    public val images: Map<ImageRole, ByteArray>,
    public val locales: Map<PassLocale, LocalizedStrings>,
)

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
    Logo, LogoRetina, LogoSuperRetina,
    Icon, IconRetina, IconSuperRetina,
    Strip, StripRetina, StripSuperRetina,
    Background, BackgroundRetina, BackgroundSuperRetina,
    Thumbnail, ThumbnailRetina, ThumbnailSuperRetina,
    Footer, FooterRetina, FooterSuperRetina,
}

/** Contents of a single `<locale>.lproj/pass.strings` file. */
public data class LocalizedStrings(public val entries: Map<String, String>)

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
