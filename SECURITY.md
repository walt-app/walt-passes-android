# Security policy

## Reporting a vulnerability

If you think you have found a security issue in walt-passes, please email **cole@walt.is** instead of opening a public GitHub issue.

Helpful things to include:

- A description of the issue and its impact.
- Steps to reproduce, ideally with a minimal `.pkpass` or code sample.
- Affected version (commit hash or release tag).
- Whether you would like to be credited if a fix ships.

## Scope

Walt-passes is the open-source pass-handling kernel that ships inside the [Walt](https://walt.is) Android wallet. The most useful reports target one of:

- The PKPASS parser (`passes-core`): ZIP, JSON, image, signature, manifest verification, `.strings` parsing, locale handling.
- Pass data storage (`passes-storage`): encryption-at-rest, key management, deletion, backup exclusion.
- Security-relevant UI flows (`passes-ui`): URL confirmation sheet, HTML subset renderer, bounded image rendering.

Out of scope:

- Issues in the closed-source Walt Android app outside `core/data-passes` and `feature/passes`.
- Issues that require a rooted, unlocked-bootloader, or otherwise compromised device.
- Issues in third-party PKPASS files (issuer-side mistakes).

## Supported versions

Walt-passes is pre-alpha. Until a v1.0 release, only the latest commit on `main` is supported.
</content>
