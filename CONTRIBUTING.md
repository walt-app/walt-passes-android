# Contributing

Walt-passes is an open-source carve-out from the closed-source [Walt](https://walt.is) Android wallet. Its purpose is **transparency-for-trust**: every security-and-privacy claim Walt makes about pass handling is implemented in code that lives here. That goal shapes what kinds of contributions fit.

## What fits this repository

- Bug fixes in PKPASS parsing, signature verification, encrypted storage, or the security-relevant UI flows listed in [`SECURITY.md`](SECURITY.md).
- Additional parser hardening, fuzzing harnesses, or test coverage for malicious inputs.
- Improvements that make security-and-privacy behavior easier to audit (clearer code, better naming, more direct trust-claim-to-code mapping).
- Localization, accessibility, and visual polish in `passes-ui`, as long as the security-relevant composables (URL confirmation, expired badge, bounded image rendering) keep their guarantees.

## What does not fit

- Features that pull pass data off the device. There is no `webServiceURL` handling, no telemetry to issuers, no cloud sync. PRs that add network calls in `passes-core` or `passes-storage` will be declined.
- NFC / HCE handling for passes. Passes never register a payment AID; the `nfc` PKPASS field is parsed-and-ignored. This is deliberate to avoid HCE conflicts with Walt's payment feature.
- WebView-based rendering of pass content. HTML in back fields is rendered through an explicit subset and nothing else.
- Generalizing the library beyond what Walt needs. Walt-passes is "Walt's open pass-handling kernel," not a general-purpose PKPASS library. Reuse by other apps is welcome as a side effect; the primary commitment is the audit trail.

If you are unsure whether a contribution fits, open an issue first to discuss.

## Reporting security issues

Do **not** open a public GitHub issue. See [`SECURITY.md`](SECURITY.md) for the disclosure process.

## Development setup

The repo is in pre-alpha. Gradle modules and the build-logic convention plugins are introduced as the architecture phase concludes. Until then:

- Source of truth for architectural intent: [`README.md`](README.md) and the [`CLAUDE.md`](CLAUDE.md) at the repo root.
- The trust-claim-to-module audit map in `README.md` describes where each security-and-privacy claim is intended to live.
- Issue tracking uses [beads](https://github.com/steveyegge/beads) (`bd`). Run `bd ready` to see available work in this repo. The umbrella epic for the Walt Passes feature lives in walt-android (`wlt-0tn`).

## Code style

- 100% Kotlin (no Java).
- Sealed interfaces, not sealed classes.
- `Result<T>` over thrown exceptions for recoverable errors. Throw only for programmer errors.
- StateFlow, not LiveData.
- JUnit 4 + Google Truth for tests.
- `kotlinx.serialization` for JSON.
- BouncyCastle (JVM) for PKCS#7 / X.509.
- `passes-core` has **no** Android framework dependencies.

## Pull request workflow

1. Fork or create a topic branch.
2. Keep PRs focused. One change per PR makes the audit trail readable.
3. Reference the relevant beads issue in the description if one exists.
4. Confirm the change does not regress any of the trust claims listed in `SECURITY.md`. If it does, that is the conversation to have in the PR.
5. CI must be green. Tests, linters, and dependency review all run automatically.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0, the same license as the rest of the repository.
