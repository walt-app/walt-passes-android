package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.internal.SyntheticPkpass
import org.junit.Test

/**
 * Locks the public API surface of `passes-core`. There is no parser implementation yet, so
 * these tests target two things:
 *
 *  1. Drift detection — every sealed arm and enum value referenced by the parser contract is
 *     reachable from a test. Removing an arm fails compilation; adding one without updating
 *     the telemetry-flattened enums fails compilation in the production-side helper that the
 *     test exercises.
 *  2. Default-policy locks — [ParserConfig] defaults encode decision-wlt-0tn-q1's lenient
 *     trust posture; flipping a default is a deliberate, test-breaking change.
 *
 * Behavior tests of parsing, signature verification, and resource enforcement land with the
 * parser implementation bead.
 */
class PublicApiSurfaceTest {
    @Test
    fun parserConfigDefaultsAreLenient() {
        val cfg = ParserConfig()
        assertThat(cfg.acceptUnsignedArchives).isTrue()
        assertThat(cfg.acceptSelfSignedCertificates).isTrue()
    }

    @Test
    fun parserConfigStrictRejectsBoth() {
        assertThat(ParserConfig.Strict.acceptUnsignedArchives).isFalse()
        assertThat(ParserConfig.Strict.acceptSelfSignedCertificates).isFalse()
    }

    @Test
    fun parseResultArmsAreReachableViaWhen() {
        val result: ParseResult = ParseResult.Malformed(MalformedReason.NotAZipArchive)
        val branch =
            when (result) {
                is ParseResult.Success -> "success"
                is ParseResult.Tampered -> "tampered"
                is ParseResult.Malformed -> "malformed"
                is ParseResult.Unsupported -> "unsupported"
            }
        assertThat(branch).isEqualTo("malformed")
    }

    /**
     * Every [SignatureStatus] arm round-trips through [toKind]. The reverse direction is
     * already enforced at compile time by the exhaustive `when` inside `toKind`; this test
     * covers the forward direction (every kind value is reachable from some status arm).
     * Together they prove the two types stay in lockstep.
     */
    @Test
    fun signatureStatusBijectsToKind() {
        val statuses: List<SignatureStatus> =
            listOf(
                SignatureStatus.Unsigned,
                SignatureStatus.SelfSigned,
                SignatureStatus.AppleVerified,
                SignatureStatus.CertChainIncomplete,
            )
        val kindsFromStatuses = statuses.map { it.toKind() }.toSet()
        assertThat(kindsFromStatuses).containsExactlyElementsIn(SignatureStatusKind.entries)
    }

    /**
     * Companion to [signatureStatusBijectsToKind] for [ParseResult] ↔ [ParseFailureKind].
     * `Success` correctly returns `null` (success is not a failure event); the other three
     * arms map onto the three non-resource [ParseFailureKind] entries, and the
     * resource-limit subreason lifts out of `Malformed` into its own bucket.
     */
    @Test
    fun parseResultFailureKindCoversEveryArm() {
        val nonResourceMalformed: ParseResult = ParseResult.Malformed(MalformedReason.MissingPassJson)
        val resourceMalformed: ParseResult =
            ParseResult.Malformed(MalformedReason.ResourceLimitExceeded(ResourceLimit.ArchiveSize))
        val results: List<ParseResult> =
            listOf(
                ParseResult.Success(samplePass(), SignatureStatus.AppleVerified),
                ParseResult.Tampered(TamperReason.ManifestSignatureMismatch),
                nonResourceMalformed,
                resourceMalformed,
                ParseResult.Unsupported(UnsupportedReason.EncryptedArchive),
            )
        val kinds = results.map { it.toFailureKind() }
        assertThat(kinds).containsExactly(
            null,
            ParseFailureKind.Tampered,
            ParseFailureKind.Malformed,
            ParseFailureKind.ResourceLimitExceeded,
            ParseFailureKind.Unsupported,
        ).inOrder()
        assertThat(kinds.filterNotNull().toSet())
            .containsExactlyElementsIn(ParseFailureKind.entries)
    }

    /**
     * Every [ResourceLimit] enum value has a backing [ParserConfig] field. The exhaustive
     * `when` inside [limitFrom] enforces the existence side at compile time; this test
     * proves the values are non-zero (a misconfigured field would silently zero-out a guard
     * that should be cutting off attack payloads).
     */
    @Test
    fun resourceLimitsAllResolveToPositiveValues() {
        val cfg = ParserConfig()
        for (limit in ResourceLimit.entries) {
            assertThat(limit.limitFrom(cfg)).isGreaterThan(0L)
        }
    }

    /**
     * Every [TamperReason] arm is constructible. Removing an arm breaks compilation here
     * before it breaks a downstream `when`.
     */
    @Test
    fun tamperReasonArmsAreAllConstructible() {
        val reasons: List<TamperReason> =
            listOf(
                TamperReason.ManifestSignatureMismatch,
                TamperReason.FileHashMismatch,
                TamperReason.SignatureCryptoFailure,
                TamperReason.SignerCertificateMissing,
            )
        assertThat(reasons.toSet()).hasSize(reasons.size)
    }

    @Test
    fun malformedReasonArmsAreAllConstructible() {
        val reasons: List<MalformedReason> =
            listOf(
                MalformedReason.NotAZipArchive,
                MalformedReason.MissingPassJson,
                MalformedReason.MissingManifest,
                MalformedReason.InvalidPassJson,
                MalformedReason.InvalidManifest,
                MalformedReason.InvalidStrings,
                MalformedReason.ResourceLimitExceeded(ResourceLimit.JsonDepth),
            )
        assertThat(reasons.toSet()).hasSize(reasons.size)
    }

    @Test
    fun unsupportedReasonArmsAreAllConstructible() {
        val reasons: List<UnsupportedReason> =
            listOf(
                UnsupportedReason.FormatVersion(2),
                UnsupportedReason.UnknownPassStyle("nfcPass"),
                UnsupportedReason.EncryptedArchive,
            )
        assertThat(reasons.toSet()).hasSize(reasons.size)
    }

    @Test
    fun telemetryGuardNoOpAcceptsAllEventShapes() {
        val guard: TelemetryGuard = TelemetryGuard.NoOp
        guard.onParseStarted()
        guard.onParseSucceeded(
            ParseSucceededEvent(
                passType = PassType.BoardingPass,
                signatureStatus = SignatureStatusKind.AppleVerified,
                archiveBytes = 12_345L,
                durationMillis = 42L,
                imageCount = 4,
                localeCount = 2,
            ),
        )
        guard.onParseFailed(ParseFailedEvent(ParseFailureKind.Tampered, 7L))
    }

    @Test
    fun parserFactoryDoesNotThrow() {
        // Construction is decoupled from any parsing work; the factory must not throw
        // for any reason other than a developer-side ParserConfig misuse (which the
        // public surface forbids by construction — the data class only exposes valid
        // shapes).
        val parser = PassParser.create()
        assertThat(parser).isNotNull()
    }

    @Test
    fun parserCanParseSyntheticPkpass() {
        // Surface lock for the bead that wired the four implementation slices into
        // PassParser.create(). Any future refactor that re-introduces a `throw` on the
        // factory's parse() arm trips this test before any consumer-side test sees it.
        val zip = SyntheticPkpass.unsigned(SyntheticPkpass.minimalPassJson("generic"))
        val result = PassParser.create().parse(PassSource.Bytes(zip))
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
    }

    /**
     * Constructs a fully-populated [Pass] using one value from every [PassType], every
     * [TextAlignment], every [BarcodeFormat], and a sample of [ImageRole]. Adding a
     * required parameter to [Pass], [PassField], [PassFields], [PassColors], [Barcode],
     * [LocalizedStrings], [PassInstant], [PassLocale], or [ColorValue] breaks this test —
     * which is the point. Anyone touching the data shape must update the consumer-facing
     * lock.
     */
    @Test
    fun passConstructorIsExercisedWithEveryShape() {
        val field =
            PassField(
                key = "destination",
                label = "Destination",
                value = "SFO",
                textAlignment = TextAlignment.Natural,
            )
        val pass =
            Pass(
                type = PassType.BoardingPass,
                serialNumber = "ABC-123",
                description = "Boarding pass for flight 42",
                organizationName = "Example Air",
                expirationDate = PassInstant(epochMillis = 1_800_000_000_000L),
                voided = false,
                colors =
                    PassColors(
                        foreground = ColorValue(0xFFFFFF),
                        background = ColorValue(0x000000),
                        label = ColorValue(0xCCCCCC),
                    ),
                frontFields =
                    PassFields(
                        header = listOf(field.copy(key = "h", textAlignment = TextAlignment.Left)),
                        primary = listOf(field.copy(key = "p", textAlignment = TextAlignment.Center)),
                        secondary = listOf(field.copy(key = "s", textAlignment = TextAlignment.Right)),
                        auxiliary = listOf(field.copy(key = "a")),
                    ),
                backFields = listOf(field.copy(key = "b")),
                barcode =
                    Barcode(
                        format = BarcodeFormat.QR,
                        message = "FLIGHT42",
                        messageEncoding = "iso-8859-1",
                        altText = "FLIGHT42",
                    ),
                images =
                    mapOf(
                        ImageRole.Logo to ImageBytes(byteArrayOf(0x89.toByte(), 0x50)),
                        ImageRole.Icon to ImageBytes(byteArrayOf(0x89.toByte(), 0x50)),
                    ),
                locales =
                    mapOf(
                        PassLocale("en-US") to LocalizedStrings(mapOf("destination" to "Destination")),
                        PassLocale("de") to LocalizedStrings(mapOf("destination" to "Ziel")),
                    ),
            )

        assertThat(pass.type).isEqualTo(PassType.BoardingPass)
        assertThat(pass.locales).hasSize(2)
        // All five PassTypes referenced so removal breaks compilation, not just the one we instantiated.
        val allTypes =
            setOf(
                PassType.BoardingPass,
                PassType.EventTicket,
                PassType.Coupon,
                PassType.StoreCard,
                PassType.Generic,
            )
        assertThat(allTypes).hasSize(PassType.entries.size)
        // Ditto for BarcodeFormat.
        val allFormats =
            setOf(
                BarcodeFormat.QR,
                BarcodeFormat.PDF417,
                BarcodeFormat.Aztec,
                BarcodeFormat.Code128,
            )
        assertThat(allFormats).hasSize(BarcodeFormat.entries.size)
    }

    /**
     * Pass equality must use structural (content) comparison on image bytes, not
     * `ByteArray` reference equality. Two passes built from byte-identical (but distinct)
     * arrays must compare equal — otherwise consumers using `==` for distinct-until-changed
     * diffing get silently surprised. This is the test for the [ImageBytes] wrapper that
     * exists to fix the landmine.
     */
    @Test
    fun passEqualityUsesStructuralImageComparison() {
        val a = samplePass(imageBytes = byteArrayOf(1, 2, 3))
        val b = samplePass(imageBytes = byteArrayOf(1, 2, 3))
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())

        val different = samplePass(imageBytes = byteArrayOf(9, 9, 9))
        assertThat(a).isNotEqualTo(different)
    }

    private fun samplePass(imageBytes: ByteArray = byteArrayOf(0)): Pass =
        Pass(
            type = PassType.Generic,
            serialNumber = "S",
            description = "D",
            organizationName = "O",
            expirationDate = null,
            voided = false,
            colors = PassColors(foreground = null, background = null, label = null),
            frontFields = PassFields(),
            backFields = emptyList(),
            barcode = null,
            images = mapOf(ImageRole.Icon to ImageBytes(imageBytes)),
            locales = emptyMap(),
        )
}
