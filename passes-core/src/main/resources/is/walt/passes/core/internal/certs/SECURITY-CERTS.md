# Bundled Apple Trust Anchors

This directory holds the certificates `verifySignature` consults when classifying
a PKCS#7 signature blob's chain. They are loaded at parse time as a static set;
the parser **never** fetches certificates over the network. A valid signature
whose chain extends beyond what is bundled here surfaces as
[`SignatureStatus.CertChainIncomplete`].

`AppleTrustAnchors` resolves these files by the **absolute** classpath path
`/is/walt/passes/core/internal/certs/` so the lookup survives an R8/ProGuard
consumer build repackaging that class. Moving this directory therefore requires
updating `RESOURCE_DIR` in `AppleTrustAnchors.kt`; `AppleTrustAnchorsTest`
consumes that constant directly, so it follows automatically.

## Trust anchors (root CAs)

These are added to the `PKIXBuilderParameters` trust anchor set. A chain that
terminates at any of them yields [`SignatureStatus.AppleVerified`].

| File | Subject | SHA-256 fingerprint | Validity |
|------|---------|---------------------|----------|
| `apple-root-ca.cer` | CN=Apple Root CA, OU=Apple Certification Authority, O=Apple Inc., C=US | `B0:B1:73:0E:CB:C7:FF:45:05:14:2C:49:F1:29:5E:6E:DA:6B:CA:ED:7E:2C:68:C5:BE:91:B5:A1:10:01:F0:24` | 2006-04-25 to 2035-02-09 |
| `apple-root-ca-g2.cer` | CN=Apple Root CA - G2, OU=Apple Certification Authority, O=Apple Inc., C=US | `C2:B9:B0:42:DD:57:83:0E:7D:11:7D:AC:55:AC:8A:E1:94:07:D3:8E:41:D8:8F:32:15:BC:3A:89:04:44:A0:50` | 2014-04-30 to 2039-04-30 |
| `apple-root-ca-g3.cer` | CN=Apple Root CA - G3, OU=Apple Certification Authority, O=Apple Inc., C=US | `63:34:3A:BF:B8:9A:6A:03:EB:B5:7E:9B:3F:5F:A7:BE:7C:4F:5C:75:6F:30:17:B3:A8:C4:88:C3:65:3E:91:79` | 2014-04-30 to 2039-04-30 |

## Known intermediates (WWDR)

These are added to the path builder's cert store so a signature blob that omits
its intermediate (rare but observed) can still chain to a bundled root. They
are **not** trust anchors on their own — a chain that stops at one of these
without reaching a root above is `CertChainIncomplete`, not `AppleVerified`.

| File | Subject | SHA-256 fingerprint | Validity |
|------|---------|---------------------|----------|
| `apple-wwdr-g3.cer` | CN=Apple Worldwide Developer Relations Certification Authority, OU=G3, O=Apple Inc., C=US | `DC:F2:18:78:C7:7F:41:98:E4:B4:61:4F:03:D6:96:D8:9C:66:C6:60:08:D4:24:4E:1B:99:16:1A:AC:91:60:1F` | 2020-02-19 to 2030-02-20 |
| `apple-wwdr-g4.cer` | CN=Apple Worldwide Developer Relations Certification Authority, OU=G4, O=Apple Inc., C=US | `EA:47:57:88:55:38:DD:8C:B5:9F:F4:55:6F:67:60:87:D8:3C:85:E7:09:02:C1:22:E4:2C:08:08:B5:BC:E1:4C` | 2020-12-16 to 2030-12-10 |
| `apple-wwdr-g6.cer` | CN=Apple Worldwide Developer Relations Certification Authority, OU=G6, O=Apple Inc., C=US | `BD:D4:ED:6E:74:69:1F:0C:2B:FD:01:BE:02:96:19:7A:F1:37:9E:04:18:E2:D3:00:EF:A9:C3:BE:F6:42:CA:30` | 2021-03-17 to 2036-03-19 |

The legacy WWDR CA (G1, expired 2023-02-07) is intentionally **not** bundled.
Reissuing pkpass signatures under that CA was no longer possible after expiry,
so any signature chaining to it would have to be older than 2023, and the
upstream issuer (Apple Root CA) covers re-signing under G3/G4/G6 anyway. Bundling
an expired anchor would muddy the trust posture without buying coverage.

## Sources

The five certificates above were exported from the macOS system keychain on
2026-05-05 from a fully-updated host (and cross-checked against the copies
distributed inside `Xcode.app`'s `DVTFoundation.framework`). The fingerprints
above match the values Apple publishes at
<https://www.apple.com/certificateauthority/>.

`apple-wwdr-g4.cer` was downloaded on 2026-10-13 from
<https://www.apple.com/certificateauthority/AppleWWDRCAG4.cer>, linked from that
page. Apple's page lists no fingerprints as of that date, so it was checked by
`openssl verify -x509_strict` against the bundled `apple-root-ca.cer`, and its
SHA-256 matches both the G4 copy embedded in a real Apple-signed pass and the
public Certificate Transparency record at
<https://crt.sh/?q=EA4757885538DD8CB59FF4556F676087D83C85E70902C122E42C0808B5BCE14C>.

A reviewer can re-derive any of these locally with:

```sh
openssl x509 -in <file>.cer -inform DER -noout -subject -fingerprint -sha256
```

## Updating

Adding a new root or intermediate (e.g. a future G7 WWDR) is a deliberate trust
decision: it expands the set of signers we consider Apple-issued. The change
must:

1. Update this file (table row, source attribution, SHA-256 fingerprint).
2. Update the `BUNDLED_TRUST_ANCHOR_FILENAMES` and
   `BUNDLED_INTERMEDIATE_FILENAMES` lists in `AppleTrustAnchors.kt`.
3. Pass `:passes-core:check` — `AppleTrustAnchorsTest` enforces the bundled
   files load and that the documented anchors are present.

Removing an anchor narrows the trust set and is also deliberate; the same
checklist applies in reverse.
