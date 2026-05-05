package `is`.walt.passes.storage

import `is`.walt.passes.storage.BackupRulesContract.RequiredExclude
import `is`.walt.passes.storage.BackupRulesContract.Section
import org.xmlpull.v1.XmlPullParser

/**
 * Walks a backup-rules XML document and returns the `<exclude>` entries grouped by
 * section. Accepts both legacy `<full-backup-content>` documents (single implicit
 * section) and modern `<data-extraction-rules>` documents (`<cloud-backup>` and
 * `<device-transfer>` children). `<include>` entries and unknown elements are ignored;
 * only the `<exclude>` discipline is load-bearing for the trust claim.
 *
 * Throws [ParseException] when the document does not have a recognized backup-rules
 * root element. Other XML or IO errors propagate as
 * [org.xmlpull.v1.XmlPullParserException] / [java.io.IOException] for the caller to
 * translate into [BackupRulesAssertion.Outcome.RulesResourceUnreadable].
 *
 * Two deliberate tolerances:
 *
 *  - **Depth-blind exclude collection.** An `<exclude>` nested below the expected level
 *    (e.g., inside an unexpected wrapper element) is still captured into the enclosing
 *    section. The Android backup-rules schema does not permit such nesting, so a
 *    consumer who writes one is shipping a document Android itself would reject; the
 *    assertion does not try to second-guess that.
 *  - **Duplicate-section union.** A malformed document that declares `<cloud-backup>`
 *    twice has its excludes unioned rather than overwritten, so a missing entry in the
 *    first block is not masked by its presence in the second.
 */
internal object BackupRulesXmlParser {

    fun parseRulesXml(parser: XmlPullParser): Map<Section, Set<RequiredExclude>> {
        advanceToFirstStartTag(parser)
        return when (val name = parser.name) {
            Section.FullBackupContent.xmlElement ->
                mapOf(Section.FullBackupContent to collectExcludesUntil(parser, name))
            DATA_EXTRACTION_RULES_ELEMENT -> parseDataExtractionRules(parser)
            else -> throw ParseException("unexpected root element: $name")
        }
    }

    class ParseException(message: String) : RuntimeException(message)

    private fun advanceToFirstStartTag(parser: XmlPullParser) {
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
            event = parser.next()
        }
        if (event != XmlPullParser.START_TAG) {
            throw ParseException("empty rules document")
        }
    }

    private fun parseDataExtractionRules(parser: XmlPullParser): Map<Section, Set<RequiredExclude>> {
        val sections = mutableMapOf<Section, Set<RequiredExclude>>()
        var event = parser.next()
        while (!isEndOfDataExtractionRules(event, parser)) {
            if (event == XmlPullParser.START_TAG) {
                handleDxrChildSection(parser, sections)
            }
            event = parser.next()
        }
        return sections
    }

    private fun handleDxrChildSection(
        parser: XmlPullParser,
        sections: MutableMap<Section, Set<RequiredExclude>>,
    ) {
        val section = sectionByElementName(parser.name) ?: return
        val collected = collectExcludesUntil(parser, parser.name)
        sections[section] = sections[section]?.let { it + collected } ?: collected
    }

    /**
     * Reads `<exclude>` children of the element currently positioned at START_TAG.
     * Returns once the matching END_TAG (named [closingElement]) is consumed.
     */
    private fun collectExcludesUntil(
        parser: XmlPullParser,
        closingElement: String,
    ): Set<RequiredExclude> {
        val excludes = mutableSetOf<RequiredExclude>()
        var event = parser.next()
        while (!isClosingEvent(event, parser, closingElement)) {
            if (event == XmlPullParser.START_TAG && parser.name == EXCLUDE_ELEMENT) {
                val domain = parser.getAttributeValue(null, DOMAIN_ATTRIBUTE)
                val path = parser.getAttributeValue(null, PATH_ATTRIBUTE)
                if (domain != null && path != null) {
                    excludes.add(RequiredExclude(domain, path))
                }
            }
            event = parser.next()
        }
        return excludes
    }

    private fun isClosingEvent(event: Int, parser: XmlPullParser, closingElement: String): Boolean {
        if (event == XmlPullParser.END_DOCUMENT) return true
        return event == XmlPullParser.END_TAG && parser.name == closingElement
    }

    private fun isEndOfDataExtractionRules(event: Int, parser: XmlPullParser): Boolean {
        if (event == XmlPullParser.END_DOCUMENT) return true
        return event == XmlPullParser.END_TAG && parser.name == DATA_EXTRACTION_RULES_ELEMENT
    }

    private fun sectionByElementName(name: String): Section? =
        Section.entries.firstOrNull { it.xmlElement == name }

    private const val DATA_EXTRACTION_RULES_ELEMENT = "data-extraction-rules"
    private const val EXCLUDE_ELEMENT = "exclude"
    private const val DOMAIN_ATTRIBUTE = "domain"
    private const val PATH_ATTRIBUTE = "path"
}
