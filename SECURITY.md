# Security policy

## Reporting a vulnerability

If you believe you have found a security vulnerability in walt-passes, please report it privately. Do **not** open a public GitHub issue.

**Preferred channel**: GitHub private vulnerability reporting.
Open the [Security advisories tab](https://github.com/walt-app/walt-passes/security/advisories/new) of this repository and submit a private advisory. Maintainers receive a notification and the report stays confidential until a fix is published.

**Email fallback**: `security@walt.is` (PGP key forthcoming, see below).

Please include:

- A description of the issue and its impact.
- Steps to reproduce, ideally with a minimal `.pkpass` or code sample.
- Affected version (commit hash or release tag).
- Whether you are willing to be credited in the advisory.

We will acknowledge receipt within **3 business days**, provide an initial assessment within **10 business days**, and aim to ship a fix or mitigation within **90 days** for high-severity issues. We will keep you updated on progress.

## Scope

Walt-passes is the open-source pass-handling kernel that ships inside the [Walt](https://walt.is) Android wallet. Vulnerability reports are most useful when they target one of:

- The PKPASS parser (`passes-core`): ZIP, JSON, image, signature, manifest verification, `.strings` parsing, locale handling.
- Pass data storage (`passes-storage`): encryption-at-rest, key management, deletion, backup exclusion.
- Security-relevant UI flows (`passes-ui`): URL confirmation sheet, HTML subset renderer, bounded image rendering.

Out-of-scope reports (we will close without action):

- Issues in the closed-source Walt Android app outside `core/data-passes` and `feature/passes`. Report those via Walt's separate channel.
- Issues that require a rooted, unlocked-bootloader, or otherwise compromised device.
- Issues in third-party PKPASS files (issuer-side mistakes).
- Pure cryptographic policy disagreements that do not result in a concrete exploit (signature lenience is a documented design decision, see `docs/threat-model.md`).

## Threat model

The threat model lives at [`docs/threat-model.md`](docs/threat-model.md). Reading it before submitting a report helps focus your write-up on residual risk we have not already accepted.

## Supported versions

Walt-passes is pre-alpha. Until a v1.0 release, only the latest commit on `main` is supported. After v1.0, the most recent minor release will receive security updates.

## Disclosure

After a fix is shipped:

- A GitHub Security Advisory is published with the CVE (if assigned), affected versions, severity, and patched version.
- A changelog entry references the advisory.
- Reporters are credited unless they request anonymity.

## PGP key

PGP key for `security@walt.is` will be published here once available. Until then, please use GitHub private vulnerability reporting for sensitive details.
