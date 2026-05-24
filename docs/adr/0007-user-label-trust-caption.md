# ADR 0007: user-label side-channel override and trust-caption rule for pkpass

- Status: Accepted
- Date: 2026-05-24
- Tracks: `wpass-7xa` (this ADR + implementation), `wpass-osf` (rename-epic
  parent), walt-android `wlt-4dn.3` / GitHub `walt-app/walt-passes-android#75`
  (consumer-side rename UX). Sibling slices already shipped:
  `wpass-hhq` (`updateDocumentLabel`, PR #109), `wpass-lay`
  (`updateScannableCard`, PR #110).
- Decision context: extends ADR 0002 D3 (the `passes` schema) and ADR 0003
  (passes-ui theming surface). Trust-claim posture inherits from the repo
  charter: every trust-claim-bearing behavior lives in this repository.

## Context

Walt-android wants a "rename pass" affordance: a user who has imported a
Delta boarding pass should be able to label it "Mom's flight home" in the
wallet list. PR #109 and #110 shipped the analogous capability for the two
sibling artifact types (documents, scannable cards). pkpass is the
highest-risk of the three because its title is part of the signed payload
and therefore part of the trust bond.

Today, a pkpass's visible identity on the front of the card and in lane
tiles is the signed `pass.json` `organizationName` (after pass.strings
substitution; see `PassFront.kt:102`). That string is what binds the user's
mental model of *who issued this pass* to the cryptographically verified
issuer identity. A naive "rename" feature that lets the user overwrite the
displayed title would decouple displayed identity from signed identity in
exactly the same shape as the #69 verifier-name swap incident: the screen
says one thing, the cryptographically attested issuer is another, and there
is no on-screen affordance to tell users which is which.

The siblings did not have this problem. Documents (`updateDocumentLabel`)
have no signed identity at all — the consumer supplies the label at import.
Scannable cards (`updateScannableCard`) have no signature either; the
payload is user-generated. pkpass is alone in carrying a PKCS#7-attested
issuer identity that the rename feature must not silently hide.

This ADR establishes the data model, repository API, surface contract, and
in-repo enforcement mechanism for a pkpass user-label override that
preserves the signed identity on every surface that presents the override.

## Decisions

### D1. user_label is a side-channel column on passes, never on the signed payload

A new nullable `user_label TEXT` column is added to the `passes` table
(ADR 0002 D3). `NULL` means "no override" and is the default for all
existing and freshly imported rows.

The override is stored alongside the row, not inside `pass_json`. The
`pass_json` BLOB carries the parser's exact reconstruction of the signed
PKPASS body (per ADR 0002 D3) and must remain bit-equivalent to what the
signature attests. Putting `user_label` inside `pass_json` would corrupt
that contract: a re-verification of the stored JSON against the original
PKCS#7 signature would fail. Keeping it as a separate column makes the
override obviously not-signed at the storage layer — a future contributor
reading the schema sees that `user_label` and `pass_json` are distinct
fields, and a future audit reading a row sees both side by side.

Schema delta (v4 → v5):

```sql
ALTER TABLE passes ADD COLUMN user_label TEXT;
```

`NULL` default. No index: the column is not a query key (sort and filter
continue against `type` / `expiration_epoch_ms` / `created_at_epoch_ms` per
ADR 0002 D3); it is loaded with the row and rendered. Adding an index later
is a non-breaking follow-on if a future feature surfaces it.

Length cap: `MAX_USER_LABEL_CHARS = 100`, mirroring the existing
`DocumentBounds.MAX_LABEL_CHARS` cap that `updateDocumentLabel` enforces.
The cap exists at the repository boundary only; nothing upstream of the
consumer-supplied label bounds it.

### D2. PassRepository.updatePassUserLabel(id, label) replaces or clears

```kotlin
public suspend fun updatePassUserLabel(
    id: PassRecordId,
    label: String?,
): StorageResult<Unit>
```

Semantics:

- `label = null` clears the override (sets `user_label` to `NULL`).
- A non-null `label` whose `.trim()` is empty is treated as a clear. This
  is defense in depth against a "rename to all whitespace" UX bug that
  would render an invisible primary identity and effectively hide the
  pass. Walt-android's rename UI is expected to offer an explicit "clear"
  button that passes `null`; the trim-blank-to-null rule means the
  storage layer cannot end up in the all-spaces state even if a future
  caller forgets the explicit-clear path.
- A non-null `label` whose trimmed length exceeds `MAX_USER_LABEL_CHARS`
  is rejected with `StorageError.PassRejected` carrying
  `PassUpdateRejectedKind.LabelTooLong`. Mirrors `updateDocumentLabel`'s
  cap-rejection shape. The cap is checked before the row is loaded, so
  an over-long label against an unknown `id` surfaces as
  `PassRejected`, not `IntegrityViolation`. Matches
  `updateDocumentLabel`'s precedence rule.
- Returns `StorageError.IntegrityViolation` if no row matches `id` and
  the label passed the cap.
- Whitespace at the leading and trailing edges is trimmed before
  storage. Internal whitespace is preserved (a label like
  `"Mom's    flight"` is legitimate user input; collapsing internal runs
  is a UI concern, not a storage concern).

### D3. updated_at_epoch_ms is NOT bumped by user_label changes

`updated_at_epoch_ms` semantically tracks "the canonical pass payload
changed" — i.e. the upsert re-import path in `PassRepository.upsert`. A
user_label change does not touch `pass_json`, images, locales, or any
signed field. It is metadata about the user's relationship to the pass,
not a payload mutation.

Precedent: `updateDocumentLabel` explicitly preserves
`imported_at_epoch_ms` ("rename is not a re-import");
`updateScannableCard` explicitly preserves `created_at_epoch_ms` ("edit
is not a re-insert"). `updatePassUserLabel` matches that posture.

Sort-order note: the `PassRepository.passes` `StateFlow` is sorted by
`created_at_epoch_ms DESC`, not `updated_at_epoch_ms`, so a hypothetical
bump would not change visible ordering. If a future feature needs a
"recency of user attention" sort, that is a new column (e.g.
`last_user_touch_epoch_ms`), not a re-purposing of `updated_at`.

### D4. StoredPass and PassSummary surface user_label as a separate field

Both data classes gain `userLabel: String?`. Callers (walt-android) read
the field directly; the kernel does not blend it into `Pass`
or `pass.organizationName`. Keeping the override on the storage-layer
type, not on the `passes-core` `Pass` type, preserves the invariant that
`Pass` carries only the parser's view of the signed payload.

A consumer that ignores `userLabel` and renders `pass.organizationName`
continues to work and continues to be trust-correct (it just doesn't
display the user's rename). The override is opt-in at the consumer
layer; the storage and parser layers are not aware of it.

### D5. Trust-caption rule: signed organizationName MUST remain visible when user_label is set

This is the load-bearing trust-claim decision of the ADR.

> **Rule.** When a pkpass's `StoredPass.userLabel` is non-null, every UI
> surface that presents that pass's primary visible identity MUST also
> present the signed `organizationName` on the same surface, not behind
> a tap-to-reveal, with a typographic treatment at least as legible as
> a caption or eyebrow under the user label.

Concrete shape on a pkpass tile:

```
┌──────────────────────────────────────┐
│ Mom's flight home          [Verified]│   ← user_label (primary)
│ Delta Air Lines                      │   ← signed organizationName (eyebrow)
│ ... rest of pass body / fields ...   │
└──────────────────────────────────────┘
```

Caption-anchor choice: `organizationName`, not `description`. PKPASS's
`organizationName` is the spec-blessed issuer-identity string ("Display
name of the organization that originated and signed the pass"). PKPASS's
`description` is an accessibility-summary field ("Brief description of
the pass, used by iOS accessibility technologies"). The trust bond a
rename can decouple is "this pass identifies itself as issued by X"; the
right anchor is the issuer identity, not the artifact-kind summary.
Description remains available for accessibility surfaces (TalkBack /
content descriptions), but is not the trust caption.

Equality edge case: when `userLabel.trim()` equals the displayed
`organizationName` (after `pass.strings` substitution) under
case-insensitive ASCII comparison, the surface MAY suppress the eyebrow
to avoid visually redundant identical lines. Storage still records what
the user typed. The override is treated as "no-op for display purposes";
the trust rule is satisfied trivially because the primary identity
already IS the signed identity.

### D6. The rule is enforced by a kernel-owned Composable, not by documentation

`passes-ui` exposes a new public Composable:

```kotlin
@Composable
public fun PassIdentityBlock(
    pass: Pass,
    userLabel: String?,
    locale: PassLocale = PassLocale("en"),
    modifier: Modifier = Modifier,
)
```

This Composable is the canonical renderer of the D5 rule. Given a
`Pass` (whose `organizationName` carries the signed issuer identity)
and an optional `userLabel`, it renders:

- when `userLabel` is `null`: the displayed `organizationName` alone, in
  the same role as today's `PassFront` eyebrow at line 156-161.
- when `userLabel` is non-null and not equal to the displayed
  `organizationName`: the user label as the primary line and the
  displayed `organizationName` as a sub-line (eyebrow), both rendered on
  the same surface, both legible.
- when `userLabel.trim()` equals the displayed `organizationName` (D5
  equality edge case): the displayed `organizationName` alone.

`PassFront` is updated to route its header eyebrow through
`PassIdentityBlock` rather than rendering `displayOrganizationName`
directly. `PassImportConfirm` is *not* updated; at import time
`userLabel` does not exist yet, and the import-confirm surface always
shows signed identity by construction (no override is possible until
after the row is persisted).

Walt-android's pkpass tile, pkpass lane row, and any future
pkpass-surfacing chrome MUST call `PassIdentityBlock` rather than read
`StoredPass.userLabel` and render it directly. Bypassing the Composable
to surface `userLabel` as bare primary identity is a trust-claim
violation, equivalent in shape to the #69 verifier-name swap.

In-repo enforcement:

- `PublicApiSurfaceTest` in `passes-ui` is extended to assert that
  `PassIdentityBlock` exists and is `public`. This is the lock on the
  surface contract: walt-android's tile code that compiles against this
  module is wired to a symbol whose existence is tested here, so a
  future refactor cannot quietly remove it.
- A new test in `TrustClaimSurfaceTest` exercises `PassIdentityBlock`
  with each of the four cases above and asserts that the signed
  `organizationName` is on screen in all non-suppressed cases.

Consumer-side enforcement is code review on walt-android (the rename
PRs land in the same review cycle that touches the tile and lane
surfaces); this ADR is the canonical reference the reviewer cites.

### D7. Telemetry: a label-mutation event with no PII

`StorageTelemetryGuard` gains:

```kotlin
public fun onUserLabelUpdated(
    type: PassType,
    hadPriorLabel: Boolean,
    clearing: Boolean,
)
```

Following the `passes-storage` discipline (ADR 0002 D8 + ADR 0002 D7):
no `String` parameters, no `Pass` parameter, no `PassField` parameter.
The event carries only the pass type, whether there was already an
override, and whether the new write is a clear (`label == null` or
trimmed to empty). The user's actual label text is never exposed
through telemetry. Adding the label or any field of the pass to this
event in the future is a security-policy change, not an API addition.

A separate `passes-ui` telemetry event for "user-label override is
being displayed" is *not* added. The trust-caption rule is enforced by
the Composable's structure, not by a runtime event; counting how many
overridden passes are on screen does not contribute to trust posture.

## Consequences

- The pkpass rename feature is unblocked behind a schema migration
  (v4 → v5), a single new repository API, and a single new public
  Composable. The trust-claim posture is preserved: the signed issuer
  identity remains on every pkpass surface that surfaces a renamed
  pass.
- walt-android's `wlt-4dn.3` consumer-side work can land against a
  fixed kernel contract: it adds a rename UI, calls
  `updatePassUserLabel`, reads `StoredPass.userLabel`, and renders via
  `PassIdentityBlock`. No parallel trust-claim-bearing logic on the
  consumer side.
- The audit narrative gains a worked example: "trust caption" is no
  longer a phrase that lives only in the ADR for documents and
  scannable cards; it is also the explicit rule for the artifact type
  whose identity is *cryptographically attested*. This ADR is the
  canonical reference when a future contributor asks "why can't we let
  the user just type a new title?"
- A schema-version-5 migration ships. The migration is a single
  `ALTER TABLE`, mechanically the simplest in `Schema.MIGRATIONS` so
  far. A row that fails the migration is impossible (no data is
  transformed); `SchemaMigrationTest` gains a v4 → v5 walk-up assertion
  that the column exists and defaults to `NULL`.
- `Pass` (passes-core) is unchanged. The override never reaches
  `passes-core`. The "pure Kotlin/JVM, signed-payload-only" character
  of `passes-core` is preserved.

## Open follow-ups

- Implementation bead: schema v4 → v5 migration in `Schema.kt`,
  `updatePassUserLabel` in `SqlCipherPassRepository`,
  `PassRecordRow` / `PassSummary` / `StoredPass` field additions,
  `StorageError.PassRejected` arm, `PassUpdateRejectedKind` enum,
  `StorageTelemetryGuard.onUserLabelUpdated` event,
  `PassRepositoryContractTest` coverage (set, clear, cap-reject,
  trim-blank-to-null, integrity-violation, no `updated_at` bump),
  `SchemaMigrationTest` v4 → v5 walk-up.
- Implementation bead: `PassIdentityBlock` in `passes-ui`,
  `PassFront.kt` re-wire to call it, `PublicApiSurfaceTest` lock
  on the symbol, `TrustClaimSurfaceTest` cases for the four render
  paths (null, override, override-equal-to-signed, longer label
  wrapping).
- walt-android `wlt-4dn.3`: rename UI + tile / lane surface
  calls `PassIdentityBlock`. Reviewer cites this ADR for the trust
  rule.
- Accessibility surfaces: revisit whether `description` should drive a
  `Modifier.semantics { contentDescription = ... }` on
  `PassIdentityBlock` for TalkBack. This is a follow-on once the
  rename surface lands; the trust rule itself does not depend on it.
- Future "recency of user attention" sort, if walt-android product
  surfaces want it: new column
  (`last_user_touch_epoch_ms` or similar), separate ADR, not a
  re-purposing of `updated_at_epoch_ms`.
