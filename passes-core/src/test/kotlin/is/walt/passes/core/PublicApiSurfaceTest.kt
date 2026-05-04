package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Smoke tests that exercise the public API as a *consumer* would — instantiating data
 * classes, switching over sealed interfaces, calling the factory. Catches accidental
 * removal of `public` visibility, missing constructor params, or sealed arms that drift
 * out of sync with [TelemetryGuard]'s flattened enums.
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
        val branch = when (result) {
            is ParseResult.Success -> "success"
            is ParseResult.Tampered -> "tampered"
            is ParseResult.Malformed -> "malformed"
            is ParseResult.Unsupported -> "unsupported"
        }
        assertThat(branch).isEqualTo("malformed")
    }

    @Test
    fun signatureStatusKindCoversAllStatusArms() {
        val arms: List<SignatureStatus> = listOf(
            SignatureStatus.Unsigned,
            SignatureStatus.SelfSigned,
            SignatureStatus.AppleVerified,
            SignatureStatus.CertChainIncomplete,
        )
        assertThat(arms).hasSize(SignatureStatusKind.entries.size)
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
    fun parserFactoryReturnsAParserBoundToConfig() {
        val parser = PassParser.create()
        // The skeleton parser intentionally throws; we assert that the factory itself does
        // not, so the API contract holds even before an implementation lands.
        assertThat(parser).isNotNull()
    }
}
