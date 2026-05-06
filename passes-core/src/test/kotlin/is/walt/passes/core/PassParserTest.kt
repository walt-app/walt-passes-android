package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.internal.SyntheticPkpass
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * End-to-end behavior tests for [PassParser]. Every fixture is built in-memory at test
 * time (see [SyntheticPkpass]) so a reviewer can read each test top-to-bottom and see
 * which arm of [ParseResult] / [SignatureStatus] is being exercised.
 *
 * Slice-internal behavior (hardened-zip rejection, manifest verification details, .strings
 * lexer arms, signature classifier branches) lives in the per-slice tests; this suite
 * covers the glue: pipeline ordering, failure-arm routing onto the public surface, and
 * the trust toggles in [ParserConfig].
 */
class PassParserTest {
    @Test
    fun successfulBoardingPassRoundTrip() {
        assertSuccessForType("boardingPass", PassType.BoardingPass)
    }

    @Test
    fun successfulEventTicketRoundTrip() {
        assertSuccessForType("eventTicket", PassType.EventTicket)
    }

    @Test
    fun successfulCouponRoundTrip() {
        assertSuccessForType("coupon", PassType.Coupon)
    }

    @Test
    fun successfulStoreCardRoundTrip() {
        assertSuccessForType("storeCard", PassType.StoreCard)
    }

    @Test
    fun successfulGenericRoundTrip() {
        assertSuccessForType("generic", PassType.Generic)
    }

    @Test
    fun unsignedArchiveSurfacesAsUnsignedUnderLenientDefault() {
        val zip = SyntheticPkpass.unsigned(SyntheticPkpass.minimalPassJson("generic"))
        val result = PassParser.create().parse(PassSource.Bytes(zip))
        val success = result as ParseResult.Success
        assertThat(success.signatureStatus).isEqualTo(SignatureStatus.Unsigned)
    }

    @Test
    fun unsignedArchiveIsTamperedUnderStrictPolicy() {
        // Strict mode interprets the absence of a signature as a refusal to attest
        // cryptographic provenance, which is the same category as a malformed CMS
        // envelope. The archive itself is structurally fine; the trust UI must surface
        // this as a security event, not as malformedness.
        val zip = SyntheticPkpass.unsigned(SyntheticPkpass.minimalPassJson("generic"))
        val result = PassParser.create(ParserConfig.Strict).parse(PassSource.Bytes(zip))
        assertThat(result).isInstanceOf(ParseResult.Tampered::class.java)
        assertThat((result as ParseResult.Tampered).reason)
            .isEqualTo(TamperReason.SignatureCryptoFailure)
    }

    @Test
    fun selfSignedArchiveSurfacesAsSelfSigned() {
        val zip = SyntheticPkpass.signedSelfSigned(SyntheticPkpass.minimalPassJson("generic"))
        val result = PassParser.create().parse(PassSource.Bytes(zip))
        val success = result as ParseResult.Success
        assertThat(success.signatureStatus).isEqualTo(SignatureStatus.SelfSigned)
    }

    @Test
    fun selfSignedArchiveRejectedUnderStrictPolicy() {
        val zip = SyntheticPkpass.signedSelfSigned(SyntheticPkpass.minimalPassJson("generic"))
        val result = PassParser.create(ParserConfig.Strict).parse(PassSource.Bytes(zip))
        assertThat(result).isInstanceOf(ParseResult.Tampered::class.java)
        assertThat((result as ParseResult.Tampered).reason)
            .isEqualTo(TamperReason.SignatureCryptoFailure)
    }

    @Test
    fun manifestHashMismatchSurfacesAsTampered() {
        // The archive is structurally valid: pass.json bytes have been swapped for a
        // mutated copy after the manifest captured the original SHA-1. Tampering, not
        // malformedness — the trust UI must distinguish the two.
        val zip =
            SyntheticPkpass.unsignedWithTamperedPassJson(
                SyntheticPkpass.minimalPassJson("generic"),
            )
        val result = PassParser.create().parse(PassSource.Bytes(zip))
        assertThat(result).isInstanceOf(ParseResult.Tampered::class.java)
        assertThat((result as ParseResult.Tampered).reason)
            .isEqualTo(TamperReason.FileHashMismatch)
    }

    @Test
    fun missingPassJsonSurfacesAsMalformed() {
        // Build a zip whose only entry is manifest.json (declaring nothing). The
        // missing-pass.json arm of decodePassJson lifts onto Malformed.MissingPassJson.
        val zip = synthesizeArchive(mapOf("manifest.json" to "{}".toByteArray()))
        val result = PassParser.create().parse(PassSource.Bytes(zip))
        assertThat(result).isEqualTo(ParseResult.Malformed(MalformedReason.MissingPassJson))
    }

    @Test
    fun invalidPassJsonSurfacesAsMalformed() {
        val zip =
            SyntheticPkpass.unsigned(passJson = "{not valid json")
        val result = PassParser.create().parse(PassSource.Bytes(zip))
        assertThat(result).isEqualTo(ParseResult.Malformed(MalformedReason.InvalidPassJson))
    }

    @Test
    fun unknownFormatVersionSurfacesAsUnsupported() {
        val zip =
            SyntheticPkpass.unsigned(
                passJson =
                    """
                    {
                        "formatVersion": 99,
                        "serialNumber": "S",
                        "description": "D",
                        "organizationName": "O",
                        "generic": {}
                    }
                    """.trimIndent(),
            )
        val result = PassParser.create().parse(PassSource.Bytes(zip))
        assertThat(result).isEqualTo(ParseResult.Unsupported(UnsupportedReason.FormatVersion(99)))
    }

    @Test
    fun extractFailureForBytesThatAreNotAZip() {
        val result = PassParser.create().parse(PassSource.Bytes(byteArrayOf(0, 1, 2, 3)))
        assertThat(result).isEqualTo(ParseResult.Malformed(MalformedReason.NotAZipArchive))
    }

    @Test
    fun zipBombEntryTripsEntrySizeLimit() {
        // A single entry whose contents exceed maxEntryBytes lifts to
        // Malformed.ResourceLimitExceeded(EntrySize) at the extractor layer.
        val zip =
            SyntheticPkpass.unsigned(
                passJson = SyntheticPkpass.minimalPassJson("generic"),
                extraEntries = mapOf("icon.png" to ByteArray(2 * 1024)),
            )
        val tightConfig = ParserConfig().copy(maxEntryBytes = 1024)
        val result = PassParser.create(tightConfig).parse(PassSource.Bytes(zip))
        assertThat(result)
            .isEqualTo(
                ParseResult.Malformed(MalformedReason.ResourceLimitExceeded(ResourceLimit.EntrySize)),
            )
    }

    @Test
    fun pngPixelCountLimitTripsAsResourceLimit() {
        // Synthesize a PNG whose IHDR declares a canvas big enough to overshoot the
        // configured pixel cap. The bytes themselves are tiny — the parser inspects
        // IHDR only, which is exactly the point: an attacker cannot smuggle an
        // expensive decode by claiming a giant canvas.
        val giantPng = SyntheticPkpass.fakePng(widthDeclared = 4_096, heightDeclared = 4_096)
        val zip =
            SyntheticPkpass.unsigned(
                passJson = SyntheticPkpass.minimalPassJson("generic"),
                extraEntries = mapOf("icon.png" to giantPng),
            )
        val tightConfig = ParserConfig().copy(maxImagePixelCount = 100)
        val result = PassParser.create(tightConfig).parse(PassSource.Bytes(zip))
        assertThat(result)
            .isEqualTo(
                ParseResult.Malformed(MalformedReason.ResourceLimitExceeded(ResourceLimit.ImagePixelCount)),
            )
    }

    @Test
    fun maxLocaleCountLimitTripsBeforeStringsParse() {
        // Three locale .strings files vs. a cap of 2. The cap must trip even though
        // the .strings payloads are individually well-formed — the resource bound
        // exists to refuse archives that ask the parser to fan out across an
        // unbounded number of locale buckets.
        val tinyStrings = "\"k\" = \"v\";".toByteArray(Charsets.UTF_8)
        val zip =
            SyntheticPkpass.unsigned(
                passJson = SyntheticPkpass.minimalPassJson("generic"),
                extraEntries =
                    mapOf(
                        "en.lproj/pass.strings" to tinyStrings,
                        "de.lproj/pass.strings" to tinyStrings,
                        "fr.lproj/pass.strings" to tinyStrings,
                    ),
            )
        val tightConfig = ParserConfig().copy(maxLocaleCount = 2)
        val result = PassParser.create(tightConfig).parse(PassSource.Bytes(zip))
        assertThat(result)
            .isEqualTo(
                ParseResult.Malformed(MalformedReason.ResourceLimitExceeded(ResourceLimit.LocaleCount)),
            )
    }

    @Test
    fun localesAreSurfacedOnTheParsedPass() {
        val englishStrings = "\"destination\" = \"Destination\";".toByteArray(Charsets.UTF_8)
        val germanStrings = "\"destination\" = \"Ziel\";".toByteArray(Charsets.UTF_8)
        val zip =
            SyntheticPkpass.unsigned(
                passJson = SyntheticPkpass.minimalPassJson("generic"),
                extraEntries =
                    mapOf(
                        "en.lproj/pass.strings" to englishStrings,
                        "de.lproj/pass.strings" to germanStrings,
                    ),
            )
        val result = PassParser.create().parse(PassSource.Bytes(zip)) as ParseResult.Success
        assertThat(result.pass.locales.keys)
            .containsExactly(PassLocale("en"), PassLocale("de"))
        assertThat(result.pass.locales[PassLocale("de")]?.entries)
            .containsExactly("destination", "Ziel")
    }

    @Test
    fun imagesAreBoundIntoPassByRole() {
        val tinyPng = SyntheticPkpass.fakePng(widthDeclared = 1, heightDeclared = 1)
        val zip =
            SyntheticPkpass.unsigned(
                passJson = SyntheticPkpass.minimalPassJson("generic"),
                extraEntries =
                    mapOf(
                        "icon.png" to tinyPng,
                        "logo@2x.png" to tinyPng,
                        "background@3x.png" to tinyPng,
                        "stranger.png" to tinyPng,
                    ),
            )
        val result = PassParser.create().parse(PassSource.Bytes(zip)) as ParseResult.Success
        assertThat(result.pass.images.keys)
            .containsExactly(
                ImageRole.Icon,
                ImageRole.LogoRetina,
                ImageRole.BackgroundSuperRetina,
            )
    }

    @Test
    fun telemetryEmitsStartedThenSucceededInOrderOnHappyPath() {
        val recorder = RecordingTelemetryGuard()
        val zip = SyntheticPkpass.unsigned(SyntheticPkpass.minimalPassJson("generic"))
        val parser = PassParser.create(ParserConfig().copy(telemetryGuard = recorder))

        val result = parser.parse(PassSource.Bytes(zip))

        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        assertThat(recorder.events.map { it.kind })
            .containsExactly(EventKind.Started, EventKind.Succeeded)
            .inOrder()
        val succeeded = recorder.events[1] as RecordedEvent.Succeeded
        assertThat(succeeded.event.passType).isEqualTo(PassType.Generic)
        assertThat(succeeded.event.signatureStatus).isEqualTo(SignatureStatusKind.Unsigned)
        assertThat(succeeded.event.archiveBytes).isEqualTo(zip.size.toLong())
        assertThat(succeeded.event.durationMillis).isAtLeast(0L)
    }

    @Test
    fun telemetryEmitsStartedThenFailedOnNonZipInput() {
        val recorder = RecordingTelemetryGuard()
        val parser = PassParser.create(ParserConfig().copy(telemetryGuard = recorder))

        parser.parse(PassSource.Bytes(byteArrayOf(0)))

        assertThat(recorder.events.map { it.kind })
            .containsExactly(EventKind.Started, EventKind.Failed)
            .inOrder()
        val failed = recorder.events[1] as RecordedEvent.Failed
        assertThat(failed.event.outcome).isEqualTo(ParseFailureKind.Malformed)
    }

    @Test
    fun parserInstanceSurvives16ParallelInvocations() {
        // Concurrency contract: the parser holds only the immutable ParserConfig and
        // delegates to stateless internals. 16 threads sharing one parser and one
        // source must agree on the same Success.
        val zip = SyntheticPkpass.unsigned(SyntheticPkpass.minimalPassJson("generic"))
        val source = PassSource.Bytes(zip)
        val parser = PassParser.create()
        val workers = 16
        val executor = Executors.newFixedThreadPool(workers)
        val ready = CountDownLatch(workers)
        val go = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<ParseResult>()
        try {
            repeat(workers) {
                executor.submit {
                    ready.countDown()
                    go.await()
                    results.add(parser.parse(source))
                }
            }
            ready.await()
            go.countDown()
            executor.shutdown()
            check(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) { "concurrent parse timed out" }
        } finally {
            executor.shutdownNow()
        }
        assertThat(results).hasSize(workers)
        val first = results.first() as ParseResult.Success
        for (r in results) {
            val success = r as ParseResult.Success
            assertThat(success.pass).isEqualTo(first.pass)
            assertThat(success.signatureStatus).isEqualTo(first.signatureStatus)
        }
    }

    @Test
    fun pngPixelCountLimitTripsForU32MaxIhdrWithoutOverflowing() {
        // Regression: an IHDR declaring near-u32-max width and height would, with a
        // naive `dim.width * dim.height` over signed Long, wrap negative and slip past
        // the cap. The axis-pre-check forces the trip without even multiplying.
        val attackPng = SyntheticPkpass.fakePng(widthDeclared = Int.MAX_VALUE, heightDeclared = Int.MAX_VALUE)
        val zip =
            SyntheticPkpass.unsigned(
                passJson = SyntheticPkpass.minimalPassJson("generic"),
                extraEntries = mapOf("icon.png" to attackPng),
            )
        // Default cap; both axes already exceed it, so overflow never gets a chance.
        val result = PassParser.create().parse(PassSource.Bytes(zip))
        assertThat(result)
            .isEqualTo(
                ParseResult.Malformed(MalformedReason.ResourceLimitExceeded(ResourceLimit.ImagePixelCount)),
            )
    }

    @Test
    fun streamSourceWithoutSizeHintReportsExtractedBytesOnTelemetry() {
        // Stream sources without a caller-supplied sizeHint used to telemeter
        // archiveBytes = 0; the extractor now plumbs its measured byte count through
        // ExtractResult.Success so downstream observability has an honest number.
        val zip = SyntheticPkpass.unsigned(SyntheticPkpass.minimalPassJson("generic"))
        val recorder = RecordingTelemetryGuard()
        val parser = PassParser.create(ParserConfig().copy(telemetryGuard = recorder))

        val result =
            parser.parse(PassSource.Stream(stream = zip.inputStream(), sizeHintBytes = null))

        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        val succeeded = recorder.events[1] as RecordedEvent.Succeeded
        // Bounded counter stops a few bytes shy of the file's total length (zip's
        // central directory + EOCD trail is not consumed by ZipInputStream past the
        // first signature byte the loop sees), so we only assert "non-zero and not
        // larger than the archive."
        assertThat(succeeded.event.archiveBytes).isGreaterThan(0L)
        assertThat(succeeded.event.archiveBytes).isAtMost(zip.size.toLong())
    }

    @Test
    fun streamSourceWithSizeHintReportsThatSizeOnTelemetry() {
        // PassSource.Stream lets a caller stream a pkpass without holding the whole
        // archive in a ByteArray. The telemetry path records sizeHintBytes verbatim;
        // the extractor layer is documented to re-check it against maxArchiveBytes.
        val zip = SyntheticPkpass.unsigned(SyntheticPkpass.minimalPassJson("generic"))
        val recorder = RecordingTelemetryGuard()
        val parser = PassParser.create(ParserConfig().copy(telemetryGuard = recorder))

        val source =
            PassSource.Stream(
                stream = zip.inputStream(),
                sizeHintBytes = zip.size.toLong(),
            )
        val result = parser.parse(source)

        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        val succeeded = recorder.events[1] as RecordedEvent.Succeeded
        assertThat(succeeded.event.archiveBytes).isEqualTo(zip.size.toLong())
    }

    private fun assertSuccessForType(
        styleKey: String,
        expected: PassType,
    ) {
        val zip = SyntheticPkpass.unsigned(SyntheticPkpass.minimalPassJson(styleKey))
        val result = PassParser.create().parse(PassSource.Bytes(zip))
        val success = result as ParseResult.Success
        assertThat(success.pass.type).isEqualTo(expected)
        assertThat(success.signatureStatus).isEqualTo(SignatureStatus.Unsigned)
    }

    private fun synthesizeArchive(members: Map<String, ByteArray>): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in members) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private enum class EventKind { Started, Succeeded, Failed }

    private sealed interface RecordedEvent {
        val kind: EventKind

        data object Started : RecordedEvent {
            override val kind: EventKind get() = EventKind.Started
        }

        data class Succeeded(val event: ParseSucceededEvent) : RecordedEvent {
            override val kind: EventKind get() = EventKind.Succeeded
        }

        data class Failed(val event: ParseFailedEvent) : RecordedEvent {
            override val kind: EventKind get() = EventKind.Failed
        }
    }

    private class RecordingTelemetryGuard : TelemetryGuard {
        val events: MutableList<RecordedEvent> = mutableListOf()

        override fun onParseStarted() {
            events.add(RecordedEvent.Started)
        }

        override fun onParseSucceeded(event: ParseSucceededEvent) {
            events.add(RecordedEvent.Succeeded(event))
        }

        override fun onParseFailed(event: ParseFailedEvent) {
            events.add(RecordedEvent.Failed(event))
        }
    }

    companion object {
        private const val WAIT_SECONDS = 30L
    }
}
