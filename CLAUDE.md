# Walt Passes (Android)

Open-source pass-handling kernel for the Walt Android wallet app. PKPASS parsing, signature verification, encrypted storage, and security-critical UI flows.

## Repository Purpose

This repository exists for **transparency-for-trust**, not library-for-reuse. Walt-android (closed source) consumes this code directly so that users and security researchers can verify what Walt does with their pass data. Reuse by other applications is welcome as a side effect; the primary commitment is the audit trail.

The trust claim: every security-and-privacy-critical behavior Walt makes about pass handling is implemented in code that lives in this repository. Walt-android does not parallel-implement any trust-claim-bearing logic; it wraps and themes what is here.

## Worktree Rule

Each worktree has its **own copy** of all files. Write to your **active worktree's** files, not via the parent's symlinks.

## Architecture Rules

- Three Gradle modules:
  - `passes-core` — pure Kotlin/JVM. Parser, model, signature verifier, `.strings` parser, `TelemetryGuard` interface. NO Android framework dependencies.
  - `passes-storage` — Android-only. SQLCipher with Keystore-sourced key, Android Auto Backup exclusion, irreversible deletion logic.
  - `passes-ui` — Android + Compose. Pass front/back composables, B3 URL confirmation sheet, expired badge, bounded image rendering. Themable via tokens passed in by the consumer.
- `passes-core` has NO Android framework dependencies (KMP-friendly).
- `passes-storage` and `passes-ui` are independent of each other.
- DECISIVE CONSTRAINT: walt-android consumes this code directly; trust-claim-bearing logic lives ONLY here, never reimplemented in walt-android.

## Code Style

- **100% Kotlin** (no Java)
- Sealed interfaces (not sealed classes)
- `Result<T>` over exceptions (matches walt-android conventions)
- StateFlow (not LiveData)
- JUnit 4 + Google Truth for testing
- `kotlinx.serialization` for JSON
- BouncyCastle (JVM) for PKCS#7 / X.509

## Reference Repository (read-only, local-only)

walt-android lives at `../../android/android-main/`. It is closed source and **not** part of this repository. Read-only access is granted via `.claude/settings.local.json` (which is gitignored). Use that path to consult walt-android conventions when designing the consumer-side wrapper modules (`core/data-passes`, `feature/passes`).

Do not commit any walt-android paths or contents into this repository.

## Beads

```bash
bd ready                    # What to work on
bd update wpass-xxx --claim  # Start work
bd close wpass-xxx --reason "Completed and tested"
```

The parent epic `wlt-0tn` lives in walt-android's beads database. Work in this repo references it via the external project mechanism. See `.beads/config.yaml` for the cross-repo configuration.

## Decisions and Memories

Brainstorming-phase decisions are captured as `bd remember` entries:

- `decision-wlt-0tn-q1` through `decision-wlt-0tn-q5` (in walt-android's beads database)
- `foss-pkpass-signature-policy-field-survey` (empirical FOSS signature-policy survey, in walt-android's beads database)

Search them with `bd memories <keyword>` from inside walt-android. As decisions accrue inside this repo, they get persisted similarly.


<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:ca08a54f -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd dolt push
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
<!-- END BEADS INTEGRATION -->
