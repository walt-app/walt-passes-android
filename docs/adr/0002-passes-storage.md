# ADR 0002: passes-storage architecture

- Status: Accepted
- Date: 2026-05-04
- Tracks: parent epic `wpass-9vv`; design bead `wpass-9vv.3`
- Decision context: `decision-wlt-0tn-q3-4` (deletion mechanism, no VACUUM), `decision-wlt-0tn-q5` (three-module shape), `decision-wlt-0tn-q3-3` (full localization)

## Context

`passes-storage` is the Android-only Gradle module that owns the at-rest representation of imported passes. Its scope is limited and deliberate:

- **Persist** parsed `Pass` values from `passes-core` so the wallet UI does not re-parse on every cold start.
- **Encrypt** that data at rest with a hardware-backed key, so a stolen device or a physical-recovery attempt cannot recover pass content without breaking the device's hardware key store.
- **Exclude** the passes database from Android Auto Backup and device-to-device transfer, so cloud-side compromise of a backup blob does not surface pass content.
- **Delete irreversibly** with no soft-delete, no trash bin, no undo, and with cached decoded data wiped in the same operation.
- **Surface** trust-relevant storage events (`PassImported`, `PassDeleted`, `KeyUnwrapFailed`) through a `StorageTelemetryGuard` whose method signatures structurally forbid PII, mirroring the `passes-core` `TelemetryGuard` discipline.

Out of scope for `passes-storage`: pass acquisition (parser lives in `passes-core`), rendering (Compose lives in `passes-ui`), confirmation dialogs (`passes-ui`), Hilt wiring (lives in walt-android's `core/data-passes`).

This ADR fixes the storage contract today; the implementation bead that follows wires Android Gradle Plugin, SQLCipher, Android Keystore, and the manifest backup-exclusion rules against this contract.

## Decisions

### D1. Single SQLCipher database, single page-level key

The persistent store is one SQLCipher database, `walt_passes.db`, encrypted at the page level with one 32-byte raw key. The library does not maintain per-pass keys.

Rationale: a per-pass key scheme would add complexity (key directory, per-row unwrap-on-read, separate IV/AAD discipline) without changing the threat model. A process-compromise adversary that can read SQLCipher's in-memory key material can read every pass key the same way; a physical-recovery adversary that cannot reach the Keystore master key cannot reach either layer. The only place per-pass keys would help is "selective leak of a single pass key without leaking the database," which is not a threat model `passes-storage` defends against.

### D2. The database key is wrapped, not derived

The DB key is generated once at first open as 32 random bytes from `SecureRandom`, then wrapped (AES-256-GCM) with a master key resident in Android Keystore. The wrapped blob and its IV are persisted in a private SharedPreferences file (`is.walt.passes.storage.key_envelope`) outside the SQLCipher database itself. On subsequent opens, the wrapped blob is unwrapped via Keystore and the resulting raw bytes are passed to SQLCipher's `PRAGMA key`.

The envelope MUST live outside the encrypted database it unlocks: storing it inside `schema_meta` would create a chicken-and-egg (you would need the DB key to read schema_meta, but the schema_meta entry IS the DB key). The earlier draft of this ADR placed the envelope in `schema_meta`; the implementation bead corrected this to a SharedPreferences file. The envelope is itself ciphertext under a Keystore-resident, non-exportable master, so the SharedPreferences file's lack of file-level encryption does not weaken the trust claim. The same `walt_passes_backup_rules.xml` / `walt_passes_data_extraction_rules.xml` rules exclude this preferences file from cloud backup and device-to-device transfer (the SharedPreferences domain is opted out alongside the database).

The envelope is written synchronously (`commit()`, not async `apply()`) before the unwrapped DB key is handed to SQLCipher. A crash between an async write and the disk flush would leave SQLCipher pages encrypted with a key whose envelope was never persisted, irreversibly bricking the at-rest data on next launch.

Why wrapped, not Keystore-derived directly: Android Keystore does not export raw symmetric key bytes for AES keys (the keys are non-exportable by design). SQLCipher requires a raw 32-byte key to derive its page key via PBKDF2. Wrapping is the bridge: the master key never leaves Keystore (so hardware-backing applies), and the unwrapped DB key is held in process memory only as long as the database is open.

StrongBox is requested via `KeyGenParameterSpec.Builder.setIsStrongBoxBacked(true)` and the library falls back to TEE-backed when StrongBox is unavailable. The `StorageTelemetryGuard.onKeyProviderInitialized` event reports which backing was actually used (via the `KeyBacking` enum), so consumers can surface `StrongBox` / `Tee` / `Software` to the user without inspecting strings.

### D3. Schema separates query columns, blob, images, and locales

Four tables. Schema version 1:

```sql
CREATE TABLE schema_meta (
    key   TEXT PRIMARY KEY NOT NULL,
    value BLOB NOT NULL
);
-- Reserved keys: 'schema_version' (TEXT-encoded int), 'wrapped_db_key' (raw bytes),
-- 'wrapped_db_key_iv' (raw bytes), 'key_alias' (TEXT), 'key_backing' (TEXT enum).

CREATE TABLE passes (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    type                  TEXT    NOT NULL,                  -- PassType enum name
    serial_number         TEXT    NOT NULL,
    organization_name     TEXT    NOT NULL,
    description           TEXT    NOT NULL,
    expiration_epoch_ms   INTEGER,                           -- nullable
    voided                INTEGER NOT NULL DEFAULT 0,        -- 0/1
    signature_status_kind TEXT    NOT NULL,                  -- SignatureStatusKind enum name
    pass_json             BLOB    NOT NULL,                  -- kotlinx-serialized Pass minus images and locales
    created_at_epoch_ms   INTEGER NOT NULL,
    updated_at_epoch_ms   INTEGER NOT NULL
);
CREATE INDEX idx_passes_type            ON passes(type);
CREATE INDEX idx_passes_expiration      ON passes(expiration_epoch_ms);
CREATE UNIQUE INDEX idx_passes_identity ON passes(type, serial_number, organization_name);

CREATE TABLE pass_images (
    pass_id INTEGER NOT NULL REFERENCES passes(id) ON DELETE CASCADE,
    role    TEXT    NOT NULL,                                -- ImageRole enum name
    bytes   BLOB    NOT NULL,
    PRIMARY KEY (pass_id, role)
);

CREATE TABLE pass_locales (
    pass_id      INTEGER NOT NULL REFERENCES passes(id) ON DELETE CASCADE,
    locale_tag   TEXT    NOT NULL,                           -- BCP-47, verbatim
    strings_json BLOB    NOT NULL,                           -- kotlinx-serialized LocalizedStrings
    PRIMARY KEY (pass_id, locale_tag)
);
```

Why split: the wallet list view needs the query columns (type, organization, expiration, voided) for tens to low hundreds of passes. Pulling tens of MB of image bytes and locale tables for that view is wasteful. Separating images and locales lets the list query touch only the `passes` row, and the detail view fetches images/locales lazily by `pass_id`.

`pass_json` carries the structured `Pass` body (fields, colors, barcode) so the renderer reconstructs the same object the parser produced, without re-running the PKCS#7 verification. The `signature_status_kind` column captures the trust band at import time so the badge remains visible without re-parsing.

The unique index on `(type, serial_number, organization_name)` enforces PKPASS identity per the Apple spec: a re-imported pass with the same identity replaces the prior row (transactional UPSERT in `PassRepository.upsert`), so updated passes do not accumulate duplicate rows.

### D4. Decoded summary cache is the row itself, not a separate cache layer

The parser-decoded query columns ARE the summary cache. There is no separate Room `@Entity`-style cache, no in-memory `LruCache`, no second source of truth.

Why: a separate cache layer creates two consistency surfaces and a pair of failure modes (cache stale, cache evicted while DB updated). The bead description spoke of a "decoded summary cache"; we deliver that semantic by denormalizing into the row at insert time, so a single transaction maintains both the canonical blob and the queryable summary.

A schema-migration breakage in the decoder (e.g. `pass_json` format changes) is recovered by re-deserializing every `pass_json` blob during the migration; if a row fails to migrate, the migration logs the failure through `StorageTelemetryGuard.onMigrationRowDropped(MigrationFailureKind)` and drops the row. Pass content is recoverable by re-import from the user's original `.pkpass` file; the database is not the system of record.

### D5. Backup exclusion is library-shipped rules + a documented consumer contract

`passes-storage` ships two manifest-merged XML resources:

- `res/xml/walt_passes_backup_rules.xml` — `<full-backup-content>` excluding the passes database for API 23 - 30.
- `res/xml/walt_passes_data_extraction_rules.xml` — `<data-extraction-rules>` excluding the passes database for API 31+ (covers both cloud backup and device-to-device transfer).

The library manifest declares both via `android:fullBackupContent` and `android:dataExtractionRules` on a `<application>` placeholder. Manifest merge applies these to the consumer.

The library does NOT set `android:allowBackup="false"` on the consumer, because that is a process-wide setting and walt-android may legitimately back up other data. The trust claim "pass data is excluded from cloud backup" is delivered by the rules files; consumers are documented to verify their merged manifest includes them.

A consumer-side `assertBackupRulesApplied()` helper is exposed from `passes-storage` for use in instrumentation tests, so the trust claim is checkable in CI rather than relying on documentation review.

Two consumer postures are valid and the assertion accepts both:

1. **Inherit.** The consumer lets the library's manifest contributions reach the merged manifest unchanged. `ApplicationInfo.fullBackupContent` and `ApplicationInfo.dataExtractionRulesRes` then reference `walt_passes_backup_rules` and `walt_passes_data_extraction_rules` directly.
2. **Mirror.** The consumer overrides the library contributions with `tools:replace="android:fullBackupContent,android:dataExtractionRules"` and points the manifest at a consumer-owned XML resource that mirrors the library's required `<exclude>` entries (and optionally adds its own). Walt-android takes this posture so it can manage backup posture for the whole app from a single resource.

The assertion validates by content, not resource identity. It opens whichever XML resource the merged manifest points at and checks that every entry in `BackupRulesAssertion.REQUIRED_EXCLUDES` is present in every backup-relevant section of that resource (`<full-backup-content>` for the API 23 - 30 path; both `<cloud-backup>` and `<device-transfer>` for the API 31+ path). Consumers may add additional excludes; only the pass-related entries are required for the trust claim. Setting `android:allowBackup="false"` app-wide trivially satisfies the claim and short-circuits the assertion.

### D6. Deletion is items 1+3+4+5+6 from `decision-wlt-0tn-q3-4`

`PassRepository.delete(id)`:

1. Begins a transaction.
2. Issues `DELETE FROM passes WHERE id = ?` (cascades to `pass_images` and `pass_locales`).
3. Commits.
4. Updates the in-memory `StateFlow<List<PassSummary>>` to remove the deleted entry, before returning.
5. Emits `StorageTelemetryGuard.onPassDeleted(PassType, SignatureStatusKind)` (no PII).

What is intentionally NOT included:

- No `VACUUM` (Q3.4): SQLCipher's page encryption + non-exportable Keystore master + Android FBE + backup opt-out is the load-bearing erasure; `VACUUM` cannot reach flash wear-leveled physical bits anyway.
- No undo, no trash bin, no soft-delete column: the row goes; "unscheduled un-deletion" is a feature gap, not a defect.
- No confirmation dialog: that lives in `passes-ui` (the `B3 URL confirmation sheet` family). `passes-storage` trusts callers; the UI owns intent-confirmation.

### D7. The repository surface is `Result<T, StorageError>`-style, not exceptions

`PassRepository` returns `StorageResult<T>`, a sealed interface (`Success`, `Failure(StorageError)`), where `StorageError` is itself sealed (`KeyUnavailable`, `KeyUnwrapFailed`, `DatabaseLocked`, `IntegrityViolation`, `Unsupported`, `Unknown`).

Rationale: matches CLAUDE.md's `Result<T> over exceptions` rule and gives the consumer the same partition `passes-core` already provides for parse failures. `KeyUnwrapFailed` in particular is a security-relevant signal (possible Keystore tampering / deleted key) and must be distinguishable from `DatabaseLocked` (concurrent open).

### D8. `passes-storage` exposes interfaces, not Android types, at its API boundary

Public-API types of `passes-storage`:

- `PassRepository` interface
- `PassKeyProvider` interface (the Keystore-backed implementation is the `AndroidKeystorePassKeyProvider` class, public-but-only-constructable-via-`create(context)`)
- `StorageResult`, `StorageError` sealed interfaces
- `StoredPass`, `PassSummary` data classes
- `StorageTelemetryGuard` interface and its event types
- `KeyBacking` enum
- `BackupRulesAssertion.assertBackupRulesApplied(context)` helper, so consumer instrumentation tests can verify the manifest-merge in CI

Internal-only: SQLCipher cursor handling, PRAGMA invocation, the `AndroidKeystorePassKeyProvider` class body, the `SqlCipherPassRepository` class body. (An earlier draft listed manifest-assertion helpers as internal-only; the implementation bead promoted `BackupRulesAssertion` to the public surface so the trust claim "rules are applied" is checkable from the consumer's instrumentation test suite.)

Rationale: walt-android's `core/data-passes` wraps `PassRepository` behind a Hilt-provided binding; if `passes-storage` leaks `SQLiteDatabase` or `Cursor` into the public API, walt-android picks up an incidental dependency on those types and the contract gets harder to swap (e.g. for an in-memory test double). Keeping the surface narrow keeps the contract testable on the JVM with a fake `PassKeyProvider` and an in-memory SQLite.

## Key flow

```mermaid
sequenceDiagram
    participant UI as walt-android UI
    participant Repo as PassRepository (passes-storage)
    participant KP as AndroidKeystorePassKeyProvider
    participant KS as Android Keystore
    participant DB as SQLCipher DB

    UI->>Repo: open()
    Repo->>KP: provideDatabaseKey()
    KP->>KP: read SharedPreferences(envelope)
    alt first launch
        KP->>KS: getOrCreateMasterKey(alias, StrongBox preferred)
        KS-->>KP: SecretKey handle (non-exportable)
        KP->>KP: dbKey = SecureRandom 32 bytes
        KP->>KS: AES-256-GCM wrap(dbKey)
        KS-->>KP: wrappedBlob, iv
        KP->>KP: SharedPreferences.commit(envelope)  // synchronous, fsync-bounded
    else subsequent launches
        KP->>KS: AES-256-GCM unwrap(wrappedBlob, iv)
        KS-->>KP: dbKey (raw 32 bytes, in-process)
    end
    KP-->>Repo: dbKey
    Repo->>DB: openOrCreateDatabase(path, dbKey)
    Repo->>DB: PRAGMA cipher_compatibility = 4
    Repo->>DB: PRAGMA foreign_keys = ON
    Repo->>Repo: zero out dbKey from local buffers
    Note over Repo,DB: Repo holds an open SQLiteDatabase handle;<br/>raw key bytes live only inside SQLCipher's internal page-key derivation buffer.
```

The raw DB key bytes are zeroed in the `PassKeyProvider` after they are handed to SQLCipher. SQLCipher's own internal copy lives for the lifetime of the open connection; the library does not attempt to control SQLCipher's internal memory.

## Consequences

- The implementation bead can land Android Gradle Plugin, SQLCipher, Keystore wiring, and manifest XML against a fixed contract; no public-API renegotiation.
- walt-android's `core/data-passes` can write its Hilt module today, binding the `PassRepository` interface to a stub for early integration work.
- `passes-storage` builds on JVM-only today (Kotlin/JVM module mirroring `passes-core`); the AGP wiring is a follow-on. Tests of the contract types and schema DDL run on the JVM CI host.
- `StorageTelemetryGuard` carries the same security-policy review gate that `TelemetryGuard` does: any future addition of a `String` (or `Pass`, or `PassField`) parameter is a security-policy change, not an API addition.
- The decoded summary cache is the row itself, so any future "richer summary" (e.g. event ticket subtype) requires a column add and a schema migration, not a new cache layer.

## Open follow-ups

- Implementation bead: AGP wiring, SQLCipher dependency, manifest rules XML, `AndroidKeystorePassKeyProvider` body, schema DDL execution, instrumentation tests covering Auto Backup exclusion via `bmgr` shell harness.
- Migration tooling: schema version 2 candidates (event ticket subtype indexing, barcode message search) are deferred until the wallet feature surfaces them.
- StrongBox availability matrix: which devices in the walt-android target population actually have StrongBox is a deployment-data question for walt-android, not for this module.
- Multi-user / work-profile behavior: the library assumes per-user Android profiles isolate Keystore aliases (they do); no extra logic is required, but the implementation bead's instrumentation tests should cover work-profile install once.
