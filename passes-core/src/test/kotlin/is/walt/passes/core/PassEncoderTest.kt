package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.internal.ALIGNMENT_NAME_BY_VALUE
import `is`.walt.passes.core.internal.BASENAME_BY_ROLE
import `is`.walt.passes.core.internal.FORMAT_NAME_BY_VALUE
import `is`.walt.passes.core.internal.STYLE_KEY_BY_TYPE
import `is`.walt.passes.core.internal.SyntheticPkpass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.TimeZone
import java.util.zip.ZipInputStream

/**
 * Behavior tests for [PassEncoder]. The contract under test is "Walt's own parser accepts
 * the output and reads back the same [Pass]": every fixture goes parse -> encode -> parse
 * through the public entrypoints, so the encoder is pinned to the reader, not to Apple's
 * format document.
 */
class PassEncoderTest {
    @Test
    fun roundTripPreservesEveryModelFieldTheKernelKeeps() {
        val original = parse(richArchive())
        assertThat(original.pass.barcode?.altText).isEqualTo("ALT 42")
        assertThat(original.pass.images).hasSize(3)
        assertThat(original.pass.locales).hasSize(2)

        val reparsed = parse(encode(original.pass))

        assertThat(reparsed.pass).isEqualTo(original.pass)
        assertThat(reparsed.signatureStatus).isEqualTo(SignatureStatus.Unsigned)
    }

    @Test
    fun roundTripHoldsForEveryPassStyle() {
        for (type in PassType.entries) {
            val style = STYLE_KEY_BY_TYPE.getValue(type)
            val original = parse(SyntheticPkpass.unsigned(SyntheticPkpass.minimalPassJson(style))).pass
            assertThat(original.type).isEqualTo(type)
            assertThat(parse(encode(original)).pass).isEqualTo(original)
        }
    }

    /** Drift detector: every enum value the writer may meet has a name in the inverted reader table. */
    @Test
    fun invertedNameTablesCoverEveryEnumValue() {
        assertThat(BASENAME_BY_ROLE.keys).containsExactlyElementsIn(ImageRole.entries)
        assertThat(STYLE_KEY_BY_TYPE.keys).containsExactlyElementsIn(PassType.entries)
        assertThat(FORMAT_NAME_BY_VALUE.keys).containsExactlyElementsIn(BarcodeFormat.entries)
        assertThat(ALIGNMENT_NAME_BY_VALUE.keys).containsExactlyElementsIn(TextAlignment.entries)
    }

    /**
     * The first local file header must carry no extra field: an extended timestamp
     * (0x5455) would embed UTC seconds and make the bytes zone-dependent on API 28-33.
     */
    @Test
    fun localHeadersCarryNoExtraFieldAndAFixedDosTimestamp() {
        val bytes = encode(parse(richArchive()).pass)
        assertThat(bytes.copyOfRange(0, 4)).isEqualTo(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        val dosTime = bytes.u16(LOCAL_HEADER_TIME_OFFSET)
        val dosDate = bytes.u16(LOCAL_HEADER_DATE_OFFSET)
        val extraLength = bytes.u16(LOCAL_HEADER_EXTRA_LENGTH_OFFSET)
        assertThat(dosTime).isEqualTo(0)
        assertThat(dosDate).isEqualTo(DOS_DATE_1980_01_02)
        assertThat(extraLength).isEqualTo(0)
    }

    @Test
    fun outputCarriesNoSignatureAndStrictModeRefusesIt() {
        val bytes = encode(parse(richArchive()).pass)
        assertThat(entryNames(bytes)).doesNotContain("signature")
        val strict = PassParser.create(ParserConfig.Strict).parse(PassSource.Bytes(bytes))
        assertThat(strict).isEqualTo(ParseResult.Tampered(TamperReason.SignatureCryptoFailure))
    }

    /** Pins that the parser needs neither identifier, so their absence from the output is not a defect. */
    @Test
    fun outputOmitsIssuerIdentifiersAndTheParserDoesNotRequireThem() {
        val bytes = encode(parse(richArchive()).pass)
        val passJson = Json.parseToJsonElement(entryBytes(bytes, "pass.json").decodeToString()) as JsonObject
        assertThat(passJson.keys).containsNoneOf("passTypeIdentifier", "teamIdentifier", "authenticationToken")
        assertThat(passJson.keys).containsAtLeast("barcode", "barcodes")
        assertThat(parse(bytes).pass.serialNumber).isEqualTo("SN-42")
    }

    @Test
    fun entryOrderIsFixed() {
        val names = entryNames(encode(parse(richArchive()).pass))
        assertThat(names)
            .containsExactly(
                "pass.json",
                "manifest.json",
                "logo.png",
                "icon@2x.png",
                "strip.png",
                "de.lproj/pass.strings",
                "en.lproj/pass.strings",
            ).inOrder()
    }

    @Test
    fun encodeIsByteIdenticalForEqualPassesRegardlessOfMapOrderOrTimeZone() {
        val pass = parse(richArchive()).pass
        val reordered =
            pass.copy(
                images = pass.images.entries.reversed().associate { it.key to it.value },
                locales = pass.locales.entries.reversed().associate { it.key to it.value },
            )
        assertThat(reordered).isEqualTo(pass)
        val first = encode(pass)
        assertThat(encode(reordered)).isEqualTo(first)

        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+12"))
            val west = encode(pass)
            TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT-14"))
            val east = encode(pass)
            assertThat(west).isEqualTo(first)
            assertThat(east).isEqualTo(first)
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun stringsEscapesRoundTrip() {
        val strings =
            LocalizedStrings(
                mapOf(
                    "quote \"q\"" to "back\\slash",
                    "multi" to "line one\nline two\r\ttabbed",
                    "emoji" to "Fahrt 🚀 los",
                    "empty" to "",
                ),
            )
        val pass = parse(richArchive()).pass.copy(locales = mapOf(PassLocale("zh-Hant") to strings))
        assertThat(parse(encode(pass)).pass.locales).containsExactly(PassLocale("zh-Hant"), strings)
    }

    @Test
    fun invalidLocaleTagIsRefusedNotWritten() {
        val pass = parse(richArchive()).pass
        for (tag in listOf("", "en/US", "en\\US", "C:", "en\uD83D")) {
            val result = PassEncoder.encode(pass.copy(locales = mapOf(PassLocale(tag) to LocalizedStrings.Empty)))
            assertThat(result).isEqualTo(PassEncodeResult.InvalidLocaleTag(tag))
        }
    }

    @Test
    fun everyParserLimitIsHonouredBeforeBytesAreProduced() {
        val pass = parse(richArchive()).pass
        val giant = SyntheticPkpass.fakePng(widthDeclared = 4_097, heightDeclared = 4_096)
        val shortStrings = ParserConfig(maxJsonStringBytes = 32)
        val cases =
            listOf(
                ResourceLimit.ArchiveSize to (pass to ParserConfig(maxArchiveBytes = 64)),
                ResourceLimit.EntryCount to (pass to ParserConfig(maxEntries = 6)),
                ResourceLimit.EntrySize to (pass to ParserConfig(maxEntryBytes = 32)),
                ResourceLimit.JsonDepth to (pass to ParserConfig(maxJsonDepth = 2)),
                ResourceLimit.JsonStringSize to (pass.copy(description = "x".repeat(33)) to shortStrings),
                ResourceLimit.JsonStringSize to (withStringsValue(pass, "y".repeat(33)) to shortStrings),
                ResourceLimit.ImagePixelCount to (withImage(pass, giant) to ParserConfig()),
                ResourceLimit.LocaleCount to (pass to ParserConfig(maxLocaleCount = 1)),
            )
        for ((limit, input) in cases) {
            val (candidate, config) = input
            assertThat(PassEncoder.encode(candidate, config)).isEqualTo(PassEncodeResult.LimitExceeded(limit))
        }
        assertThat(ResourceLimit.entries).containsExactlyElementsIn(cases.map { it.first }.toSet())
    }

    @Test
    fun outputAtTheDefaultConfigIsAcceptedAtTheSameConfig() {
        val pass = parse(richArchive()).pass
        val config = ParserConfig(maxEntries = 7, maxLocaleCount = 2)
        val bytes = (PassEncoder.encode(pass, config) as PassEncodeResult.Success).bytes
        val result = PassParser.create(config).parse(PassSource.Bytes(bytes))
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
    }

    private fun withStringsValue(
        pass: Pass,
        value: String,
    ): Pass = pass.copy(locales = mapOf(PassLocale("en") to LocalizedStrings(mapOf("k" to value))))

    private fun withImage(
        pass: Pass,
        png: ByteArray,
    ): Pass = pass.copy(images = mapOf(ImageRole.Background to ImageBytes(png)))

    private fun encode(pass: Pass): ByteArray = (PassEncoder.encode(pass) as PassEncodeResult.Success).bytes

    private fun parse(bytes: ByteArray): ParseResult.Success {
        val result = PassParser.create().parse(PassSource.Bytes(bytes))
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        return result as ParseResult.Success
    }

    private fun ByteArray.u16(offset: Int): Int {
        val low = this[offset].toInt() and 0xFF
        val high = this[offset + 1].toInt() and 0xFF
        return low or (high shl 8)
    }

    private fun entryNames(zip: ByteArray): List<String> =
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            generateSequence { zis.nextEntry }.map { it.name }.toList()
        }

    private fun entryBytes(
        zip: ByteArray,
        name: String,
    ): ByteArray =
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            generateSequence { zis.nextEntry }.first { it.name == name }
            zis.readBytes()
        }

    /**
     * Every field the model keeps, plus keys the parser drops (identifiers, token, a
     * second barcode) so the round trip proves they are neither needed nor resurrected.
     */
    private fun richArchive(): ByteArray {
        val passJson =
            """
            {
              "formatVersion": 1,
              "passTypeIdentifier": "pass.is.walt.test",
              "teamIdentifier": "TEAM123",
              "serialNumber": "SN-42",
              "description": "Round trip fixture",
              "organizationName": "Walt Test Rail",
              "authenticationToken": "secret-token",
              "webServiceURL": "https://example.invalid/",
              "expirationDate": "2027-03-04T05:06:07.089+02:00",
              "voided": true,
              "foregroundColor": "rgb(255, 254, 253)",
              "backgroundColor": "#010203",
              "labelColor": "rgb(0,128,0)",
              "barcodes": [
                {"format": "PKBarcodeFormatAztec", "message": "AZ-42", "messageEncoding": "utf-8", "altText": "ALT 42"},
                {"format": "PKBarcodeFormatQR", "message": "ignored second", "messageEncoding": "iso-8859-1"}
              ],
              "boardingPass": {
                "transitType": "PKTransitTypeTrain",
                "headerFields": [{"key": "h", "label": "Header", "value": "H1", "textAlignment": "PKTextAlignmentRight"}],
                "primaryFields": [{"key": "from", "value": "OSL"}, {"key": "to", "label": "To", "value": "BGO"}],
                "secondaryFields": [{"key": "s", "label": "Seat \"12A\"", "value": "12A", "textAlignment": "PKTextAlignmentLeft"}],
                "auxiliaryFields": [{"key": "a", "label": "Aux", "value": "42", "textAlignment": "PKTextAlignmentCenter"}],
                "backFields": [{"key": "terms", "label": "Terms", "value": "Line one\nLine two"}]
              }
            }
            """.trimIndent()
        val extras =
            mapOf(
                "logo.png" to SyntheticPkpass.fakePng(2, 2),
                "icon@2x.png" to SyntheticPkpass.fakePng(4, 4),
                "strip.png" to SyntheticPkpass.fakePng(8, 2),
                "en.lproj/pass.strings" to "\"Header\" = \"Header\";\n\"To\" = \"To\";".toByteArray(),
                "de.lproj/pass.strings" to "\"Header\" = \"Kopf\";\n\"To\" = \"Nach\";".toByteArray(),
            )
        return SyntheticPkpass.unsigned(passJson, extras)
    }
}

private const val LOCAL_HEADER_TIME_OFFSET = 10
private const val LOCAL_HEADER_DATE_OFFSET = 12
private const val LOCAL_HEADER_EXTRA_LENGTH_OFFSET = 28

/** DOS date bits: (year - 1980) shl 9, month shl 5, day. */
private const val DOS_DATE_1980_01_02 = 1 shl 5 or 2
