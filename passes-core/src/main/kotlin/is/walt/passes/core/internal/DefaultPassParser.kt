package `is`.walt.passes.core.internal

import `is`.walt.passes.core.ImageBytes
import `is`.walt.passes.core.ImageRole
import `is`.walt.passes.core.LocalizedStrings
import `is`.walt.passes.core.MalformedReason
import `is`.walt.passes.core.ParseFailedEvent
import `is`.walt.passes.core.ParseResult
import `is`.walt.passes.core.ParseSucceededEvent
import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.core.PassLocale
import `is`.walt.passes.core.PassParser
import `is`.walt.passes.core.PassSource
import `is`.walt.passes.core.ResourceLimit
import `is`.walt.passes.core.SignatureStatus
import `is`.walt.passes.core.TamperReason
import `is`.walt.passes.core.UnsupportedReason
import `is`.walt.passes.core.toFailureKind
import `is`.walt.passes.core.toKind

/**
 * The single concrete [PassParser] implementation. Stitches the four security-critical
 * slices ([extractSafely], [verifyManifest], [verifySignature], [decodePassJson]) plus
 * [parseStrings] and [readPngDimensions] into a non-throwing pipeline whose every error
 * path lands on a [ParseResult] arm.
 *
 * **Pipeline order is load-bearing.** The shape mirrors the trust hierarchy the public
 * surface partitions along: structural validity (extract) → integrity binding (manifest
 * hash chain) → cryptographic provenance (signature) → semantic content (pass.json,
 * strings, images). Reordering any earlier step past a later one would let a structural
 * attack surface as a tampering / unsupported reason or vice versa, which in turn would
 * mis-route the trust UI.
 *
 * **Concurrency.** The class is intentionally stateless beyond the immutable
 * [ParserConfig]. Every helper takes the work-in-progress entries / bytes as parameters;
 * nothing is cached on the instance. A single [DefaultPassParser] is therefore safe to
 * call from any number of threads concurrently.
 *
 * **Telemetry.** [System.nanoTime] brackets the entire [parse] call; the duration and
 * the type-flattened [ParseFailureKind] / [SignatureStatusKind] flow through to the
 * configured [TelemetryGuard]. Pass content (serial, organization name, field values,
 * barcode message) never enters the event payload — that constraint is enforced
 * structurally by [TelemetryGuard]'s event types, not by discipline here.
 */
internal class DefaultPassParser(private val config: ParserConfig) : PassParser {
    override fun parse(source: PassSource): ParseResult {
        val started = System.nanoTime()
        config.telemetryGuard.onParseStarted()
        val outcome = runPipeline(source)
        emit(outcome.result, started, outcome.archiveBytes)
        return outcome.result
    }

    /**
     * The full pipeline as a flat sequence of stages. Each stage either continues with
     * its produced value or returns the [PipelineOutcome] holding the [ParseResult] and
     * the best available archive-bytes count for telemetry. The branches are
     * deliberately one-screen-tall and read top-to-bottom: a chain of `withX` helpers
     * obscures that the trust hierarchy is just a linear sequence.
     *
     * The `@Suppress("ReturnCount")` is intentional: the pipeline has six stage
     * boundaries, each with its own short-circuit; collapsing them into ≤2 returns
     * either re-introduces the helper-chain or hides the early-exit shape behind a
     * generic monadic plumbing whose payoff for six stages is not obviously worth the
     * indirection.
     */
    @Suppress("ReturnCount")
    private fun runPipeline(source: PassSource): PipelineOutcome {
        val extracted =
            when (val r = extractSafely(source, config)) {
                is ExtractResult.Failure -> return PipelineOutcome(ParseResult.Malformed(r.reason), 0L)
                is ExtractResult.Success -> r
            }
        val archiveBytes = computeArchiveBytes(source, extracted.archiveBytes)
        val entries = extracted.entries
        val manifestBytes =
            when (val r = verifyManifest(entries)) {
                is ManifestVerifyResult.Failed ->
                    return PipelineOutcome(manifestFailureToResult(r.failure), archiveBytes)
                is ManifestVerifyResult.Ok -> r.manifestBytes
            }
        val signatureStatus =
            when (val r = resolveSignature(entries, manifestBytes)) {
                is Phase.Halt -> return PipelineOutcome(r.result, archiveBytes)
                is Phase.Continue -> r.value
            }
        val pass =
            when (val r = decodePassJson(entries, config)) {
                is PassJsonDecodeResult.Failed ->
                    return PipelineOutcome(passJsonFailureToResult(r.failure), archiveBytes)
                is PassJsonDecodeResult.Ok -> r.pass
            }
        val locales =
            when (val r = collectLocales(entries)) {
                is Phase.Halt -> return PipelineOutcome(r.result, archiveBytes)
                is Phase.Continue -> r.value
            }
        val images =
            when (val r = collectImages(entries)) {
                is Phase.Halt -> return PipelineOutcome(r.result, archiveBytes)
                is Phase.Continue -> r.value
            }
        return PipelineOutcome(
            result =
                ParseResult.Success(
                    pass = pass.copy(images = images, locales = locales),
                    signatureStatus = signatureStatus,
                ),
            archiveBytes = archiveBytes,
        )
    }

    private fun resolveSignature(
        entries: Map<String, ByteArray>,
        manifestBytes: ByteArray,
    ): Phase<SignatureStatus> {
        val signatureBytes = entries[SIGNATURE_FILE_NAME]
        if (signatureBytes == null) {
            // No signature blob. The lenient default surfaces this as Unsigned; strict
            // mode treats absence as a security event. Routing through
            // SignatureCryptoFailure (rather than coining a new "Unsigned" tamper arm)
            // matches decision-wlt-0tn-q1: strict refuses unsigned input *because* it
            // refuses to trust unsigned cryptographic provenance, which is the same
            // category of refusal as "the bytes did not parse as a CMS envelope."
            return if (config.acceptUnsignedArchives) {
                Phase.Continue(SignatureStatus.Unsigned)
            } else {
                Phase.Halt(ParseResult.Tampered(TamperReason.SignatureCryptoFailure))
            }
        }
        return when (val r = verifySignature(signatureBytes, manifestBytes, config)) {
            is SignatureVerifyResult.Ok -> Phase.Continue(r.status)
            is SignatureVerifyResult.Failed -> Phase.Halt(ParseResult.Tampered(r.reason))
        }
    }

    private fun collectLocales(entries: Map<String, ByteArray>): Phase<LocaleMap> {
        // Two-pass over the entries: first identify the locale-bearing names so the cap
        // can fire before any .strings parsing happens, then parse. Doing the cap check
        // up-front avoids parsing N strings files just to reject N+1.
        val stringsEntries =
            entries.entries
                .mapNotNull { (name, bytes) ->
                    val locale = lprojStringsLocaleOrNull(name) ?: return@mapNotNull null
                    locale to bytes
                }
        return if (stringsEntries.size > config.maxLocaleCount) {
            Phase.Halt(
                ParseResult.Malformed(MalformedReason.ResourceLimitExceeded(ResourceLimit.LocaleCount)),
            )
        } else {
            parseStringsEntries(stringsEntries)
        }
    }

    private fun parseStringsEntries(stringsEntries: List<Pair<String, ByteArray>>): Phase<LocaleMap> {
        val map = LinkedHashMap<PassLocale, LocalizedStrings>(stringsEntries.size)
        var failure: ParseResult? = null
        for ((locale, bytes) in stringsEntries) {
            if (failure != null) break
            when (val r = parseStrings(bytes, config)) {
                is StringsResult.Ok -> map[PassLocale(locale)] = r.strings
                is StringsResult.Failed -> failure = stringsFailureToResult(r.failure)
            }
        }
        return failure?.let { Phase.Halt(it) } ?: Phase.Continue(map)
    }

    private fun collectImages(entries: Map<String, ByteArray>): Phase<Map<ImageRole, ImageBytes>> {
        // Pre-filter to top-level role images so the loop body has a single jump
        // statement (the break-on-failure). Localized images (under `<locale>.lproj/`)
        // are silently dropped — ADR 0001 does not yet model locale-aware image
        // binding, and the renderer consumes only the top-level role images today.
        val candidates =
            entries.entries.mapNotNull { (name, bytes) ->
                val role = topLevelImageRole(name) ?: return@mapNotNull null
                role to bytes
            }
        val map = LinkedHashMap<ImageRole, ImageBytes>(candidates.size)
        var failure: ParseResult? = null
        for ((role, bytes) in candidates) {
            if (failure != null) break
            val limitTrip = pngPixelLimitFailure(bytes)
            if (limitTrip != null) failure = limitTrip else map[role] = ImageBytes(bytes)
        }
        return failure?.let { Phase.Halt(it) } ?: Phase.Continue(map)
    }

    private fun topLevelImageRole(name: String): ImageRole? {
        val isTopLevelPng = '/' !in name && name.endsWith(PNG_EXTENSION, ignoreCase = true)
        return if (isTopLevelPng) imageRoleForBasename(name) else null
    }

    private fun pngPixelLimitFailure(bytes: ByteArray): ParseResult? {
        // A PNG whose IHDR is unreadable is treated as "skip the pixel cap" rather
        // than malformed: the bytes are inert here (the renderer will surface a decode
        // failure downstream), and per-entry size is already bounded by maxEntryBytes
        // upstream. Rejecting on unreadable IHDR would force the parser to police PNG
        // structural validity, which is outside its remit.
        val dim = readPngDimensions(bytes) ?: return null
        val limit = config.maxImagePixelCount.toLong()
        // Defense against u32 IHDR width × height overflowing Long. A signed-Long
        // multiplication of two values near u32 max would wrap silently — and a
        // wrapped (negative) result would compare ≤ limit, bypassing the cap. The
        // axis-pre-check rules out that path: if either axis already exceeds the
        // cap, the product certainly does. Otherwise both axes are ≤ limit ≤
        // Int.MAX_VALUE (the field's declared type is `Int`), so the product is at
        // most ~4.6×10¹⁸, comfortably under Long.MAX_VALUE ≈ 9.22×10¹⁸.
        val exceedsAxis = dim.width > limit || dim.height > limit
        val exceedsProduct = !exceedsAxis && dim.width * dim.height > limit
        return if (exceedsAxis || exceedsProduct) {
            ParseResult.Malformed(MalformedReason.ResourceLimitExceeded(ResourceLimit.ImagePixelCount))
        } else {
            null
        }
    }

    private fun emit(
        result: ParseResult,
        startedNanos: Long,
        archiveBytes: Long,
    ) {
        val durationMillis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        when (result) {
            is ParseResult.Success ->
                config.telemetryGuard.onParseSucceeded(
                    ParseSucceededEvent(
                        passType = result.pass.type,
                        signatureStatus = result.signatureStatus.toKind(),
                        archiveBytes = archiveBytes,
                        durationMillis = durationMillis,
                        imageCount = result.pass.images.size,
                        localeCount = result.pass.locales.size,
                    ),
                )
            else ->
                config.telemetryGuard.onParseFailed(
                    ParseFailedEvent(
                        // toFailureKind() returns null only for Success, which the
                        // outer `when` already routed; the !! is a category check, not
                        // a runtime risk. A future ParseResult arm that breaks this
                        // surfaces as an immediate test failure here, not a silent
                        // observability hole.
                        outcome = result.toFailureKind()!!,
                        durationMillis = durationMillis,
                    ),
                )
        }
    }

    /**
     * The pipeline carries both the [result] and the archive-bytes count separately
     * because telemetry's [ParseSucceededEvent.archiveBytes] is populated only on
     * success, but the count is computed at extraction time — before the call site
     * knows which arm of [ParseResult] it will return.
     */
    private data class PipelineOutcome(val result: ParseResult, val archiveBytes: Long)

    /**
     * Continuation outcome of any pipeline stage that can short-circuit with a
     * pre-baked [ParseResult]. Replaces what was previously three near-identical
     * sealed types ([resolveSignature]'s, locale-collection's, image-collection's
     * outputs) — the shape is the same in every case, so factoring out the type
     * removes duplicated arms whose only difference was the wrapped value's static
     * type.
     */
    private sealed interface Phase<out T> {
        data class Continue<out T>(val value: T) : Phase<T>

        data class Halt(val result: ParseResult) : Phase<Nothing>
    }
}

/**
 * Resolves the archive-bytes value reported on [ParseSucceededEvent]. Sources we know
 * exactly (a [PassSource.Bytes] holds the whole array; a [PassSource.Stream] with a
 * caller-supplied [PassSource.Stream.sizeHintBytes] asserts its full length) report
 * that exact number; a hint-less [PassSource.Stream] falls back to the extractor's
 * measured count, which is "bytes consumed by the zip pipeline" — not perfectly
 * equal to the file's on-disk length (the central directory + EOCD record is
 * truncated when [java.util.zip.ZipInputStream] sees the central-directory signature)
 * but far closer to truth than the prior `0L` placeholder.
 */
private fun computeArchiveBytes(
    source: PassSource,
    extractedBytes: Long,
): Long =
    when (source) {
        is PassSource.Bytes -> source.bytes.size.toLong()
        is PassSource.Stream -> source.sizeHintBytes ?: extractedBytes
    }

/**
 * Maps a top-level PKPASS image basename onto its [ImageRole]. Unknown basenames return
 * `null` so the caller can drop them silently — PKPASS allows extra files alongside the
 * known roles, and the trust claim does not ride on rejecting them. The map is
 * case-insensitive on the basename only; the extractor's allowlist already restricted
 * names to `*.png`.
 */
private fun imageRoleForBasename(name: String): ImageRole? = ROLE_BY_BASENAME[name.lowercase()]

/**
 * The hash-mismatch arm is the only manifest failure that constitutes tampering: the
 * archive is structurally valid, but a file's bytes differ from what the manifest
 * declared. Every other arm is structural malformedness collapsed onto
 * [MalformedReason.MissingManifest] / [MalformedReason.InvalidManifest] until
 * `wpass-n6g` lands dedicated arms for each shape.
 */
private fun manifestFailureToResult(failure: ManifestFailure): ParseResult =
    when (failure) {
        is ManifestFailure.Missing -> ParseResult.Malformed(MalformedReason.MissingManifest)
        is ManifestFailure.HashMismatch -> ParseResult.Tampered(TamperReason.FileHashMismatch)
        is ManifestFailure.InvalidJson,
        is ManifestFailure.InvalidShape,
        is ManifestFailure.InvalidHashFormat,
        is ManifestFailure.SelfReferentialEntry,
        is ManifestFailure.ExtraEntry,
        is ManifestFailure.MissingEntry,
        -> ParseResult.Malformed(MalformedReason.InvalidManifest)
    }

private fun passJsonFailureToResult(failure: PassJsonFailure): ParseResult =
    when (failure) {
        is PassJsonFailure.Missing -> ParseResult.Malformed(MalformedReason.MissingPassJson)
        is PassJsonFailure.InvalidJson -> ParseResult.Malformed(MalformedReason.InvalidPassJson)
        is PassJsonFailure.InvalidShape -> ParseResult.Malformed(MalformedReason.InvalidPassJson)
        is PassJsonFailure.JsonDepthExceeded ->
            ParseResult.Malformed(MalformedReason.ResourceLimitExceeded(ResourceLimit.JsonDepth))
        is PassJsonFailure.JsonStringTooLong ->
            ParseResult.Malformed(MalformedReason.ResourceLimitExceeded(ResourceLimit.JsonStringSize))
        is PassJsonFailure.UnknownFormatVersion ->
            ParseResult.Unsupported(UnsupportedReason.FormatVersion(failure.version))
        is PassJsonFailure.UnknownPassStyle ->
            ParseResult.Unsupported(UnsupportedReason.UnknownPassStyle(failure.raw))
    }

private fun stringsFailureToResult(failure: StringsFailure): ParseResult =
    when (failure) {
        is StringsFailure.ValueTooLong ->
            ParseResult.Malformed(MalformedReason.ResourceLimitExceeded(ResourceLimit.JsonStringSize))
        is StringsFailure.InvalidEncoding,
        is StringsFailure.UnterminatedString,
        is StringsFailure.UnterminatedComment,
        is StringsFailure.BadStructure,
        is StringsFailure.BadEscape,
        -> ParseResult.Malformed(MalformedReason.InvalidStrings)
    }

private fun lprojStringsLocaleOrNull(name: String): String? {
    // A valid PKPASS keeps locale directories at the archive root, so the locale
    // segment must be non-empty and slash-free. Path traversal is already rejected
    // upstream; this guard is the structural-shape check for a top-level lproj.
    if (!name.endsWith(LPROJ_STRINGS_SUFFIX)) return null
    val locale = name.substring(0, name.length - LPROJ_STRINGS_SUFFIX.length)
    return locale.takeUnless { it.isEmpty() || '/' in it }
}

private val ROLE_BY_BASENAME: Map<String, ImageRole> =
    mapOf(
        "logo.png" to ImageRole.Logo,
        "logo@2x.png" to ImageRole.LogoRetina,
        "logo@3x.png" to ImageRole.LogoSuperRetina,
        "icon.png" to ImageRole.Icon,
        "icon@2x.png" to ImageRole.IconRetina,
        "icon@3x.png" to ImageRole.IconSuperRetina,
        "strip.png" to ImageRole.Strip,
        "strip@2x.png" to ImageRole.StripRetina,
        "strip@3x.png" to ImageRole.StripSuperRetina,
        "background.png" to ImageRole.Background,
        "background@2x.png" to ImageRole.BackgroundRetina,
        "background@3x.png" to ImageRole.BackgroundSuperRetina,
        "thumbnail.png" to ImageRole.Thumbnail,
        "thumbnail@2x.png" to ImageRole.ThumbnailRetina,
        "thumbnail@3x.png" to ImageRole.ThumbnailSuperRetina,
        "footer.png" to ImageRole.Footer,
        "footer@2x.png" to ImageRole.FooterRetina,
        "footer@3x.png" to ImageRole.FooterSuperRetina,
    )

/**
 * Local typealiases keep the [Phase] generic instantiations short enough to fit on a
 * single function-signature line, which the project's combined ktlint /detekt rules
 * (multi-line argument lists must trail-comma; single-line signatures must fit under
 * the line cap) otherwise force into incompatible shapes.
 */
private typealias LocaleMap = Map<`is`.walt.passes.core.PassLocale, `is`.walt.passes.core.LocalizedStrings>

private const val PNG_EXTENSION = ".png"
private const val LPROJ_STRINGS_SUFFIX = ".lproj/pass.strings"
private const val NANOS_PER_MILLI = 1_000_000L
