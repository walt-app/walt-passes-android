# `apple-signed/` fixture

Real Apple-signed pkpass artifacts pulled from a Tixly Chroniques ticket. Used by
`SignatureVerifierTest.realAppleSignedPkpassFixtureVerifies` and
`SignatureVerifierTest.realAppleSignedPkpassVerifiesEvenWhenBcSlotHoldsAStrippedProvider`
to exercise the full production verifier path against a wire-shape Apple ships, on top
of the bundled Apple Root CA anchor set.

## What's here

- `manifest.json` — twelve SHA-1 file digests. No PII; the digest values reveal nothing
  about ticket contents.
- `signature` — detached PKCS#7 / CMS blob. Embeds the Apple WWDR G4 intermediate and
  the Tixly leaf certificate. The leaf's CN is `Pass Type ID: pass.com.tixly` (an issuer
  identity, not user data).

`pass.json` and the localized `*.lproj/pass.strings` files are deliberately *not*
included: they are the only entries with passenger / event / ticket-number content, and
the manifest digest comparison does not require them — the verifier only signs and
checks `manifest.json`.

## Implicit shelf life

The Tixly leaf certificate's `notAfter` is **2027-02-04T10:18:41 UTC** (Apple Pass Type
ID certificates are typically issued for 1 year). The verifier sets
`PKIXBuilderParameters.date` to *now* and `isRevocationEnabled = false`; revocation is
fine forever, but expiry will trip on the wall clock.

When the leaf expires, both fixture-based tests will downgrade from
`Ok(AppleVerified)` to `Ok(CertChainIncomplete)` and fail — per the documented behavior
in `SignatureVerifier`'s "No date-shift override" docblock.

## Renewal procedure when the test breaks

1. Buy or otherwise obtain any current Apple-signed pkpass (any vendor; Apple Wallet's
   sample passes work too).
2. Unzip it and copy `manifest.json` and `signature` into this directory, replacing the
   files here. Do **not** copy `pass.json`, `*.lproj/`, or any image assets — they are
   PII-bearing and unnecessary for the verifier path.
3. Update the `notAfter` date above with the new leaf cert's expiry, extracted via:
   ```
   openssl cms -in signature -inform DER -cmsout -print 2>/dev/null \
     | awk '/subject:.*Pass Type ID/{found=1} found && /notAfter:/{print; exit}'
   ```
4. Rerun `:passes-core:test --tests "*.SignatureVerifierTest.realAppleSignedPkpass*"`
   to confirm both tests turn green again.

A deferred follow-up (noted in `SignatureVerifier.kt`'s docblock) would snapshot the
PKIX path-build date to the CMS signing time, eliminating this maintenance loop. That
work is intentionally out of scope here.
