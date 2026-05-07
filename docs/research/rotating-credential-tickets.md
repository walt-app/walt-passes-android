# Rotating-credential ticket interoperability

Tracks: `wpass-27m` (explain-and-link UX), `wpass-4vc` (Walt-native rotating-barcode extension exploration). Companion to ADR 0006.

This document records the empirical research that motivates Walt's
posture on rotating-credential tickets (Ticketmaster SafeTix and
analogous issuer flows). The goal of the research was to determine
whether such tickets can be imported into Walt at all, what other
third-party wallets do today, what the standards-track and regulatory
horizons look like, and which paths are excluded for trust-claim
reasons even when technically feasible.

## Methodology

The investigation was conducted as six parallel research streams,
each tasked with a specific angle, on 2026-05-07. Each stream was
asked to cite primary sources where available (vendor documentation,
reverse-engineering writeups, regulatory texts, conference
proceedings, official press releases) and to ground its conclusions in
those citations. The streams:

1. **SafeTix architecture.** Mechanism, secret distribution, gate
   verification, refresh windows, reverse-engineering precedent,
   regulatory pressure on Ticketmaster as an actor.
2. **Google Wallet ticket API.** `EventTicketObject.rotatingBarcode`
   schema, `totpDetails` parameters, JWT save flow, closed-loop
   properties, third-party read API surfaces (or absence thereof),
   Apple PassKit and Samsung Wallet comparisons.
3. **Third-party wallet field survey.** PassAndroid, FossWallet,
   Pass2U, WalletPasses, Catima, Stocard, EU consumer wallets:
   rotating-barcode support, `webServiceURL` polling support,
   Ticketmaster compatibility, observed UX fallback patterns.
4. **PKPASS rotation feasibility.** PKPASS schema rotation surface,
   `webServiceURL` update rate caps, Apple's companion-app
   entitlement, historical Ticketmaster PKPASS, theoretical extension
   shapes.
5. **EU regulatory pressure.** DMA gatekeeper scope on ticketing,
   eIDAS 2.0 / EUDI Wallet pilot status, GDPR Article 20 portability,
   antitrust proceedings, industry interoperability initiatives.
6. **Android intent and capture options.** Intent interception
   surface, JWT inspection, `FLAG_SECURE` and accessibility, NFC /
   Smart Tap HCE, browser-based and web fallbacks.

The streams ran independently to avoid cross-contamination of
findings; their reports were then synthesized into the sections
below. Sources are cited inline; the consolidated source block is at
the end of the document.

This is an evidence document, not a decision document. The decision
that Walt draws from this evidence lives in ADR 0006.

## SafeTix mechanism

Ticketmaster SafeTix (also called "Presence" or "Secure Entry") is a
rotating barcode introduced in 2019 that replaces the static PDF417
or QR codes Ticketmaster previously embedded in PKPASS files and
PDFs. The barcode encodes a colon-delimited payload:

```
[base64 bearer token (~48 bytes)]::[6-digit eventKey TOTP]::[6-digit customerKey TOTP]::[unix timestamp]
```

The two 6-digit codes are RFC 6238 SHA-1 TOTPs at a 15-second time
step, computed from two 20-byte hex secrets: an `eventKey` (per
event) and a `customerKey` (per ticket holder). The barcode format is
PDF417 in the United States; the EU presentation observed in the
example screenshot for this research used a QR code, but the underlying
payload structure is identical.

The visible "shimmer" or animation in Ticketmaster's app is CSS only;
only the inner six-digit codes and timestamp actually rotate. Once the
secrets are provisioned, generation is fully client-side. The
Ticketmaster mobile app, the Ticketmaster web client (via
`presence-secure-entry.js` / `generateSignedToken`), and Google Wallet
all locally compute the TOTP from the secrets they hold. No
per-display server roundtrip is required.

Secrets are re-issued approximately 20 hours before each event via the
Ticketmaster Order Management endpoint. Partner integrations are
required to fetch the encrypted Rotating Entry Token at display time;
the partner API itself is gated and contract-only.

At the gate, scanners read the barcode, validate the timestamp window,
recompute both TOTPs from the keys held server-side (or from a
pre-synced cache), and look up the bearer token to verify ticket and
seat ownership before burning it. Some venues use SDK-based offline
verification. The bearer token is the actual identity carried by the
ticket; the rotating TOTPs are an anti-screenshot enforcement layer
that fails closed when a static screenshot is presented.

Each issued token is bound to a `device_id` (and, for partner
distribution, a `third_party_account_id`). Re-issuance kills earlier
copies, which prevents simultaneous-display fraud across devices and
makes the closed-loop binding load-bearing.

Reference: [conduition.io, "Reverse Engineering Ticketmaster's
Rotating Barcodes" (July
2024)](https://conduition.io/coding/ticketmaster/) is the canonical
public writeup. The reverse engineering was covered by
[Hackaday](https://hackaday.com/2024/07/11/ticketmaster-safetix-reverse-engineered/),
[Schneier on
Security](https://www.schneier.com/blog/archives/2024/07/reverse-engineering-ticketmasters-barcode-system.html),
and a [Hacker News
discussion](https://news.ycombinator.com/item?id=40906148). No
academic or conference paper (Black Hat, DEF CON, USENIX) treats
SafeTix specifically; the protocol uses standard cryptographic
primitives in unsurprising ways and the work is entirely in informal
reverse-engineering space.

Ticketmaster's own [Partner SafeTix
documentation](https://developer.ticketmaster.com/products-and-docs/apis/partner/safetix/)
is the only first-party reference; it is gated to vetted distribution
partners.

## How Google Wallet receives SafeTix

Google Wallet's [`EventTicketObject.rotatingBarcode`
field](https://developers.google.com/wallet/reference/rest/v1/RotatingBarcode)
carries the rotation parameters Wallet needs to generate the
barcode locally:

```json
{
  "type": "QR_CODE | PDF_417 | AZTEC | CODE_128",
  "renderEncoding": "...",
  "valuePattern": "MyBarcode-{totp_timestamp_seconds}-{totp_value_0}",
  "totpDetails": {
    "periodMillis": "30000",
    "algorithm": "TOTP_SHA1",
    "parameters": [
      { "key": "<base16 secret>", "valueLength": 8 }
    ]
  },
  "alternateText": "...",
  "showCodeText": { ... },
  "initialRotatingBarcodeValues": {
    "startDateTime": "2026-...",
    "values": ["..."],
    "periodMillis": "30000"
  }
}
```

The `valuePattern` template substitutes `{totp_value_n}` (which TOTP
slot), `{totp_timestamp_millis}`, and `{totp_timestamp_seconds}`.
Multiple `parameters` slots support several concurrent TOTPs, which is
how Ticketmaster encodes both `eventKey` and `customerKey` into the
same Wallet object. `initialRotatingBarcodeValues` is a precomputed
array delivered once for offline use; after it expires the device
must contact Google's servers to refresh.

Google's documented [security best
practices](https://developers.google.com/wallet/tickets/events/resources/rotating-barcodes)
recommend inserting the object server-side via `object:insert` and
then sending only the object ID in the JWT, so the secret key never
appears in the JWT itself. Ticketmaster follows this guidance.

The save flow on Android is one of two paths:

1. A web link of the form `https://pay.google.com/gp/v/save/<JWT>`,
   which is an Android App Link claimed by Google Wallet
   (`com.google.android.apps.walletnfcrel`) and verified via
   [`assetlinks.json`](https://pay.google.com/.well-known/assetlinks.json).
2. A native SDK call: `PayClient.savePasses(jwt, activity, requestCode)`
   from `com.google.android.gms:play-services-pay`, which uses an
   explicit intent targeting GMS and signs the JWT inside Play
   Services using the calling app's SHA-1 fingerprint.

Once the rotating barcode is in Google Wallet there is no user-facing
or API-facing export to PKPASS or any interchange format. Google ships
a one-way [pass-converter](https://github.com/google-wallet/pass-converter)
for issuers that converts at issue time, not from a user's wallet.
There is no public Android Intent, ContentProvider, broadcast, or
Play Services API that exposes Wallet contents to other apps. The
Wallet SDK is write-only.

## Closed paths and rationale

The following paths were evaluated against the import surface of an
Android third-party wallet. Each is rated for technical feasibility,
legal or ethical exposure, and user-experience cost. "Rated" means
the cost the path would impose on Walt specifically; each path is
otherwise considered against an arbitrary third-party wallet.

| Path                                                        | Feasibility | Legal/ethical | UX cost | Why excluded                                                                                                                                                                                                                                                                                                       |
|-------------------------------------------------------------|-------------|---------------|---------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Intercept `PayClient.savePasses` SDK intent                 | None        | Low           | High    | Explicit intent to GMS process; not interceptable by sibling packages without root.                                                                                                                                                                                                                                |
| Register competing app-link filter for `pay.google.com/gp/v/save/*` | Low         | Medium        | High    | Google Wallet's filter is auto-verified via Digital Asset Links; on Android 12+ the system routes verified-only matches to the verified app. Walt does not own `pay.google.com` and cannot publish a matching `assetlinks.json`.                                                                                   |
| Decode the save-to-Wallet JWT to recover TOTP secrets       | High*       | Low           | High    | Per Google's documented best practice, the JWT references objects by ID and does not inline secrets. Ticketmaster follows this pattern. The decode is feasible if it ever yielded a secret, but the secret is not present.                                                                                          |
| Read the Ticketmaster app's QR via screen capture           | None        | Low           | High    | The Ticketmaster ticket Activity sets `WindowManager.LayoutParams.FLAG_SECURE`, which blocks `MediaProjection`, system screenshots, recents thumbnails, and accessibility-service bitmap retrieval at the OS level.                                                                                                |
| Smart Tap NFC at the venue gate                             | None        | High          | High    | Smart Tap requires an ECDH handshake against a venue collector public key provisioned by Google to the merchant, paired with a wallet private key Google holds. There is no path for a third-party wallet to participate as a Smart-Tap-emitting device without Google merchant onboarding the venue does not own. |
| PKPASS `webServiceURL` polling at TOTP cadence              | None        | Low           | High    | Apple Wallet caps push notifications at approximately 20 per pass per day; Google Wallet at 3 per day. A 15-second TOTP rotation requires approximately 5,760 pushes per day, two orders of magnitude over the cap. The full flow also requires a freshly signed PKPASS bundle per rotation, which is bandwidth-prohibitive.        |
| Reverse engineer + extract secrets from Ticketmaster's web client | High        | High          | Medium  | Tools like TicketGimp, Amosa, Secure.Tickets, Verified-Ticket.com extract secrets via Chrome DevTools against Ticketmaster's authenticated session. This violates Ticketmaster's Terms of Use, plausibly violates DMCA Section 1201 anti-circumvention, and is the subject of Ticketmaster's 2025 patent suit. Categorically incompatible with Walt's transparency-for-trust posture. |
| Apple PassKit "companion app" emulation                     | None        | Low           | High    | The companion-app entitlement (`com.apple.developer.pass-type-identifiers`) merely lets an installed app overwrite a pass it owns via `PKPassLibrary`. It does not inject rotating barcode rendering into Wallet's chrome. The rotating QR observed in Apple Wallet for SafeTix tickets is drawn by the Ticketmaster iOS app over a Wallet-launcher pass, not by Apple Wallet itself. |

\* "High feasibility" for JWT decode means the decode itself works;
the recovered payload is not useful in practice because the secret is
not present.

## Third-party wallet field survey

| Wallet              | Rotating barcode | `webServiceURL` polling      | Ticketmaster compatibility | Notes                                                                                                                                                |
|---------------------|------------------|------------------------------|----------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| PassAndroid (Ligi)  | No               | Not documented as supported  | None                       | Static QR / PDF417 / Aztec / Code39 / Code128 only. Ticketmaster does not export PKPASS for SafeTix events.                                          |
| FossWallet          | No               | Partial; manual + scheduled poll, no APNS | None                       | Issue tracker has zero hits for "rotating", "dynamic", "SafeTix". No SafeTix `.pkpass` ever reaches it.                                              |
| Pass2U              | No               | Yes (PassKit-style)          | None                       | Documented push-notification compliance via `X-Pass-Client: Passes`. No TOTP rendering.                                                              |
| WalletPasses        | No               | Yes (PassKit-style)          | None                       | Static `barcodes[]` only. Same as Pass2U on rotation.                                                                                                |
| Catima              | No               | No (not a PKPASS engine)     | None                       | Loyalty card focused; static barcode formats only.                                                                                                   |
| Stocard / ShopFully | No               | No                           | None                       | Loyalty only; no public rotating-ticket support.                                                                                                     |
| EU consumer wallets | No               | No                           | None                       | GoCardWallet, Passwave, etc. None advertise rotating-ticket holder support.                                                                          |

No FOSS or commercial third-party wallet has solved holding a
SafeTix ticket. Three obstacles compound across all of them:

1. Ticketmaster does not emit a `.pkpass` containing SafeTix
   material; there is no file to hand a third-party wallet.
2. The "Save to Google Wallet" JWT contains a server-side reference,
   not the rotating secrets, so JWT decoding yields no usable seed.
3. The only known third-party rendering path (TicketGimp et al.)
   requires DevTools extraction against Ticketmaster's authenticated
   session, violates Terms of Use, and is the subject of active
   litigation.

Observed UX fallback patterns across this category:

- Silent acceptance with a blank barcode: the most common failure
  mode in Apple Wallet and Google Wallet when Ticketmaster passes are
  added but cannot render the rotating QR.
- Official redirect: Ticketmaster's help docs route users back to the
  Ticketmaster app or a mobile-web fallback.
- No graceful degradation in FOSS wallets: PassAndroid, FossWallet,
  Catima have no UI surface for "this ticket type is not supported"
  because they never see a SafeTix file.

## PKPASS rotation feasibility

The PKPASS `barcodes` array entries have a fixed schema: `format`
(QR / PDF417 / Aztec / Code128), `message` (string), `messageEncoding`,
`altText`. There is no native time-based, TOTP, or rotation field
anywhere in `pass.json`. The barcode `message` is a static string
baked into the signed manifest. To change it, the entire pass must be
re-signed and re-issued via the `webServiceURL` mechanism.

Apple's documented [push update flow for
PassKit](https://developer.apple.com/library/archive/documentation/UserExperience/Conceptual/PassKit_PG/Updating.html)
is pull-then-replace: the device registers, the issuer sends an APNs
push, the device queries serial-numbers-changed-since, and the device
fetches a new signed `.pkpass`. Apple's docs explicitly note that
pushes are "not guaranteed to be delivered" and "multiple pushes from
the same source are coalesced into a single notification." Practical
caps are approximately 20 pushes per pass per day on Apple Wallet and
3 per day on Google Wallet. A 15-second SafeTix-equivalent rotation
would need approximately 5,760 pushes per day.

The "Companion App" pattern Apple promotes is not a rotation
primitive. The relevant entitlement,
`com.apple.developer.pass-type-identifiers`, scopes which pass-type
IDs an installed app may read, update, or delete via `PKPassLibrary`.
It does not grant any TOTP rendering primitive. Apple's guidance frames
companion apps as "doing things that Wallet cannot do, such as letting
the user ask for a different seat on a flight and then updating the
pass." The rotating QR observed in Apple Wallet for SafeTix tickets is
not drawn by Apple Wallet at all; it is drawn by the Ticketmaster iOS
app over a Wallet-launcher pass.

A Walt-specific rotating-barcode extension to PKPASS is, however,
theoretically achievable, because Walt is its own wallet and is not
subject to Apple Wallet's renderer constraints. PKPASS reserves a
top-level `userInfo` field for app-specific data that Apple Wallet
ignores. A cooperating issuer could ship a PKPASS that carries both a
static fallback `barcodes[]` entry (for Apple Wallet, Google Wallet's
PKPASS importer, and any other wallet that does not understand the
extension) and a Walt-namespaced rotation block under `userInfo`,
shaped like Google's `RotatingBarcode`:

```jsonc
{
  "userInfo": {
    "waltRotatingBarcode": {
      "type": "QR_CODE",
      "valuePattern": "{totp_value_0}",
      "totpDetails": {
        "periodMillis": 15000,
        "algorithm": "TOTP_SHA1",
        "parameters": [
          { "key": "<base16 secret>", "valueLength": 6 }
        ]
      },
      "initialRotatingBarcodeValues": { "...": "..." }
    }
  }
}
```

Walt's renderer would compute TOTP locally on display. The signed
manifest stays static; the secret is signed, not the rendered code.
This sidesteps `webServiceURL` rate caps, APNs delivery semantics, and
the companion-app entitlement entirely. The constraint is adoption,
not technology: incumbents (Ticketmaster, AXS, Eventim) will not issue
Walt-flavored bundles, but smaller ticketing platforms, festivals,
season-ticket holders, conference issuers, and self-issuers may. This
extension concept is tracked under `wpass-4vc` for v2 / v3
architecture discussion and is explicitly outside v1 scope per
`decision-wlt-0tn-q3`.

## Standards-track landscape

The W3C Verifiable Credentials 2.0 specification became a [W3C
Recommendation on
2025-05-15](https://www.w3.org/press-releases/2025/verifiable-credentials-2-0/).
OpenID for Verifiable Credential Issuance (OpenID4VCI) is the
issuance protocol of record for the EUDI Wallet; cross-issuer and
cross-wallet interop was demonstrated in
[2025](https://www.biometricupdate.com/202507/openid-vc-spec-shows-interoperability-between-issuers-digital-wallets).

The EU Digital Identity Wallet, mandated under [eIDAS 2.0
(Regulation EU
2024/1183)](https://www.european-digital-identity-regulation.com/Article_3_(Regulation_EU_2024_1183).html),
lists "tickets, social passes or loyalty cards" as explicit Electronic
Attestation of Attributes (EAA) use cases. The [EWC large-scale
pilot](https://ec.europa.eu/digital-building-blocks/sites/spaces/EUDIGITALIDENTITYWALLET/pages/920064565/LSP-EWC)
(24 countries, approximately 80 partners) tested travel and event
credentials including a Lufthansa + Amadeus boarding pass [pilot in
late
2025](https://www.computerweekly.com/news/366631224/Lufthansa-pilots-EU-Digital-Identity-Wallet-based-travel).
The member-state delivery mandate is 2026-09. None of the major
ticketing platforms (Ticketmaster, AXS, Eventim, CTS Eventim) has
announced EUDI participation.

A Lissi-issued event-ticket credential pilot ran at a small "Between
the Towers" event with approximately 70 attendees ([writeup,
2024](https://lissi-id.medium.com/event-tickets-as-verifiable-credentials-31f4a10b28cc));
no major ticketing platform integrations followed. The OPEN Ticketing
Ecosystem (formerly GET Protocol) is an opt-in blockchain-backed
interoperability play that no major issuer participates in. No active
W3C Ticketing Community Group was located. ISO/IEC 18013-7 covers
mDoc presentation but is identity-shaped, not ticket-shaped. GS1
EPCIS exists for supply-chain event data but is not a consumer-ticket
standard.

The realistic standards-track read is that EUDI Wallet adoption is
the only path with regulatory weight, and ticketing under it is a
stated-but-unbuilt use case. A Walt-as-EUDI-Wallet-holder posture
becomes meaningful only when issuers begin emitting ticket-shaped EAAs,
which the September 2026 mandate may catalyze on the relying-party
side without compelling issuers directly.

## Regulatory levers

**Digital Markets Act.** The DMA designates Apple as a gatekeeper for
iOS, Safari, and the App Store, and Alphabet for Google Search, Maps,
Chrome, Android, Google Play, YouTube, Google Ads, and Google
Shopping. Apple Wallet itself is not separately designated, but its
NFC stack falls within DMA Article 6(7) via iOS; the [Article 6(7)
specification proceeding (DMA.100203, decided March
2025)](https://eur-lex.europa.eu/legal-content/EN/TXT/PDF/?uri=OJ:C_202504646)
forced Apple to open NFC and HCE entitlement to third-party wallets.
Recital 56 cites Apple Mobile Payments as the model. The framing
remains payment- and hardware-feature centric. Nothing in the
published decisions extends Article 6(7) to "ticket data the wallet
app holds." Article 6(7) covers access to OS-level hardware features,
not application-layer content held in a competitor's app.

No ticketing platform meets DMA designation thresholds (45 million
monthly active users, 10,000 business users, EUR 7.5 billion EEA
revenue, entrenched position). Live Nation's global revenue is large
but its EU "core platform service" footprint is far below threshold
and the wrong shape (B2B promoter relationships, not a digital
intermediation service in the DMA sense). DMA opens the NFC chip on
Apple and Google to Walt; it does not compel Ticketmaster to release
ticket payloads.

**eIDAS 2.0.** Regulation EU 2024/1183 is scoped to identity,
authentication, and qualified trust services. It does not impose a
duty on event-ticket issuers. A ticket can technically be modeled as
a non-qualified Electronic Attestation of Attributes; whether it
qualifies as a Qualified EAA depends on the issuer being a Qualified
Trust Service Provider, which Ticketmaster is not and has no
obligation to become. Issuance to the EUDI Wallet is permissible, not
mandatory.

**GDPR Article 20.** [Right to data
portability](https://gdpr-info.eu/art-20-gdpr/) entitles a data
subject to receive personal data they provided in a structured,
commonly used, machine-readable format, where processing is based on
consent or contract. A ticket purchase qualifies. So a Danish user
can demand a Ticketmaster export of name, email, and purchase
history. WP29's guidance on Article 20 expressly limits portability
to data "actively and knowingly provided" plus observed data, not
derived or inferred outputs. The rotating-secret barcode payload that
authenticates entry is data Ticketmaster generates and refreshes as
an anti-resale measure; it is plausibly outside Article 20's reach.
No published Datatilsynet, CNIL, or BfDI decision was located that
ordered a ticketing company to release a scannable ticket payload to
a third-party wallet.

**Antitrust.** US v. Live Nation (filed May 2024, jury verdict April
2026, found liable) is the most active proceeding. The DOJ amended
complaint specifically called SafeTix's nontransferability and
rotating barcode "anticompetitive levers." 40 states joined. The
remedy phase is ongoing; a structural Ticketmaster spinoff is the
leading proposal, though no proposed remedy public to date includes
third-party wallet interoperability. In the EU, the only meaningful
action has been at member-state level (AGCM v. CTS Eventim/TicketOne
in Italy, partially annulled by the Council of State in 2022). No live
EU Commission proceeding against a ticketing platform was located.

The honest read is that no current regulatory lever forces
Ticketmaster open. Near-future leverage is indirect and contingent: a
US Ticketmaster spinoff remedy that includes interoperability
conditions; a member-state DPA test of Article 20 against a barcode
payload; or EUDI Wallet adoption pulling travel and event issuers onto
verifiable-credential rails by demand-side gravity rather than
compulsion.

## Reverse engineering in the wild

Conduition's writeup is the canonical public reverse engineering of
SafeTix and demonstrates that secrets are loaded into the JS console
and trivially extractable via Chrome DevTools on Android.
[Conduition](https://conduition.io/coding/ticketmaster/) published
functional code regenerating valid SafeTix barcodes from extracted
keys.

Downstream services have built on this work. [Engadget reported in
July
2024](https://www.engadget.com/hackers-reverse-engineer-ticketmasters-barcode-system-to-unlock-resales-on-other-platforms-194826061.html)
on scalper-facing platforms (Amosa App, Secure.Tickets, Virtual
Barcode Distribution, Verified-Ticket.com) using the technique to
enable resale outside Ticketmaster's app. [404
Media](https://www.404media.co/ticketmaster-used-revolving-barcodes-to-control-ticket-resale-market-and-surveil-customers-doj-alleges/)
cited the same work in DOJ allegations.

Ticketmaster's [Terms of
Use](https://legal.ticketmaster.com/terms-of-use/prior-version-terms-of-use/)
explicitly forbid "decoding, decrypting, modifying, or reverse
engineering any tickets or underlying algorithms" and cite DMCA
Section 1201 anti-circumvention. A November 2025 SafeTix patent
infringement [lawsuit](https://www.ticketnews.com/2025/11/ticketmaster-hit-with-new-lawsuit-over-safetix-rotating-barcode-patent/)
underscores that Ticketmaster treats the protocol as proprietary IP
and is willing to litigate.

For Walt, this path is excluded categorically. The transparency-for-
trust posture is incompatible with shipping reverse-engineered
SafeTix rendering: even if the legal exposure were acceptable, the
trust claim "every security-and-privacy-critical behavior is
implemented in code that lives in this repository" cannot coexist with
"and we extract secrets from another company's authenticated user
session against their explicit ToU." The two postures are in direct
contradiction.

## Sources

### Mechanism and reverse engineering

- conduition.io. ["Reverse Engineering Ticketmaster's Rotating
  Barcodes"](https://conduition.io/coding/ticketmaster/) (July 2024).
- Hackaday. ["Ticketmaster SafeTix
  Reverse-Engineered"](https://hackaday.com/2024/07/11/ticketmaster-safetix-reverse-engineered/) (July 2024).
- Schneier on Security. ["Reverse Engineering Ticketmaster's Barcode
  System"](https://www.schneier.com/blog/archives/2024/07/reverse-engineering-ticketmasters-barcode-system.html).
- Ticketmaster. [Partner SafeTix
  documentation](https://developer.ticketmaster.com/products-and-docs/apis/partner/safetix/).
- TechCrunch. ["Ticketmaster put an end to screenshots with new
  digital ticket
  technology"](https://techcrunch.com/2019/05/16/ticketmaster-put-an-end-to-screenshots-with-new-digital-ticket-technology/) (2019).
- TicketNews. ["Ticketmaster hit with new lawsuit over SafeTix
  rotating barcode
  patent"](https://www.ticketnews.com/2025/11/ticketmaster-hit-with-new-lawsuit-over-safetix-rotating-barcode-patent/) (November 2025).

### Wallet schemas and APIs

- Google. [`RotatingBarcode` REST
  reference](https://developers.google.com/wallet/reference/rest/v1/RotatingBarcode).
- Google. [Event-ticket rotating
  barcodes](https://developers.google.com/wallet/tickets/events/resources/rotating-barcodes).
- Google. [Event-ticket JWT
  flow](https://developers.google.com/wallet/tickets/events/use-cases/jwt).
- Google. [Event-ticket Android
  SDK](https://developers.google.com/wallet/tickets/events/android).
- Google. [Smart Tap
  introduction](https://developers.google.com/wallet/smart-tap/introduction/overview).
- Google. [`google-wallet/pass-converter` on
  GitHub](https://github.com/google-wallet/pass-converter).
- Apple. [Wallet Developer Guide: Pass Design and
  Creation](https://developer.apple.com/library/archive/documentation/UserExperience/Conceptual/PassKit_PG/Creating.html).
- Apple. [Wallet Developer Guide: Updating a
  Pass](https://developer.apple.com/library/archive/documentation/UserExperience/Conceptual/PassKit_PG/Updating.html).
- Apple. [Apple Pay and PassKit
  Entitlements](https://developer.apple.com/library/archive/documentation/Miscellaneous/Reference/EntitlementKeyReference/ApplePayandPassKitEntitlements/ApplePayandPassKitEntitlements.html).
- Samsung. [Wallet event ticket
  schema](https://developer.samsung.com/wallet/api_new/purposes/walletcards/ticket.html).

### Third-party wallets

- [PassAndroid (Ligi)](https://github.com/ligi/PassAndroid).
- [FossWallet (SeineEloquenz)](https://github.com/SeineEloquenz/fosswallet)
  and [discussion #57 on
  webServiceURL](https://github.com/SeineEloquenz/fosswallet/discussions/57).
- [Pass2U Push Notification API](https://www.pass2u.net/apiPushNotification).
- [WalletPasses](https://walletpasses.io/).
- [Catima](https://github.com/CatimaLoyalty/Android).

### Standards and pilots

- W3C. [Verifiable Credentials 2.0 Recommendation press
  release](https://www.w3.org/press-releases/2025/verifiable-credentials-2-0/).
- Biometric Update. ["OpenID VC spec shows interoperability between
  issuers, digital
  wallets"](https://www.biometricupdate.com/202507/openid-vc-spec-shows-interoperability-between-issuers-digital-wallets) (July 2025).
- Computer Weekly. ["Lufthansa pilots EU Digital Identity Wallet-based
  travel"](https://www.computerweekly.com/news/366631224/Lufthansa-pilots-EU-Digital-Identity-Wallet-based-travel).
- Lissi. ["Event tickets as verifiable
  credentials"](https://lissi-id.medium.com/event-tickets-as-verifiable-credentials-31f4a10b28cc).
- European Commission. [LSP-EWC pilot
  page](https://ec.europa.eu/digital-building-blocks/sites/spaces/EUDIGITALIDENTITYWALLET/pages/920064565/LSP-EWC).
- [EUDI Wallet Architecture and Reference Framework
  2.7.3](https://eu-digital-identity-wallet.github.io/eudi-doc-architecture-and-reference-framework/2.7.3/technical-specifications/).

### Regulatory and antitrust

- European Commission. [DMA gatekeepers
  portal](https://digital-markets-act.ec.europa.eu/gatekeepers-portal_en).
- European Commission. [Apple Article 6(7) decision
  summary](https://eur-lex.europa.eu/legal-content/EN/TXT/PDF/?uri=OJ:C_202504646).
- European Commission. [Interoperability Q&A on the
  DMA](https://digital-markets-act.ec.europa.eu/questions-and-answers/interoperability_en).
- [Article 20 GDPR](https://gdpr-info.eu/art-20-gdpr/).
- US DOJ. [Press release on Live Nation /
  Ticketmaster](https://www.justice.gov/archives/opa/pr/justice-department-sues-live-nation-ticketmaster-monopolizing-markets-across-live-concert).
- 9to5Mac. ["SafeTix
  anticompetitive"](https://9to5mac.com/2024/08/20/ticketmaster-safetix-anticompetitive/).
- 404 Media. ["Ticketmaster used revolving barcodes to control ticket
  resale market and surveil customers, DOJ
  alleges"](https://www.404media.co/ticketmaster-used-revolving-barcodes-to-control-ticket-resale-market-and-surveil-customers-doj-alleges/).

### Android platform

- Android Developers. [`FLAG_SECURE`
  reference](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE).
- Android Developers. [Verify Android App
  Links](https://developer.android.com/training/app-links/verify-android-applinks).
