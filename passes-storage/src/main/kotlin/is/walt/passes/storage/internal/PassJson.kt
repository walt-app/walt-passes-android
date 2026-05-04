package `is`.walt.passes.storage.internal

import `is`.walt.passes.core.Barcode
import `is`.walt.passes.core.BarcodeFormat
import `is`.walt.passes.core.ColorValue
import `is`.walt.passes.core.LocalizedStrings
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassColors
import `is`.walt.passes.core.PassField
import `is`.walt.passes.core.PassFields
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.TextAlignment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Kotlinx-serialized projection of `Pass` minus its image-bytes map and locale tables, which
 * `passes-storage` persists as separate normalized rows. The on-disk `pass_json` BLOB column
 * carries this surrogate; the renderer reconstructs the same `Pass` value the parser
 * produced, without re-running PKCS#7 verification.
 *
 * `passes-core` deliberately does not annotate its public types as `@Serializable` (KMP-aimed,
 * keeps kotlinx-serialization off its public surface), so this module owns its own surrogate
 * and the `Pass <-> PassJson` conversion.
 */
internal object PassJsonCodec {
    private val json: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
    }

    fun encode(pass: Pass): ByteArray {
        val surrogate = pass.toSurrogate()
        return json.encodeToString(PassSurrogate.serializer(), surrogate).encodeToByteArray()
    }

    fun decode(bytes: ByteArray): Pass {
        val surrogate = json.decodeFromString(PassSurrogate.serializer(), bytes.decodeToString())
        return surrogate.toPass()
    }

    fun encodeStrings(strings: LocalizedStrings): ByteArray =
        json.encodeToString(LocalizedStringsSurrogate.serializer(), LocalizedStringsSurrogate(strings.entries))
            .encodeToByteArray()

    fun decodeStrings(bytes: ByteArray): LocalizedStrings =
        LocalizedStrings(
            json.decodeFromString(LocalizedStringsSurrogate.serializer(), bytes.decodeToString()).entries,
        )

    @Serializable
    internal data class PassSurrogate(
        val type: PassType,
        val serialNumber: String,
        val description: String,
        val organizationName: String,
        val expirationEpochMs: Long?,
        val voided: Boolean,
        val colors: PassColorsSurrogate,
        val frontFields: PassFieldsSurrogate,
        val backFields: List<PassFieldSurrogate>,
        val barcode: BarcodeSurrogate?,
    )

    @Serializable
    internal data class PassColorsSurrogate(
        val foregroundRgb: Int?,
        val backgroundRgb: Int?,
        val labelRgb: Int?,
    )

    @Serializable
    internal data class PassFieldsSurrogate(
        val header: List<PassFieldSurrogate>,
        val primary: List<PassFieldSurrogate>,
        val secondary: List<PassFieldSurrogate>,
        val auxiliary: List<PassFieldSurrogate>,
    )

    @Serializable
    internal data class PassFieldSurrogate(
        val key: String,
        val label: String?,
        val value: String,
        val textAlignment: TextAlignment,
    )

    @Serializable
    internal data class BarcodeSurrogate(
        val format: BarcodeFormat,
        val message: String,
        val messageEncoding: String,
        val altText: String?,
    )

    @Serializable
    internal data class LocalizedStringsSurrogate(val entries: Map<String, String>)

    private fun Pass.toSurrogate(): PassSurrogate = PassSurrogate(
        type = type,
        serialNumber = serialNumber,
        description = description,
        organizationName = organizationName,
        expirationEpochMs = expirationDate?.epochMillis,
        voided = voided,
        colors = PassColorsSurrogate(
            foregroundRgb = colors.foreground?.rgb,
            backgroundRgb = colors.background?.rgb,
            labelRgb = colors.label?.rgb,
        ),
        frontFields = PassFieldsSurrogate(
            header = frontFields.header.map { it.toSurrogate() },
            primary = frontFields.primary.map { it.toSurrogate() },
            secondary = frontFields.secondary.map { it.toSurrogate() },
            auxiliary = frontFields.auxiliary.map { it.toSurrogate() },
        ),
        backFields = backFields.map { it.toSurrogate() },
        barcode = barcode?.let {
            BarcodeSurrogate(
                format = it.format,
                message = it.message,
                messageEncoding = it.messageEncoding,
                altText = it.altText,
            )
        },
    )

    private fun PassField.toSurrogate(): PassFieldSurrogate =
        PassFieldSurrogate(key = key, label = label, value = value, textAlignment = textAlignment)

    private fun PassSurrogate.toPass(): Pass = Pass(
        type = type,
        serialNumber = serialNumber,
        description = description,
        organizationName = organizationName,
        expirationDate = expirationEpochMs?.let { PassInstant(it) },
        voided = voided,
        colors = PassColors(
            foreground = colors.foregroundRgb?.let { ColorValue(it) },
            background = colors.backgroundRgb?.let { ColorValue(it) },
            label = colors.labelRgb?.let { ColorValue(it) },
        ),
        frontFields = PassFields(
            header = frontFields.header.map { it.toField() },
            primary = frontFields.primary.map { it.toField() },
            secondary = frontFields.secondary.map { it.toField() },
            auxiliary = frontFields.auxiliary.map { it.toField() },
        ),
        backFields = backFields.map { it.toField() },
        barcode = barcode?.let {
            Barcode(
                format = it.format,
                message = it.message,
                messageEncoding = it.messageEncoding,
                altText = it.altText,
            )
        },
        images = emptyMap(),
        locales = emptyMap(),
    )

    private fun PassFieldSurrogate.toField(): PassField =
        PassField(key = key, label = label, value = value, textAlignment = textAlignment)
}
