# About this repository

walt-passes-android is an open-source carve-out from the closed-source [Walt](https://walt.is) Android wallet. It is published for one reason: **transparency-for-trust**. Every security-and-privacy claim Walt makes about pass handling is implemented in code that lives here, so users and security researchers can read exactly what Walt does with their pass data.

**This repository is published for auditability, not for external contribution.** It is not a community project. The canonical source of this code is Walt's internal development process; this repository is a faithful mirror of the trust-claim-bearing code, kept in the open so it can be verified — not a venue for collaborative development.

## Pull requests are not accepted

External pull requests — features, refactors, fixes, documentation — are not accepted and will be closed unmerged. This is not a judgment on any individual change. It is structural: the code Walt ships comes from its internal process, and merging outside patches here would fork the audit trail from the code that actually runs in the app. The whole value of this repository is that what you read here *is* what Walt ships; accepting outside PRs would undermine that. Please do not invest time in a PR expecting it to be merged.

## Reporting security issues

Security disclosure is the one form of outside input that is actively wanted. If you find a vulnerability, **do not** open a public GitHub issue — see [`SECURITY.md`](SECURITY.md) for the private disclosure process. Reports that demonstrate a gap between a stated trust claim and the code are especially valuable.

## Flagging an inconsistency

If you notice a non-security discrepancy between a documented trust claim and the code, you are welcome to open an issue describing it, purely as a signal for the maintainers. There is no commitment to triage on any timeline, and any resulting change lands through Walt's internal process (and is then mirrored here), not through an external pull request.

## Building locally (for audit and verification)

Researchers who want to build and run the code to verify its behavior:

- Architectural intent and the trust-claim-to-module audit map live in [`README.md`](README.md) and the [`CLAUDE.md`](CLAUDE.md) at the repo root.
- The [`SECURITY.md`](SECURITY.md) trust claims map each security-and-privacy guarantee to the code that implements it.
- Issue tracking uses [beads](https://github.com/steveyegge/beads) (`bd`); the history is in `.beads/` for transparency into how the code evolved.

## How the code is written

These conventions are documented so the code reads consistently for auditors, not as contributor onboarding:

- 100% Kotlin (no Java).
- Sealed interfaces, not sealed classes.
- `Result<T>` over thrown exceptions for recoverable errors. Throw only for programmer errors.
- StateFlow, not LiveData.
- JUnit 4 + Google Truth for tests.
- `kotlinx.serialization` for JSON.
- BouncyCastle (JVM) for PKCS#7 / X.509.
- `passes-core` has **no** Android framework dependencies.

## License

The repository is licensed under the Apache License 2.0. Publishing under a permissive license serves the transparency goal — anyone may read, build, and verify the code — and reuse by other applications is welcome as a side effect. The primary commitment remains the audit trail, not a supported general-purpose library.
