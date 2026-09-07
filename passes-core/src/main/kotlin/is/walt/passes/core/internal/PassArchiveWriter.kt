package `is`.walt.passes.core.internal

import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.core.Pass
import `is`.walt.passes.core.PassEncodeResult
import `is`.walt.passes.core.ResourceLimit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the unsigned archive behind [`is`.walt.passes.core.PassEncoder]. Member names
 * come from the same tables the parser reads ([ROLE_BY_BASENAME] inverted,
 * [LPROJ_STRINGS_SUFFIX]), so the writer cannot drift from the reader.
 *
 * Every [ParserConfig] limit the parser enforces is checked here, in the same units and
 * where possible through the parser's own guard ([enforceJsonLimits],
 * [readPngDimensions]), so a refusal here is exactly a rejection there.
 */
internal fun writePassArchive(
    pass: Pass,
    config: ParserConfig,
): PassEncodeResult {
    val badTag = pass.locales.keys.firstOrNull { !isSafeLocaleTag(it.tag) }
    if (badTag != null) return PassEncodeResult.InvalidLocaleTag(badTag.tag)
    val members = LinkedHashMap<String, ByteArray>()
    members[PASS_JSON_FILE_NAME] = encodePassJson(pass)
    for ((role, image) in pass.images.entries.sortedBy { it.key.ordinal }) {
        members[BASENAME_BY_ROLE.getValue(role)] = image.bytes
    }
    for ((locale, strings) in pass.locales.entries.sortedBy { it.key.tag }) {
        members["${locale.tag}$LPROJ_STRINGS_SUFFIX"] = encodeStrings(strings)
    }
    val limit =
        jsonLimit(members.getValue(PASS_JSON_FILE_NAME), config)
            ?: imageLimit(pass, config)
            ?: stringsLimit(pass, config)
    return limit?.let { PassEncodeResult.LimitExceeded(it) } ?: zipWithinCaps(members, config)
}

/** Fixed order: pass.json, manifest.json, images by role, strings by tag. */
private fun zipWithinCaps(
    members: Map<String, ByteArray>,
    config: ParserConfig,
): PassEncodeResult {
    val ordered =
        linkedMapOf(
            PASS_JSON_FILE_NAME to members.getValue(PASS_JSON_FILE_NAME),
            MANIFEST_FILE_NAME to encodeManifest(members),
        ) + (members - PASS_JSON_FILE_NAME)
    val limit = entryLimit(ordered, config)
    if (limit != null) return PassEncodeResult.LimitExceeded(limit)
    val bytes = zip(ordered)
    return if (bytes.size > config.maxArchiveBytes) {
        PassEncodeResult.LimitExceeded(ResourceLimit.ArchiveSize)
    } else {
        PassEncodeResult.Success(bytes)
    }
}

private fun jsonLimit(
    passJson: ByteArray,
    config: ParserConfig,
): ResourceLimit? =
    when (enforceJsonLimits(passJson, config)) {
        null -> null
        PassJsonFailure.JsonDepthExceeded -> ResourceLimit.JsonDepth
        else -> ResourceLimit.JsonStringSize
    }

/** Mirrors the parser's pixel cap, including "unreadable IHDR skips the cap". */
private fun imageLimit(
    pass: Pass,
    config: ParserConfig,
): ResourceLimit? {
    val cap = config.maxImagePixelCount.toLong()
    val over =
        pass.images.values.any { image ->
            val dim = readPngDimensions(image.bytes)
            dim != null && (dim.width > cap || dim.height > cap || dim.width * dim.height > cap)
        }
    return if (over) ResourceLimit.ImagePixelCount else null
}

private fun stringsLimit(
    pass: Pass,
    config: ParserConfig,
): ResourceLimit? {
    val valueOver =
        pass.locales.values.any { strings ->
            strings.entries.values.any { conservativeUtf8Bytes(it) > config.maxJsonStringBytes }
        }
    return when {
        pass.locales.size > config.maxLocaleCount -> ResourceLimit.LocaleCount
        valueOver -> ResourceLimit.JsonStringSize
        else -> null
    }
}

private fun entryLimit(
    members: Map<String, ByteArray>,
    config: ParserConfig,
): ResourceLimit? =
    when {
        members.size > config.maxEntries -> ResourceLimit.EntryCount
        members.values.any { it.size > config.maxEntryBytes } -> ResourceLimit.EntrySize
        else -> null
    }

/**
 * Mirrors the extractor's path rules for the single `<tag>.lproj` segment. The UTF-16
 * check keeps `ZipEntry(name)` from throwing on an unpaired surrogate in a hand-built tag.
 */
private fun isSafeLocaleTag(tag: String): Boolean {
    val driveLetter = tag.length >= 2 && tag[1] == ':'
    val wellFormed = Charsets.UTF_8.newEncoder().canEncode(tag)
    return tag.isNotEmpty() && '/' !in tag && '\\' !in tag && !driveLetter && wellFormed
}

private fun encodeManifest(members: Map<String, ByteArray>): ByteArray {
    val sha1 = MessageDigest.getInstance(SHA1_ALGORITHM)
    val root = buildJsonObject { for ((name, bytes) in members) put(name, toHex(sha1.digest(bytes))) }
    return Json.encodeToString(JsonObject.serializer(), root).toByteArray(Charsets.UTF_8)
}

/** Manual loop: `java.util.HexFormat` is API 34 on Android and is not desugared (wpass-g00). */
private fun toHex(bytes: ByteArray): String =
    buildString(bytes.size * 2) {
        for (b in bytes) {
            append(HEX_DIGITS[b.toInt() shr 4 and NIBBLE_MASK])
            append(HEX_DIGITS[b.toInt() and NIBBLE_MASK])
        }
    }

private fun zip(members: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    val stamp = fixedEntryTimeMillis()
    ZipOutputStream(out).use { zos ->
        for ((name, bytes) in members) {
            zos.putNextEntry(ZipEntry(name).apply { time = stamp })
            zos.write(bytes)
            zos.closeEntry()
        }
    }
    return out.toByteArray()
}

/**
 * Local-calendar 1980-01-02 gives zone-independent DOS fields and avoids the
 * `DOSTIME_BEFORE_1980` sentinel that older `ZipEntry.setTime` turns into a zone-dependent extra field.
 */
private fun fixedEntryTimeMillis(): Long =
    LocalDateTime.of(DOS_EPOCH_YEAR, 1, FIXED_ENTRY_DAY, 0, 0)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

internal val BASENAME_BY_ROLE = ROLE_BY_BASENAME.entries.associate { it.value to it.key }

private const val HEX_DIGITS = "0123456789abcdef"
private const val NIBBLE_MASK = 0x0F
private const val DOS_EPOCH_YEAR = 1980
private const val FIXED_ENTRY_DAY = 2
