package `is`.walt.passes.core.internal

import `is`.walt.passes.core.Barcode
import `is`.walt.passes.core.ColorValue
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassField
import `is`.walt.passes.core.TextAlignment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/**
 * Serializes the pass.json half of a [Pass]. The inverse of [decodePassJson] for the
 * keys the model keeps: same key constants, same name tables inverted, so a key the
 * decoder reads is the key the encoder writes. Compact kotlinx output with insertion
 * order gives byte-stable JSON for an equal [Pass].
 */
internal fun encodePassJson(pass: Pass): ByteArray {
    val root =
        buildJsonObject {
            put(FIELD_FORMAT_VERSION, PKPASS_FORMAT_VERSION)
            put(FIELD_SERIAL_NUMBER, pass.serialNumber)
            put(FIELD_DESCRIPTION, pass.description)
            put(FIELD_ORGANIZATION_NAME, pass.organizationName)
            pass.expirationDate?.let { put(FIELD_EXPIRATION_DATE, Instant.ofEpochMilli(it.epochMillis).toString()) }
            if (pass.voided) put(FIELD_VOIDED, true)
            pass.colors.foreground?.let { put(FIELD_FOREGROUND_COLOR, it.toRgbFunction()) }
            pass.colors.background?.let { put(FIELD_BACKGROUND_COLOR, it.toRgbFunction()) }
            pass.colors.label?.let { put(FIELD_LABEL_COLOR, it.toRgbFunction()) }
            pass.barcode?.let { barcode ->
                // Both spellings: the parser prefers `barcodes`; pre-iOS 9 readers only know `barcode`.
                put(FIELD_BARCODE, encodeBarcode(barcode))
                putJsonArray(FIELD_BARCODES) { add(encodeBarcode(barcode)) }
            }
            put(STYLE_KEY_BY_TYPE.getValue(pass.type), encodeStyle(pass))
        }
    return Json.encodeToString(JsonObject.serializer(), root).toByteArray(Charsets.UTF_8)
}

private fun encodeStyle(pass: Pass): JsonObject =
    buildJsonObject {
        putFieldsIfAny(FIELD_HEADER_FIELDS, pass.frontFields.header)
        putFieldsIfAny(FIELD_PRIMARY_FIELDS, pass.frontFields.primary)
        putFieldsIfAny(FIELD_SECONDARY_FIELDS, pass.frontFields.secondary)
        putFieldsIfAny(FIELD_AUXILIARY_FIELDS, pass.frontFields.auxiliary)
        putFieldsIfAny(FIELD_BACK_FIELDS, pass.backFields)
    }

private fun JsonObjectBuilder.putFieldsIfAny(
    name: String,
    fields: List<PassField>,
) {
    if (fields.isNotEmpty()) put(name, buildJsonArray { fields.forEach { add(encodeField(it)) } })
}

private fun encodeField(field: PassField): JsonObject =
    buildJsonObject {
        put(FIELD_KEY, field.key)
        field.label?.let { put(FIELD_LABEL, it) }
        put(FIELD_VALUE, field.value)
        if (field.textAlignment != TextAlignment.Natural) {
            put(FIELD_TEXT_ALIGNMENT, ALIGNMENT_NAME_BY_VALUE.getValue(field.textAlignment))
        }
    }

private fun encodeBarcode(barcode: Barcode): JsonObject =
    buildJsonObject {
        put(FIELD_FORMAT, FORMAT_NAME_BY_VALUE.getValue(barcode.format))
        put(FIELD_MESSAGE, barcode.message)
        put(FIELD_MESSAGE_ENCODING, barcode.messageEncoding)
        barcode.altText?.let { put(FIELD_ALT_TEXT, it) }
    }

private fun ColorValue.toRgbFunction(): String {
    val r = rgb shr RED_SHIFT and COLOR_MASK
    val g = rgb shr GREEN_SHIFT and COLOR_MASK
    val b = rgb and COLOR_MASK
    return "rgb($r,$g,$b)"
}

internal val STYLE_KEY_BY_TYPE = STYLE_KEY_TO_TYPE.entries.associate { it.value to it.key }
internal val FORMAT_NAME_BY_VALUE = BARCODE_FORMAT_MAP.entries.associate { it.value to it.key }
internal val ALIGNMENT_NAME_BY_VALUE = TEXT_ALIGNMENT_MAP.entries.associate { it.value to it.key }

private const val COLOR_MASK = 0xFF
