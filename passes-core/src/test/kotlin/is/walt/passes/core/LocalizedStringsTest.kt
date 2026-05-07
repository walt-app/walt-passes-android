package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocalizedStringsTest {
    @Test
    fun lookupOrSelfReturnsMappedValueWhenKeyPresent() {
        val strings = LocalizedStrings(mapOf("#LABELTICKETNUMBER#" to "Ticket Number"))
        assertThat(strings.lookupOrSelf("#LABELTICKETNUMBER#")).isEqualTo("Ticket Number")
    }

    @Test
    fun lookupOrSelfReturnsRawWhenKeyAbsent() {
        val strings = LocalizedStrings(mapOf("#LABELTICKETNUMBER#" to "Ticket Number"))
        assertThat(strings.lookupOrSelf("52311919")).isEqualTo("52311919")
    }

    @Test
    fun lookupOrSelfReturnsNullWhenInputNull() {
        val strings = LocalizedStrings(mapOf("a" to "b"))
        assertThat(strings.lookupOrSelf(null)).isNull()
    }

    @Test
    fun lookupOrSelfOnEmptyTableIsPassthrough() {
        assertThat(LocalizedStrings.Empty.lookupOrSelf("anything")).isEqualTo("anything")
    }

    @Test
    fun resolveLocalizedStringsExactTagWins() {
        val pass =
            sampleLocalizedPass(
                PassLocale("en") to LocalizedStrings(mapOf("k" to "english")),
                PassLocale("en-US") to LocalizedStrings(mapOf("k" to "us-english")),
            )
        assertThat(pass.resolveLocalizedStrings(PassLocale("en-US")).entries["k"])
            .isEqualTo("us-english")
    }

    @Test
    fun resolveLocalizedStringsLanguageFallbackBcp47() {
        val pass =
            sampleLocalizedPass(
                PassLocale("sv") to LocalizedStrings(mapOf("k" to "swedish")),
                PassLocale("en") to LocalizedStrings(mapOf("k" to "english")),
            )
        // Device locale is sv-FI (Swedish in Finland); exact miss falls to language `sv`,
        // not to the `en` default.
        assertThat(pass.resolveLocalizedStrings(PassLocale("sv-FI")).entries["k"])
            .isEqualTo("swedish")
    }

    @Test
    fun resolveLocalizedStringsLanguageFallbackLegacyUnderscoreForm() {
        val pass =
            sampleLocalizedPass(
                PassLocale("de") to LocalizedStrings(mapOf("k" to "german")),
            )
        // Java's Locale.toString() emits `de_DE`-style; passes-core does not impose BCP 47
        // on the consumer, so the underscore form must language-split too.
        assertThat(pass.resolveLocalizedStrings(PassLocale("de_DE")).entries["k"])
            .isEqualTo("german")
    }

    @Test
    fun resolveLocalizedStringsFallsBackToEnglish() {
        val pass =
            sampleLocalizedPass(
                PassLocale("en") to LocalizedStrings(mapOf("k" to "english")),
                PassLocale("de") to LocalizedStrings(mapOf("k" to "german")),
            )
        // Device locale is fr-CA; no exact, no `fr` table, so the documented `en`
        // fallback wins over arbitrary first-locale picking.
        assertThat(pass.resolveLocalizedStrings(PassLocale("fr-CA")).entries["k"])
            .isEqualTo("english")
    }

    @Test
    fun resolveLocalizedStringsFallsBackToSmallestTagWhenNoEnglish() {
        val pass =
            sampleLocalizedPass(
                PassLocale("nb") to LocalizedStrings(mapOf("k" to "norwegian")),
                PassLocale("da") to LocalizedStrings(mapOf("k" to "danish")),
            )
        // No exact match, no language-only match, no `en`. Falls back to the locale
        // with the lexicographically-smallest tag (`da` < `nb`), independent of the
        // map's iteration order. Picking deterministically (rather than relying on
        // insertion order, which the Map<,,> contract type does not guarantee) keeps
        // the visible result stable across parser refactors.
        assertThat(pass.resolveLocalizedStrings(PassLocale("fr-CA")).entries["k"])
            .isEqualTo("danish")
    }

    @Test
    fun resolveLocalizedStringsFallbackIsStableForHashMapInputs() {
        // The Pass.locales contract is a plain Map; a future parser that hands a
        // HashMap (or anything else without insertion-order iteration) must not
        // change which fallback locale we pick. This test pins the contract.
        val locales =
            HashMap<PassLocale, LocalizedStrings>().apply {
                put(PassLocale("nb"), LocalizedStrings(mapOf("k" to "norwegian")))
                put(PassLocale("da"), LocalizedStrings(mapOf("k" to "danish")))
                put(PassLocale("sv"), LocalizedStrings(mapOf("k" to "swedish")))
            }
        val pass =
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
                images = emptyMap(),
                locales = locales,
            )
        assertThat(pass.resolveLocalizedStrings(PassLocale("fr-CA")).entries["k"])
            .isEqualTo("danish")
    }

    @Test
    fun resolveLocalizedStringsReturnsEmptyWhenPassHasNoLocales() {
        val pass = sampleLocalizedPass()
        assertThat(pass.resolveLocalizedStrings(PassLocale("en")))
            .isEqualTo(LocalizedStrings.Empty)
    }

    /**
     * The chroniques pass (pass.com.tixly) regression: 8 lprojs (da, de, en, fi, is, nb,
     * nl, sv) and labels declared as `#LABELKEY#` placeholders. wpass-38y. After the
     * fix, the resolved English table substitutes the placeholder to its human label.
     */
    @Test
    fun chroniquesFixtureSubstitutesLabelPlaceholders() {
        val pass =
            sampleLocalizedPass(
                PassLocale("en") to
                    LocalizedStrings(
                        mapOf(
                            "#LABELTICKETNUMBER#" to "Ticket Number",
                            "#LABELORDERNUMBER#" to "Order Number",
                            "#LABELPRICEZONE#" to "Price Zone",
                        ),
                    ),
                PassLocale("da") to LocalizedStrings(emptyMap()),
                PassLocale("de") to LocalizedStrings(emptyMap()),
                PassLocale("fi") to LocalizedStrings(emptyMap()),
                PassLocale("is") to LocalizedStrings(emptyMap()),
                PassLocale("nb") to LocalizedStrings(emptyMap()),
                PassLocale("nl") to LocalizedStrings(emptyMap()),
                PassLocale("sv") to LocalizedStrings(emptyMap()),
            )
        val strings = pass.resolveLocalizedStrings(PassLocale("en"))
        assertThat(strings.lookupOrSelf("#LABELTICKETNUMBER#")).isEqualTo("Ticket Number")
        assertThat(strings.lookupOrSelf("#LABELORDERNUMBER#")).isEqualTo("Order Number")
        assertThat(strings.lookupOrSelf("#LABELPRICEZONE#")).isEqualTo("Price Zone")
        // Dynamic values (the actual ticket digits) must pass through unchanged.
        assertThat(strings.lookupOrSelf("52311919")).isEqualTo("52311919")
    }

    private fun sampleLocalizedPass(vararg locales: Pair<PassLocale, LocalizedStrings>): Pass =
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
            images = emptyMap(),
            locales = linkedMapOf(*locales),
        )
}
