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
