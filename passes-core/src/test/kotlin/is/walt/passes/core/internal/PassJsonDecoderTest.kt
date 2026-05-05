package `is`.walt.passes.core.internal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import `is`.walt.passes.core.Barcode
import `is`.walt.passes.core.BarcodeFormat
import `is`.walt.passes.core.ColorValue
import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassField
import `is`.walt.passes.core.PassFields
import `is`.walt.passes.core.PassInstant
import `is`.walt.passes.core.PassType
import `is`.walt.passes.core.TextAlignment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * Behavior tests for [decodePassJson]. Every fixture is built in-memory from
 * [buildJsonObject] / [buildJsonArray] so a reviewer can read each test and see
 * exactly which arm of [PassJsonFailure] (or which mapped field of [Pass]) is being
 * exercised. No checked-in binary fixtures.
 *
 * The test file uses two shapes of helper: [passJsonBytes], which fully builds a
 * minimal valid pass.json with one named override, and per-test inline builders
 * where the override is too structured for a single-field swap. The split keeps the
 * happy-path tests short while letting failure tests be self-contained.
 */
class PassJsonDecoderTest {
    @Test
    fun happyPathBoardingPass() {
        val style =
            buildJsonObject {
                put(
                    "primaryFields",
                    buildJsonArray {
                        addJsonObject {
                            put("key", "from")
                            put("label", "From")
                            put("value", "SFO")
                            put("textAlignment", "PKTextAlignmentRight")
                        }
                    },
                )
                put(
                    "secondaryFields",
                    buildJsonArray {
                        addJsonObject {
                            put("key", "to")
                            put("value", "JFK")
                        }
                    },
                )
            }
        val bytes = passJsonBytes(styleKey = "boardingPass", styleNode = style, voided = true)
        val ok = decodePassJson(mapOf("pass.json" to bytes), ParserConfig()).asOk()

        assertThat(ok.pass.type).isEqualTo(PassType.BoardingPass)
        assertThat(ok.pass.serialNumber).isEqualTo("S1")
        assertThat(ok.pass.description).isEqualTo("D1")
        assertThat(ok.pass.organizationName).isEqualTo("Example Air")
        assertThat(ok.pass.voided).isTrue()
        assertThat(ok.pass.frontFields.primary).hasSize(1)
        assertThat(ok.pass.frontFields.primary.single())
            .isEqualTo(PassField(key = "from", label = "From", value = "SFO", textAlignment = TextAlignment.Right))
        // Field with no label / textAlignment defaults: label = null, alignment = Natural.
        assertThat(ok.pass.frontFields.secondary.single())
            .isEqualTo(PassField(key = "to", label = null, value = "JFK", textAlignment = TextAlignment.Natural))
        // Images and locales are NOT this slice's responsibility — the parser-glue bead
        // wires them in. Surfacing them empty is the load-bearing contract.
        assertThat(ok.pass.images).isEmpty()
        assertThat(ok.pass.locales).isEmpty()
    }

    @Test
    fun happyPathEachStyleKeyMapsToTheRightPassType() {
        val pairs =
            listOf(
                "boardingPass" to PassType.BoardingPass,
                "eventTicket" to PassType.EventTicket,
                "coupon" to PassType.Coupon,
                "storeCard" to PassType.StoreCard,
                "generic" to PassType.Generic,
            )
        for ((key, expectedType) in pairs) {
            val bytes = passJsonBytes(styleKey = key, styleNode = buildJsonObject {})
            val ok = decodePassJson(mapOf("pass.json" to bytes), ParserConfig()).asOk()
            assertWithMessage("style=$key").that(ok.pass.type).isEqualTo(expectedType)
        }
    }

    @Test
    fun missingPassJsonEntryReturnsMissing() {
        val result = decodePassJson(mapOf("manifest.json" to "{}".toByteArray()), ParserConfig())
        assertFailedWith(result, PassJsonFailure.Missing)
    }

    @Test
    fun invalidJsonReturnsInvalidJson() {
        val result = decodePassJson(mapOf("pass.json" to "not json".toByteArray()), ParserConfig())
        assertFailedWith(result, PassJsonFailure.InvalidJson)
    }

    @Test
    fun strictModeRejectsLenientInput() {
        // isLenient = false: unquoted keys are rejected. Lenient JSON would accept this.
        val lenient = "{formatVersion:1,serialNumber:\"S\"}".toByteArray()
        val result = decodePassJson(mapOf("pass.json" to lenient), ParserConfig())
        assertFailedWith(result, PassJsonFailure.InvalidJson)
    }

    @Test
    fun topLevelArrayReturnsInvalidJson() {
        // parseToJsonElement returns a JsonArray; the `as? JsonObject` check catches it.
        // It is NOT InvalidShape because shape-level checks only run on a successful
        // object parse — a top-level array means we cannot meaningfully ask "is this a
        // pass-style key here", so it is correctly rejected at the JSON layer.
        val result = decodePassJson(mapOf("pass.json" to "[1,2,3]".toByteArray()), ParserConfig())
        assertFailedWith(result, PassJsonFailure.InvalidJson)
    }

    @Test
    fun unknownFormatVersionReturnsUnknownFormatVersion() {
        val bytes =
            buildJsonObject {
                put("formatVersion", 2)
                put("serialNumber", "S")
                put("description", "D")
                put("organizationName", "O")
                put("generic", buildJsonObject {})
            }.encodeToBytes()
        val result = decodePassJson(mapOf("pass.json" to bytes), ParserConfig())
        assertFailedWith(result, PassJsonFailure.UnknownFormatVersion(2))
    }

    @Test
    fun missingFormatVersionReturnsUnknownFormatVersionZero() {
        val bytes =
            buildJsonObject {
                put("serialNumber", "S")
                put("description", "D")
                put("organizationName", "O")
                put("generic", buildJsonObject {})
            }.encodeToBytes()
        val result = decodePassJson(mapOf("pass.json" to bytes), ParserConfig())
        assertFailedWith(result, PassJsonFailure.UnknownFormatVersion(0))
    }

    @Test
    fun nonIntegerFormatVersionReturnsUnknownFormatVersionZero() {
        // formatVersion is a string — kotlinx parses it, intOrNull is null, we surface
        // UnknownFormatVersion(0). Distinguishes from a missing key only via telemetry,
        // which is acceptable: both indicate "no usable version".
        val bytes =
            buildJsonObject {
                put("formatVersion", "one")
                put("serialNumber", "S")
                put("description", "D")
                put("organizationName", "O")
                put("generic", buildJsonObject {})
            }.encodeToBytes()
        val result = decodePassJson(mapOf("pass.json" to bytes), ParserConfig())
        assertFailedWith(result, PassJsonFailure.UnknownFormatVersion(0))
    }

    @Test
    fun unknownStyleKeyReturnsUnknownPassStyleWithRaw() {
        // No known style; an unknown object-valued top-level key surfaces as raw.
        // The `userInfo` object is in the non-style allowlist and must NOT be picked.
        val bytes =
            buildJsonObject {
                put("formatVersion", 1)
                put("serialNumber", "S")
                put("description", "D")
                put("organizationName", "O")
                put("userInfo", buildJsonObject { put("a", 1) })
                put("ssoPass", buildJsonObject { put("primaryFields", buildJsonArray {}) })
            }.encodeToBytes()
        val result = decodePassJson(mapOf("pass.json" to bytes), ParserConfig())
        assertFailedWith(result, PassJsonFailure.UnknownPassStyle("ssoPass"))
    }

    @Test
    fun noStyleKeyAndNoUnknownObjectReturnsUnknownPassStyleEmptyHint() {
        val bytes =
            buildJsonObject {
                put("formatVersion", 1)
                put("serialNumber", "S")
                put("description", "D")
                put("organizationName", "O")
            }.encodeToBytes()
        val result = decodePassJson(mapOf("pass.json" to bytes), ParserConfig())
        assertFailedWith(result, PassJsonFailure.UnknownPassStyle(""))
    }

    @Test
    fun multipleStyleKeysReturnInvalidShape() {
        // PKPASS spec requires exactly one style; two is malformed.
        val bytes =
            buildJsonObject {
                put("formatVersion", 1)
                put("serialNumber", "S")
                put("description", "D")
                put("organizationName", "O")
                put("boardingPass", buildJsonObject {})
                put("eventTicket", buildJsonObject {})
            }.encodeToBytes()
        val result = decodePassJson(mapOf("pass.json" to bytes), ParserConfig())
        assertFailedWith(result, PassJsonFailure.InvalidShape)
    }

    @Test
    fun missingRequiredTopLevelFieldsReturnInvalidShape() {
        // Each required field omitted in turn: serialNumber, description, organizationName.
        val omitted = listOf("serialNumber", "description", "organizationName")
        for (skipKey in omitted) {
            val bytes =
                buildJsonObject {
                    put("formatVersion", 1)
                    if (skipKey != "serialNumber") put("serialNumber", "S")
                    if (skipKey != "description") put("description", "D")
                    if (skipKey != "organizationName") put("organizationName", "O")
                    put("generic", buildJsonObject {})
                }.encodeToBytes()
            val result = decodePassJson(mapOf("pass.json" to bytes), ParserConfig())
            assertWithMessage("omitting=$skipKey")
                .that((result as? PassJsonDecodeResult.Failed)?.failure)
                .isEqualTo(PassJsonFailure.InvalidShape)
        }
    }

    @Test
    fun rgbColorIsParsed() {
        val bytes =
            passJsonBytes(
                styleKey = "generic",
                styleNode = buildJsonObject {},
                colors =
                    mapOf(
                        "foregroundColor" to "rgb(255,255,255)",
                        "backgroundColor" to "rgb(0, 0, 0)",
                        "labelColor" to "rgb(204,204,204)",
                    ),
            )
        val ok = decodePassJson(mapOf("pass.json" to bytes), ParserConfig()).asOk()
        assertThat(ok.pass.colors.foreground).isEqualTo(ColorValue(0xFFFFFF))
        assertThat(ok.pass.colors.background).isEqualTo(ColorValue(0x000000))
        assertThat(ok.pass.colors.label).isEqualTo(ColorValue(0xCCCCCC))
    }

    @Test
    fun hexColorIsParsed() {
        val bytes =
            passJsonBytes(
                styleKey = "generic",
                styleNode = buildJsonObject {},
                colors =
                    mapOf(
                        "foregroundColor" to "#102030",
                        "backgroundColor" to "#aabbcc",
                    ),
            )
        val ok = decodePassJson(mapOf("pass.json" to bytes), ParserConfig()).asOk()
        assertThat(ok.pass.colors.foreground).isEqualTo(ColorValue(0x102030))
        assertThat(ok.pass.colors.background).isEqualTo(ColorValue(0xAABBCC))
        assertThat(ok.pass.colors.label).isNull()
    }

    @Test
    fun invalidColorIsDroppedAsNull() {
        // Out-of-range, malformed, hex of wrong length: all silently drop to null
        // rather than failing the whole parse — colors are optional and a malformed
        // color is recoverable in render.
        val bytes =
            passJsonBytes(
                styleKey = "generic",
                styleNode = buildJsonObject {},
                colors =
                    mapOf(
                        "foregroundColor" to "rgb(300,0,0)",
                        "backgroundColor" to "#zzzzzz",
                        "labelColor" to "#abc",
                    ),
            )
        val ok = decodePassJson(mapOf("pass.json" to bytes), ParserConfig()).asOk()
        assertThat(ok.pass.colors.foreground).isNull()
        assertThat(ok.pass.colors.background).isNull()
        assertThat(ok.pass.colors.label).isNull()
    }

    @Test
    fun barcodesArrayPreferredOverLegacyBarcode() {
        val bytes =
            passJsonBytes(
                styleKey = "generic",
                styleNode = buildJsonObject {},
                extra = {
                    put(
                        "barcode",
                        buildJsonObject {
                            put("format", "PKBarcodeFormatPDF417")
                            put("message", "LEGACY")
                            put("messageEncoding", "iso-8859-1")
                        },
                    )
                    put(
                        "barcodes",
                        buildJsonArray {
                            addJsonObject {
                                put("format", "PKBarcodeFormatQR")
                                put("message", "MODERN")
                                put("messageEncoding", "iso-8859-1")
                                put("altText", "modern-alt")
                            }
                        },
                    )
                },
            )
        val ok = decodePassJson(mapOf("pass.json" to bytes), ParserConfig()).asOk()
        assertThat(ok.pass.barcode)
            .isEqualTo(
                Barcode(
                    format = BarcodeFormat.QR,
                    message = "MODERN",
                    messageEncoding = "iso-8859-1",
                    altText = "modern-alt",
                ),
            )
    }

    @Test
    fun legacyBarcodeUsedWhenBarcodesArrayAbsent() {
        val bytes =
            passJsonBytes(
                styleKey = "generic",
                styleNode = buildJsonObject {},
                extra = {
                    put(
                        "barcode",
                        buildJsonObject {
                            put("format", "PKBarcodeFormatAztec")
                            put("message", "L")
                            put("messageEncoding", "iso-8859-1")
                        },
                    )
                },
            )
        val ok = decodePassJson(mapOf("pass.json" to bytes), ParserConfig()).asOk()
        assertThat(ok.pass.barcode?.format).isEqualTo(BarcodeFormat.Aztec)
        assertThat(ok.pass.barcode?.altText).isNull()
    }

    @Test
    fun legacyBarcodeUsedWhenBarcodesArrayHasNoUsableEntry() {
        // First entry has unknown format; legacy `barcode` is the fallback.
        val bytes =
            passJsonBytes(
                styleKey = "generic",
                styleNode = buildJsonObject {},
                extra = {
                    put(
                        "barcode",
                        buildJsonObject {
                            put("format", "PKBarcodeFormatCode128")
                            put("message", "FALLBACK")
                            put("messageEncoding", "iso-8859-1")
                        },
                    )
                    put(
                        "barcodes",
                        buildJsonArray {
                            addJsonObject {
                                put("format", "PKBarcodeFormatHologram")
                                put("message", "x")
                                put("messageEncoding", "iso-8859-1")
                            }
                        },
                    )
                },
            )
        val ok = decodePassJson(mapOf("pass.json" to bytes), ParserConfig()).asOk()
        assertThat(ok.pass.barcode?.message).isEqualTo("FALLBACK")
    }

    @Test
    fun absentBarcodeSurfacesNull() {
        val bytes = passJsonBytes(styleKey = "generic", styleNode = buildJsonObject {})
        val ok = decodePassJson(mapOf("pass.json" to bytes), ParserConfig()).asOk()
        assertThat(ok.pass.barcode).isNull()
    }

    @Test
    fun expirationDateIsParsedAsRfc3339() {
        // `2026-12-31T23:59:00Z` -> 1798761540000ms (epoch milliseconds).
        val bytes =
            passJsonBytes(
                styleKey = "generic",
                styleNode = buildJsonObject {},
                extra = { put("expirationDate", "2026-12-31T23:59:00Z") },
            )
        val ok = decodePassJson(mapOf("pass.json" to bytes), ParserConfig()).asOk()
        assertThat(ok.pass.expirationDate).isEqualTo(PassInstant(1798761540000L))
    }

    @Test
    fun absentExpirationDateSurfacesNull() {
        val bytes = passJsonBytes(styleKey = "generic", styleNode = buildJsonObject {})
        val ok = decodePassJson(mapOf("pass.json" to bytes), ParserConfig()).asOk()
        assertThat(ok.pass.expirationDate).isNull()
    }

    @Test
    fun malformedExpirationDateReturnsInvalidShape() {
        // Distinguishing absent (null) from present-but-junk (InvalidShape) is the
        // load-bearing call: silently dropping a corrupted validity window would let
        // an attacker un-expire a pass by tampering the field.
        val bytes =
            passJsonBytes(
                styleKey = "generic",
                styleNode = buildJsonObject {},
                extra = { put("expirationDate", "not a date") },
            )
        val result = decodePassJson(mapOf("pass.json" to bytes), ParserConfig())
        assertFailedWith(result, PassJsonFailure.InvalidShape)
    }

    @Test
    fun voidedTrueIsSurfacedTrueAndDefaultsFalse() {
        val voidedBytes = passJsonBytes(styleKey = "generic", styleNode = buildJsonObject {}, voided = true)
        val absentBytes = passJsonBytes(styleKey = "generic", styleNode = buildJsonObject {}, voided = null)
        assertThat(decodePassJson(mapOf("pass.json" to voidedBytes), ParserConfig()).asOk().pass.voided).isTrue()
        assertThat(decodePassJson(mapOf("pass.json" to absentBytes), ParserConfig()).asOk().pass.voided).isFalse()
    }

    @Test
    fun dangerousFieldsAreParsedButNotSurfaced() {
        // The presence of nfc, webServiceURL, authenticationToken, personalization,
        // and personalizationToken must not surface on the public Pass type. There
        // is intentionally no Pass field for them — this test guards the *structural*
        // claim that the data model has nowhere to surface them.
        val bytes =
            buildJsonObject {
                put("formatVersion", 1)
                put("serialNumber", "S")
                put("description", "D")
                put("organizationName", "O")
                put("generic", buildJsonObject {})
                put("nfc", buildJsonObject { put("message", "secret") })
                put("webServiceURL", "https://attacker.example.com/pass")
                put("authenticationToken", "TOK_64_CHARS_LONG_FAKE_VALUE_FOR_TEST_ONLY_DO_NOT_USE_ANYWHERE_X")
                put("personalization", buildJsonObject { put("requiredPersonalizationFields", buildJsonArray {}) })
                put("personalizationToken", "ptok-fake-value-xyz")
            }.encodeToBytes()
        val ok = decodePassJson(mapOf("pass.json" to bytes), ParserConfig()).asOk()
        // Equality with the canonical "minimal valid" pass demonstrates no extra
        // fields leaked: every Pass field is structurally derivable from the inputs,
        // so an extra dangerous payload either round-trips into a Pass field (bug)
        // or vanishes (correct). Since Pass has no NFC/web/auth/personalization
        // fields, only the latter is possible — so the assertion is structural.
        val canonicalBytes =
            buildJsonObject {
                put("formatVersion", 1)
                put("serialNumber", "S")
                put("description", "D")
                put("organizationName", "O")
                put("generic", buildJsonObject {})
            }.encodeToBytes()
        val canonical = decodePassJson(mapOf("pass.json" to canonicalBytes), ParserConfig()).asOk()
        assertThat(ok.pass).isEqualTo(canonical.pass)
    }

    @Test
    fun maxJsonDepthTrips() {
        // Build a JSON object nested deeper than the default depth limit.
        val deeplyNested = buildNestedObject(depth = ParserConfig.DEFAULT_MAX_JSON_DEPTH + 4)
        val result = decodePassJson(mapOf("pass.json" to deeplyNested), ParserConfig())
        assertFailedWith(result, PassJsonFailure.JsonDepthExceeded)
    }

    @Test
    fun depthLimitAtBoundaryAccepts() {
        // Exactly maxDepth nests is allowed; one more is the failure boundary.
        val cfg = ParserConfig(maxJsonDepth = 5)
        val justWithinDepth = buildNestedObject(depth = 5)
        // At-the-limit should pass the limit check (and fall to InvalidShape because
        // the deeply-nested payload is not a valid pass.json — that's fine; the test
        // is specifically that JsonDepthExceeded does NOT fire).
        val result = decodePassJson(mapOf("pass.json" to justWithinDepth), cfg)
        assertThat(result).isInstanceOf(PassJsonDecodeResult.Failed::class.java)
        val failure = (result as PassJsonDecodeResult.Failed).failure
        assertThat(failure).isNotEqualTo(PassJsonFailure.JsonDepthExceeded)
    }

    @Test
    fun maxJsonStringBytesTrips() {
        // Build a JSON object with a very long string value; configure a small ceiling.
        val cfg = ParserConfig(maxJsonStringBytes = 16)
        val bytes =
            buildJsonObject {
                put("formatVersion", 1)
                put("description", "x".repeat(64))
            }.encodeToBytes()
        val result = decodePassJson(mapOf("pass.json" to bytes), cfg)
        assertFailedWith(result, PassJsonFailure.JsonStringTooLong)
    }

    @Test
    fun stringLimitDoesNotTripOnSequenceOfShortStrings() {
        // Critical guard: the per-string counter must reset between strings. Sixty-four
        // strings of 4 bytes apiece with maxJsonStringBytes=16 must NOT trip — a single
        // running counter would.
        val cfg = ParserConfig(maxJsonStringBytes = 16)
        val styleNode =
            buildJsonObject {
                put(
                    "primaryFields",
                    buildJsonArray {
                        repeat(64) { i ->
                            addJsonObject {
                                put("key", "k$i")
                                put("value", "v$i")
                            }
                        }
                    },
                )
            }
        val bytes = passJsonBytes(styleKey = "generic", styleNode = styleNode)
        val ok = decodePassJson(mapOf("pass.json" to bytes), cfg).asOk()
        assertThat(ok.pass.frontFields.primary).hasSize(64)
    }

    @Test
    fun maxJsonStringBytesAcceptsStringsAtTheLimit() {
        val cfg = ParserConfig(maxJsonStringBytes = 16)
        val bytes =
            buildJsonObject {
                put("formatVersion", 1)
                put("serialNumber", "S")
                put("description", "x".repeat(16))
                put("organizationName", "O")
                put("generic", buildJsonObject {})
            }.encodeToBytes()
        val ok = decodePassJson(mapOf("pass.json" to bytes), cfg).asOk()
        assertThat(ok.pass.description).isEqualTo("x".repeat(16))
    }

    @Test
    fun strayLeadingClosersDoNotInflateInFlightDepth() {
        // Regression test for the depth-clamp invariant (`0 <= depth <= maxDepth`).
        // Without clamping, leading `}}` would drive depth to -2, then the
        // following 3 `{` would only peak at +1 — meaning JsonDepthExceeded would
        // never fire, and the payload would surface only as InvalidJson when
        // kotlinx rejects the mismatch. With clamping, depth stays 0 through the
        // leading closers, then climbs 1, 2, 3 — tripping JsonDepthExceeded at the
        // third opener (depth 3 > maxDepth 2). The clamp keeps the budget honest
        // regardless of what kotlinx does downstream.
        val cfg = ParserConfig(maxJsonDepth = 2)
        val payload = "}}{{{}}".toByteArray()
        val result = decodePassJson(mapOf("pass.json" to payload), cfg)
        assertFailedWith(result, PassJsonFailure.JsonDepthExceeded)
    }

    @Test
    fun escapedQuoteInsideStringDoesNotTerminateString() {
        // Regression: the tokenizer must treat \" as a continued string, otherwise it
        // resets stringByteCount mid-string and silently lets oversized payloads through.
        // 16 characters of "abc\"" repeated would need correct escape handling.
        val cfg = ParserConfig(maxJsonStringBytes = 8)
        val bytes =
            buildJsonObject {
                put("formatVersion", 1)
                put("description", "a\"b\"c\"d\"e\"f\"g\"h\"i\"j") // > 8 source bytes
            }.encodeToBytes()
        val result = decodePassJson(mapOf("pass.json" to bytes), cfg)
        assertFailedWith(result, PassJsonFailure.JsonStringTooLong)
    }

    @Test
    fun frontFieldsDefaultToEmptyListsWhenStyleNodeOmitsThem() {
        val bytes = passJsonBytes(styleKey = "storeCard", styleNode = buildJsonObject {})
        val ok = decodePassJson(mapOf("pass.json" to bytes), ParserConfig()).asOk()
        assertThat(ok.pass.frontFields).isEqualTo(PassFields())
        assertThat(ok.pass.backFields).isEmpty()
    }

    @Test
    fun fieldRowEntryWithMissingKeyOrValueIsSkipped() {
        // PKPASS spec requires both key and value on a field. A junk entry must not
        // bring down the whole pass — drop the entry and keep the rest.
        val styleNode =
            buildJsonObject {
                put(
                    "primaryFields",
                    buildJsonArray {
                        addJsonObject { put("value", "no key") }
                        addJsonObject {
                            put("key", "ok")
                            put("value", "yes")
                        }
                        addJsonObject {
                            put("key", "no value only label")
                            put("label", "L")
                        }
                    },
                )
            }
        val bytes = passJsonBytes(styleKey = "generic", styleNode = styleNode)
        val ok = decodePassJson(mapOf("pass.json" to bytes), ParserConfig()).asOk()
        assertThat(ok.pass.frontFields.primary.map { it.key }).containsExactly("ok")
    }

    @Test
    fun fieldValueAcceptsNumberAsString() {
        // PKPASS allows numeric `value` (with optional numberStyle/currencyCode). The
        // model stores `value` as String — stringification preserves the literal.
        val styleNode =
            buildJsonObject {
                put(
                    "primaryFields",
                    buildJsonArray {
                        addJsonObject {
                            put("key", "amount")
                            put("value", 5.99)
                        }
                    },
                )
            }
        val bytes = passJsonBytes(styleKey = "coupon", styleNode = styleNode)
        val ok = decodePassJson(mapOf("pass.json" to bytes), ParserConfig()).asOk()
        assertThat(ok.pass.frontFields.primary.single().value).isEqualTo("5.99")
    }

    /**
     * Builds a minimal but fully-typed pass.json. The named overrides are applied
     * after the canonical defaults so a single-field swap reads as one named arg.
     */
    private fun passJsonBytes(
        styleKey: String,
        styleNode: JsonObject,
        voided: Boolean? = null,
        colors: Map<String, String> = emptyMap(),
        extra: JsonObjectBuilder.() -> Unit = {},
    ): ByteArray =
        buildJsonObject {
            put("formatVersion", 1)
            put("serialNumber", "S1")
            put("description", "D1")
            put("organizationName", "Example Air")
            if (voided != null) put("voided", voided)
            for ((k, v) in colors) put(k, v)
            put(styleKey, styleNode)
            extra()
        }.encodeToBytes()

    private fun buildNestedObject(depth: Int): ByteArray {
        // Leaf is `1`; each layer wraps in {"k": ...}. depth = number of opening braces.
        val sb = StringBuilder()
        repeat(depth) { sb.append("{\"k\":") }
        sb.append("1")
        repeat(depth) { sb.append("}") }
        return sb.toString().toByteArray()
    }

    private fun PassJsonDecodeResult.asOk(): PassJsonDecodeResult.Ok {
        assertThat(this).isInstanceOf(PassJsonDecodeResult.Ok::class.java)
        return this as PassJsonDecodeResult.Ok
    }

    private fun assertFailedWith(
        actual: PassJsonDecodeResult,
        expected: PassJsonFailure,
    ) {
        assertThat(actual).isInstanceOf(PassJsonDecodeResult.Failed::class.java)
        val failure = (actual as PassJsonDecodeResult.Failed).failure
        assertWithMessage("expected PassJsonFailure=$expected, got $failure")
            .that(failure)
            .isEqualTo(expected)
    }
}

private fun JsonObject.encodeToBytes(): ByteArray = Json.encodeToString(JsonObject.serializer(), this).toByteArray()
