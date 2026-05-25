package `is`.walt.passes.ui

import `is`.walt.passes.core.ColorValue
import `is`.walt.passes.core.LocalizedStrings
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassColors
import `is`.walt.passes.core.PassField
import `is`.walt.passes.core.PassFields
import `is`.walt.passes.core.PassLocale
import `is`.walt.passes.core.PassType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM-pure coverage for the [resolvePassDisplayIdentity] resolver (wpass-up1).
 * The composable surface that consumes it is exercised by `TrustClaimSurfaceTest`;
 * these tests lock the trust-caption rule (ADR 0007 D5 / D6) at the value-class
 * boundary so a consumer reading the resolver in a non-Compose context (e.g. a
 * list-row title driven by walt-android's `PassSummaryRow`) gets the same
 * contract.
 *
 * "Fenced" below means each returned line is wrapped in U+2068 / U+2069 (FSI/PDI),
 * the same isolation `PassIdentityBlock` has always applied. See
 * `passes-ui-core::BidiIsolation`.
 */
class PassDisplayIdentityTest {

    @Test
    fun nullOverrideReturnsFencedOrgNameAsPrimaryAndNullEyebrow() {
        val identity = resolvePassDisplayIdentity(pass = passFixture(), userLabel = null)
        assertThat(identity.primary).isEqualTo("⁨Acme⁩")
        assertThat(identity.eyebrow).isNull()
    }

    @Test
    fun blankOverrideIsTreatedAsNoOverride() {
        val identity = resolvePassDisplayIdentity(pass = passFixture(), userLabel = "   ")
        assertThat(identity.primary).isEqualTo("⁨Acme⁩")
        assertThat(identity.eyebrow).isNull()
    }

    @Test
    fun emptyOverrideIsTreatedAsNoOverride() {
        val identity = resolvePassDisplayIdentity(pass = passFixture(), userLabel = "")
        assertThat(identity.primary).isEqualTo("⁨Acme⁩")
        assertThat(identity.eyebrow).isNull()
    }

    @Test
    fun distinctOverrideReturnsBothLinesFencedIndependently() {
        val identity = resolvePassDisplayIdentity(
            pass = passFixture(),
            userLabel = "Mom's flight home",
        )
        assertThat(identity.primary).isEqualTo("⁨Mom's flight home⁩")
        assertThat(identity.eyebrow).isEqualTo("⁨Acme⁩")
    }

    @Test
    fun overrideTrimmedBeforeFencing() {
        // Leading/trailing whitespace is normalized but the override otherwise survives.
        val identity = resolvePassDisplayIdentity(
            pass = passFixture(),
            userLabel = "  Mom's flight  ",
        )
        assertThat(identity.primary).isEqualTo("⁨Mom's flight⁩")
        assertThat(identity.eyebrow).isEqualTo("⁨Acme⁩")
    }

    @Test
    fun overrideEqualToOrgNameIsSuppressed() {
        // Case-insensitive ASCII compare after trim collapses to a single line. The
        // trust rule is satisfied trivially: primary IS the signed identity.
        val identity = resolvePassDisplayIdentity(pass = passFixture(), userLabel = "ACME")
        assertThat(identity.primary).isEqualTo("⁨Acme⁩")
        assertThat(identity.eyebrow).isNull()
    }

    @Test
    fun overrideEqualToOrgNameWithSurroundingWhitespaceIsSuppressed() {
        val identity = resolvePassDisplayIdentity(pass = passFixture(), userLabel = "  acme  ")
        assertThat(identity.primary).isEqualTo("⁨Acme⁩")
        assertThat(identity.eyebrow).isNull()
    }

    @Test
    fun overrideSubstitutesOrgNameThroughLocalizedStrings() {
        // Per `wpass-38y`, organizationName MUST substitute through pass.strings on
        // every surface that renders it. The resolver owns the lookup so consumers
        // without an ambient `LocalLocalizedStrings` (e.g. walt-android's tile) still
        // see the localized signed identity in the eyebrow.
        val identity = resolvePassDisplayIdentity(
            pass = localizedFixture(
                organizationName = "#ORGNAME#",
                locales = mapOf(PassLocale("en") to LocalizedStrings(mapOf("#ORGNAME#" to "Tixly"))),
            ),
            userLabel = "Mom's flight",
        )
        assertThat(identity.primary).isEqualTo("⁨Mom's flight⁩")
        assertThat(identity.eyebrow).isEqualTo("⁨Tixly⁩")
    }

    @Test
    fun nullOverrideStillSubstitutesOrgNameThroughLocalizedStrings() {
        val identity = resolvePassDisplayIdentity(
            pass = localizedFixture(
                organizationName = "#ORGNAME#",
                locales = mapOf(PassLocale("en") to LocalizedStrings(mapOf("#ORGNAME#" to "Tixly"))),
            ),
            userLabel = null,
        )
        assertThat(identity.primary).isEqualTo("⁨Tixly⁩")
        assertThat(identity.eyebrow).isNull()
    }

    @Test
    fun overrideEqualToLocalizedOrgNameIsSuppressed() {
        // The equality compare is against the SUBSTITUTED org name, not the raw key.
        // A user who renames a pass to the localized issuer name still trips the
        // suppression path.
        val identity = resolvePassDisplayIdentity(
            pass = localizedFixture(
                organizationName = "#ORGNAME#",
                locales = mapOf(PassLocale("en") to LocalizedStrings(mapOf("#ORGNAME#" to "Tixly"))),
            ),
            userLabel = "tixly",
        )
        assertThat(identity.primary).isEqualTo("⁨Tixly⁩")
        assertThat(identity.eyebrow).isNull()
    }

    @Test
    fun localeFallbackSelectsLanguageMatchWhenExactMissing() {
        // Apple's PKPASS locale chain: an exact `en-US` lookup falls back to `en` when
        // only the language-only table is present. Resolver delegates to
        // `Pass.resolveLocalizedStrings` for this; the test pins the resolver wires it
        // up correctly rather than re-doing the chain itself.
        val identity = resolvePassDisplayIdentity(
            pass = localizedFixture(
                organizationName = "#ORGNAME#",
                locales = mapOf(PassLocale("en") to LocalizedStrings(mapOf("#ORGNAME#" to "Tixly"))),
            ),
            userLabel = null,
            locale = PassLocale("en-US"),
        )
        assertThat(identity.primary).isEqualTo("⁨Tixly⁩")
    }

    @Test
    fun missingOrgNameKeyFallsThroughRawForFence() {
        // If pass.strings is empty (no locales), the org name is fenced verbatim.
        val identity = resolvePassDisplayIdentity(
            pass = passFixture(),
            userLabel = null,
            locale = PassLocale("en"),
        )
        assertThat(identity.primary).isEqualTo("⁨Acme⁩")
    }

    @Test
    fun bidiCharactersInOverrideAreFencedNotStripped() {
        // The fence is defense-in-depth; the resolver MUST NOT silently filter or
        // alter bidi-override characters in the override. The fence isolates them.
        val rtl = "‮BAD"
        val identity = resolvePassDisplayIdentity(pass = passFixture(), userLabel = rtl)
        assertThat(identity.primary).isEqualTo("⁨$rtl⁩")
        assertThat(identity.eyebrow).isEqualTo("⁨Acme⁩")
    }

    @Test
    fun resolverIsPureAndCallableOutsideCompose() {
        // No @Composable annotation needed -- the signature must remain JVM-only so
        // walt-android's PassSummaryRow can call it from a non-Compose code path.
        // PassLocale is a value class so the JVM method name carries a mangling
        // suffix; match by prefix (mirrors PublicApiSurfaceTest's lock for
        // PassIdentityBlock). Skip the synthetic `$default` overload Kotlin emits
        // for the default-arg entry point; the direct entry is the trust-contract
        // surface.
        //
        // NOTE on failure mode: this lock is sensitive to Kotlin's value-class /
        // default-arg name mangling. If a future toolchain changes the suffix
        // shape and this test breaks, the most likely cause is mangling churn,
        // not a regressed contract. Re-derive the prefix from `javap -p` on the
        // compiled `PassDisplayIdentityKt` before assuming the resolver itself
        // changed. Becoming @Composable, in contrast, would bump parameterCount
        // by two (Composer + $changed) -- the assertion below catches that.
        val method = Class.forName("is.walt.passes.ui.PassDisplayIdentityKt")
            .declaredMethods
            .first { it.name.startsWith("resolvePassDisplayIdentity") && !it.name.contains("\$default") }
        assertThat(method.parameterCount).isEqualTo(3)
    }

    private fun passFixture(): Pass = Pass(
        type = PassType.Generic,
        serialNumber = "0",
        description = "fixture",
        organizationName = "Acme",
        expirationDate = null,
        voided = false,
        colors = PassColors(
            foreground = ColorValue(0x000000),
            background = ColorValue(0xFFFFFF),
            label = ColorValue(0x444444),
        ),
        frontFields = PassFields(
            primary = listOf(PassField(key = "p", label = "From", value = "JFK")),
        ),
        backFields = emptyList(),
        barcode = null,
        images = emptyMap(),
        locales = emptyMap(),
    )

    private fun localizedFixture(
        organizationName: String,
        locales: Map<PassLocale, LocalizedStrings>,
    ): Pass = Pass(
        type = PassType.Generic,
        serialNumber = "0",
        description = "fixture",
        organizationName = organizationName,
        expirationDate = null,
        voided = false,
        colors = PassColors(
            foreground = ColorValue(0x000000),
            background = ColorValue(0xFFFFFF),
            label = ColorValue(0x444444),
        ),
        frontFields = PassFields(
            primary = listOf(PassField(key = "p", label = "From", value = "JFK")),
        ),
        backFields = emptyList(),
        barcode = null,
        images = emptyMap(),
        locales = locales,
    )
}
