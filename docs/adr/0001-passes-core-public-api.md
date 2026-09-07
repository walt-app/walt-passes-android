# ADR 0001: passes-core public API

- Status: Accepted
- Date: 2026-05-04
- Tracks: parent epic `wlt-0tn` (walt-android beads); design bead `wlt-ajj`; implementation bead `wpass-epb`
- Decision context: `decision-wlt-0tn-q1` (lenient signature policy), `decision-wlt-0tn-q3` (full localization, secure deletion), `decision-wlt-0tn-q5` (three-module shape)

## Context

`passes-core` is the pure-Kotlin/JVM module that performs all PKPASS parsing, signature verification, and modeling for Walt's pass handling. It must:

- Run identically on Android (Walt's wallet) and on a JVM CI host (audit tooling, fuzzers), so it cannot import `android.*` or `java.util.Locale`.
- Surface trust-relevant information without forcing the consumer to inspect exception messages, since the UI must distinguish *tampered* from *malformed* from *unsupported*.
- Make it structurally impossible for a consumer to log pass content through the telemetry hook, so the trust claim "pass content never appears in logs or telemetry" survives careless integration.
- Apply lenient signature defaults (accept unsigned and self-signed archives, surface their status) per the q1 decision, while keeping a strict mode reachable for tests and audit tooling.

## Decisions

### D1. Sealed-interface result type, not `Result<Pass, Throwable>`

`PassParser.parse` returns `ParseResult`, a sealed interface with four arms: `Success`, `Tampered`, `Malformed`, `Unsupported`. Each failure arm carries a typed reason (e.g. `MalformedReason.ResourceLimitExceeded(ResourceLimit.JsonDepth)`).

Rationale: CLAUDE.md's rule of "Result<T> over exceptions" was written against typical I/O failure spaces. The PKPASS failure space is rich enough that a `Throwable` would erase the partition the UI needs. Tampered and malformed must render differently (a tampered pass is a security event; a malformed one is a corrupt download), and both must be distinguishable from "your build of Walt doesn't know this pass style yet." Sealed interfaces preserve that partition at the type level.

### D2. `SignatureStatus` is the *successful-parse* trust band, not a failure type

Cryptographic *failures* during signature verification produce `ParseResult.Tampered`, not a `SignatureStatus.Invalid` arm. `SignatureStatus` only describes what level of provenance a successfully parsed archive carried: `Unsigned`, `SelfSigned`, `AppleVerified`, `CertChainIncomplete`.

Rationale: collapsing "tampered" and "weak signature" into one type would make it easy for a consumer UI to render them identically and forfeit the q1 lenient policy's purpose. The q1 policy is "accept and warn"; that requires a distinct type for "accepted but warn-worthy."

### D3. `TelemetryGuard` enforces PII safety structurally

`ParseSucceededEvent` and `ParseFailedEvent` contain only enums, counts, and durations. There is no `String` parameter anywhere in the `TelemetryGuard` interface. A consumer cannot call `guard.onParseSucceeded(serialNumber = pass.serialNumber, ...)` because the method signature refuses to accept it.

Rationale: this is the load-bearing implementation of the README trust claim. Documentation-only PII rules drift; type signatures don't. Reviewers should treat any future addition of a `String` (or `Pass`, or `PassField`) parameter to a `TelemetryGuard` method as a security-policy change.

The flattened `SignatureStatusKind` and `ParseFailureKind` enums exist so this telemetry contract can travel through metric backends that prefer string dimensions, without expanding the interface to take arbitrary strings.

### D4. No Android, no `java.time`, no `java.util.Locale`

`passes-core` depends only on the Kotlin stdlib and `kotlinx.serialization`. Time is `PassInstant(epochMillis: Long)`; locales are `PassLocale(tag: String)` carrying a BCP-47 tag verbatim. Color is `ColorValue(rgb: Int)`.

Rationale: lets the same module compile against minSdk-21 Android (without core library desugaring of `java.time`), against a JVM CI fuzzer, and against future KMP targets without per-target shims. Consumers that already depend on Android or kotlinx-datetime do the conversion at the module boundary.

### D5. `ParserConfig` defaults are lenient on trust, restrictive on resources

Trust toggles default to accept-and-surface (`acceptUnsignedArchives = true`, `acceptSelfSignedCertificates = true`). Resource limits default to values that fit any legitimate pass observed in the FOSS field survey but cut off zip-bomb / JSON-bomb shapes far below process-OOM (10 MB archive, 256 entries, 16-deep JSON, 16M-pixel images).

Rationale: q1 establishes the trust posture; the threat model establishes the resource posture. They live in the same config object because a future "strict" mode (audit tooling) wants to flip both kinds of toggle together, and `ParserConfig.Strict` is a single named alternative.

### D6. `explicitApi()` and `public` everywhere

The Kotlin compiler is configured with `explicitApi()`. Every API element carries an explicit `public` modifier so that accidental visibility loss surfaces as a build failure rather than as a slow-burn breakage in a downstream consumer.

Rationale: walt-android imports this module directly. A removed `public` modifier becomes a downstream compile error rather than a silent API change.

## Consequences

- The implementation bead (follow-up to `wpass-epb`) can land the parser body without renegotiating any types; the public surface is fixed.
- `passes-storage` and `passes-ui` can take dependencies on `Pass`, `ParseResult`, `SignatureStatus` today.
- The `TelemetryGuard` contract will need a security-policy review the first time anyone proposes adding a parameter to it. That review gate is the point.
- KMP targets are not enabled today, but nothing in the API surface forbids them; the module is structured so a `kotlin { jvm(); androidTarget(); ... }` block is a one-bead change.

## Open follow-ups

- `wpass-epb` does not implement parsing; the `PassParser.create()` factory currently throws `NotImplementedError`. The implementation bead picks up here.
- Whether `BarcodeFormat` should include `Code128` is settled here as "yes" (consumer support is trivial and walt-android already renders it for non-pass barcodes); revisit if it complicates renderer surface area.
- Cert-chain trust anchors (which Apple WWDR roots ship in the parser, how they rotate) are deferred to the implementation bead's design notes.

## Addendum 2026-09-08: bundle reader surface and the nested-archive class

Tracks: `wpass-59i.2` (reader, PR #237) and `wpass-59i.3` (this addendum) under
parent epic `wpass-59i`; consumer epic walt-android `wlt-lasc`. D1 through D6
stand as written; D5 gains a threat row and a seventh decision freezes the new
surface.

### Amendment to D5: nested archives are a separate amplification class

**What.** A `.pkpasses` bundle, and any consumer superset of it, is a ZIP of
ZIPs. D5's limits bound ONE archive: `ParserConfig` caps the compressed size,
entry count, and per-entry inflation of a single `.pkpass`, and nothing bounds
the sum across inner archives. A bundle of a few hundred inner entries each just
under the per-archive cap inflates to gigabytes while every one of them is
individually legitimate. Symlinks are not a vector at either layer:
`java.util.zip` exposes no symlink attribute and neither reader touches the file
system, so a symlink-shaped entry is plain bytes.

**Mitigation.** `PassBundleReader` reads the outer layer once and applies the
`BundleConfig` caps before any inner archive is opened: `maxArchiveBytes` (outer
compressed bytes, pre-checked against the declared size and then bounded while
streaming; default 256 MiB), `maxEntries` (default 200), `maxEntryBytes`
(inflated bytes per entry; default 10 MiB, `ParserConfig.DEFAULT_MAX_ARCHIVE_BYTES`),
and `maxCumulativeBytes` (inflated bytes summed over every file entry; default
256 MiB). Both inflation caps are checked after every chunk, so a bomb is
stopped mid-entry before a buffer materializes. Entries the allowlist declines
and payloads behind a directory (`/`) name are drained through the same
per-entry and cumulative caps: counted, never handed out. Zip-slip names and
duplicate names reject the bundle. Every input-driven failure is a typed
`BundleRejection` arm (`NotAZipArchive`, `SourceUnreadable`, `UnsafeEntryName`,
`DuplicateEntryName`, `LimitExceeded(BundleLimit)`), and a `BundleReadResult`
carries counts only, so an entry name, which is attacker-controlled text, never
reaches a result or telemetry.

The reader does not open, parse, or validate an inner archive. Each accepted
entry is delivered as bytes and the caller runs `PassParser` on it, so D5's
per-archive limits and the D2 signature path apply to every inner pass
unchanged. The outer caps are additive; nothing at the inner layer is weakened.

**Consequence for consumers.** Under the default config a skipped entry larger
than `maxEntryBytes` rejects the whole bundle, since draining it costs what
accepting it would. A consumer superset that carries documents next to passes
must raise `maxEntryBytes` deliberately, and a consumer that raises
`ParserConfig.maxArchiveBytes` must raise `BundleConfig.maxEntryBytes` with it,
or the reader rejects inner passes the parser would accept.

**Two hardenings to the single-archive path.** Moving the stream chain into
`internal/ArchiveStreams.kt`, shared by `extractSafely` and the bundle reader,
closed two extractor gaps. A payload behind a directory-entry name was inflated
and discarded outside every cap; it now pays `maxEntryBytes`. An `IOException`
from the caller's stream during the magic sniff escaped `PassParser.parse` as a
throw; it now folds into `MalformedReason.NotAZipArchive` (the frozen
`MalformedReason` surface has no `SourceUnreadable` arm; the bundle reader's
newer taxonomy does).

**Status.** Mitigated.

### D7. `PassBundleReader` is the frozen outer-layer surface

The following are public in `is.walt.passes.core` and frozen the way D1 through
D6 freeze the parser surface:

- `PassBundleReader` (fun interface; `read(PassSource, BundleEntrySink): BundleReadResult`).
  Companion: `MIME_TYPE` (`application/vnd.apple.pkpasses`), `FILE_EXTENSION`
  (`pkpasses`), `PASS_ENTRY_EXTENSION` (`pkpass`), `create(BundleConfig = BundleConfig())`.
- `BundleEntrySink` (fun interface) returning `BundleVisit` (`Continue`, `Stop`).
- `BundleEntry` (sealed): `Accepted(name, ordinal, bytes)` and `Skipped(name, ordinal)`.
  Both are plain classes, not data classes, so `toString` never prints the name.
- `PassBundleSniff` (`Bundle`, `NotBundle`, `Undetermined`; `RECOMMENDED_HEADER_BYTES` = 285)
  with the top-level `sniffPassBundle(header)`. A dispatch hint that judges the first
  local file header's name by `BundleEntryAllowlist.PkpassOnly`; never a trust decision.
- `BundleConfig` (data class) with `DEFAULT_MAX_CUMULATIVE_BYTES` (256 MiB),
  `DEFAULT_MAX_ARCHIVE_BYTES` (= cumulative), `DEFAULT_MAX_ENTRIES` (200),
  `DEFAULT_MAX_ENTRY_BYTES` (= `ParserConfig.DEFAULT_MAX_ARCHIVE_BYTES`).
- `BundleEntryAllowlist` (fun interface; sees the name only, never bytes) and
  `BundleEntryAllowlist.PkpassOnly`: root-level `<non-empty stem>.pkpass`, ASCII-only case fold.
- `BundleLimit` (`ArchiveSize`, `EntryCount`, `EntrySize`, `CumulativeSize`) and
  `BundleLimit.limitFrom(BundleConfig)`.
- `BundleReadResult` (sealed): `Completed`, `Stopped`, `Rejected(reason)`, each carrying
  `accepted` and `skipped` counts and nothing else.
- `BundleRejection` (sealed; arms listed above) and `BundleFailureReason` (eight values,
  one per arm and per limit) with `BundleRejection.toFailureReason()`.

Rationale, in the D3 spirit: no `String` travels in any result or failure type,
so the "pass content never appears in logs or telemetry" claim extends to bundle
entry names without a new `TelemetryGuard` method. No bundle event is added to
`TelemetryGuard`; the enum-and-counts result is what a consumer forwards. Adding
a name, path, or bytes field to `BundleReadResult` or `BundleRejection` is a
security-policy change, not an API addition.

### Tests pinning this addendum

| Decision | Test |
|----------|------|
| D5 (cumulative) | `PassBundleReaderTest.cumulativeCapTripsWhenEveryEntryIsIndividuallyUnderCap`, `skippedEntriesStillCountTowardTheCumulativeCap`, `directoryEntryPayloadsAreChargedAgainstTheCumulativeCap` |
| D5 (per-entry, mid-entry stop) | `PassBundleReaderTest.perEntryCapStopsInflatingAZipBombBeforeItMaterializes`, `skippedEntriesStillHonourThePerEntryCap`, `directoryEntryPayloadIsChargedAgainstThePerEntryCap`, `storedEntryOverThePerEntryCapIsRejected` |
| D5 (outer size, count) | `PassBundleReaderTest.declaredSizeOverOuterArchiveCapFailsFastWithoutReading`, `outerArchiveCapIsEnforcedWhileStreamingWhenTheSizeHintLies`, `entryCountCapTripsOnTheEntryPastTheCapAndCountsSkippedEntries` |
| D5 (names) | `PassBundleReaderTest.zipSlipNamesRejectTheWholeBundle`, `zipSlipDirectoryEntryIsRejected`, `duplicateEntryNamesRejectTheBundleAfterTheFirstCopyWasDelivered`, `symlinkShapedEntryIsDeliveredAsPlainBytesNeverResolved`, `entryToStringNeverPrintsTheName` |
| D5 (typed failures) | `PassBundleReaderTest.invalidUtf8EntryNameIsATypedRejectionNotAThrow`; `PassBundleReaderSourceTest.callerStreamThatThrowsOnFirstReadIsATypedRejection`, `callerStreamThatDiesMidStreamIsSourceUnreadableAfterEarlierEntriesWereDelivered` |
| D5 (extractor hardenings) | `SafeArchiveExtractorTest.directoryEntryPayloadIsChargedAgainstTheEntrySizeLimit`, `streamThatThrowsOnFirstReadIsMalformedNotAThrow` |
| D7 (drift) | `PassBundleSurfaceTest.bundleConfigDefaultsAreConservative`, `limitFromReadsTheMatchingConfigField`, `bundleRejectionFlattensToADistinctFailureReasonPerArm`, `mimeAndExtensionConstantsAreApples` |
