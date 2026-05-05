# ADR 0004: distribution mechanism and GitHub Packages migration plan

- Status: Accepted (v1 composite build); Proposed (v1.5 GitHub Packages cutover)
- Date: 2026-05-05
- Tracks: parent epic `wpass-9vv`; bead `wpass-9vv.7`
- Decision context: `decision-wlt-0tn-q4` (separate repo, Apache 2.0, composite
  Gradle build for v1, GitHub Packages later)
- Related: [`docs/composite-build.md`](../composite-build.md)

## Context

Two questions have to be answered together for `walt-passes-android`:

1. How does walt-android (closed source) consume the three library modules
   (`passes-core`, `passes-storage`, `passes-ui`) shipped by this repository?
2. How can a third-party auditor or open-source reuser consume them without
   needing access to walt-android's local-checkout topology?

The brainstorming-phase decision (`decision-wlt-0tn-q4`) said: composite Gradle
build for v1, GitHub Packages later. v1 is now real, and the producer side of
the composite build has to land before walt-android can wire it up
(`wlt-4pg` in the consumer repo). At the same time, the project has accumulated
enough of an audit posture that "later" needs to be more than a hand-wave — a
follow-up bead has to be cheap to spawn, with the version strategy and
publishing surface already settled.

This ADR fixes both halves: the v1 mechanism (already in effect after this
bead lands) and the v1.5+ migration plan (gated on a yet-to-be-filed bead).

## Decisions

### D1. v1 distribution: composite Gradle build, sibling checkouts

walt-android adds `includeBuild("../../passes-android/passes-android-main")` in
its `settings.gradle.kts`. Module dependencies are declared as
`implementation("is.walt:passes-core")` and so on; Gradle's dependency
substitution matches on `(group, module-name)` and resolves the coordinate to
the included project at configuration time.

This is documented operationally in [`docs/composite-build.md`](../composite-build.md);
the rest of this section is the rationale for choosing this over the
alternatives.

**Rejected alternatives:**

- *Monorepo*: would put the trust-claim-bearing code in the closed repo and
  break the audit story (the central decisive constraint in `wpass-9vv`).
- *Local Maven publish*: forces every contributor on the consumer side to run
  `./gradlew publishToMavenLocal` before each build, easy to forget, hard to
  validate in CI without a wrapper script that this ADR would still have to
  describe. Composite build subsumes the workflow with one Gradle line.
- *git submodule*: orthogonal to dependency resolution. Consumers still have to
  point the build at the submodule path, and submodules add their own UX
  problems (detached HEAD, two-step pulls). Not worth the extra moving piece.
- *Maven Central from day one*: committing to Maven Central before we know the
  API is stable creates a graveyard of `0.x` artifacts under the `is.walt`
  group that we cannot retract. The audit posture does not yet need it; the
  reusers we would attract are a v1.5+ concern.

**Coordinates** are pinned in `gradle.properties` (`walt.passes.group=is.walt`,
`walt.passes.version=...`), and the convention plugins in `build-logic/` stamp
those onto every library module. There is no per-module `group =` / `version =`
line for any contributor to forget.

### D2. v1.5+ distribution: GitHub Packages with Maven Publish

The follow-up cutover publishes the same three coordinates to GitHub Packages
(`https://maven.pkg.github.com/walt-app/walt-passes-android`). Composite build
remains supported as a development-time mode for engineers working on both
repos at once; GitHub Packages is the published mode that decouples the
consumer from a sibling-checkout assumption and lets third-party auditors
depend on a published artifact without negotiating a local layout.

GitHub Packages was chosen over Maven Central for v1.5 because:

- The org already has a `walt-app` GitHub presence and credentials there, with
  no separate Sonatype OSSRH account to provision.
- Token-based consumer auth fits the closed walt-android workflow without
  exposing an API key broadly.
- It lets us iterate on the publishing pipeline (signing, version strategy,
  release workflow) before promoting any artifact to Maven Central, which is
  effectively unretractable.

Maven Central is not ruled out as a v2 destination; the publishing pipeline
designed below is portable to it (the Sonatype publishing plugin and Maven
Central differ only in the repository URL and the staging steps).

#### D2.1 Plugin surface

Each library module applies `maven-publish` and `signing` (the latter gated on
release builds; SNAPSHOTs publish unsigned). The convention plugins gain a
small `PublishingConventionPlugin` that wires both, so contributors do not
duplicate the publication block per module.

Sketch of what the publishing plugin does on a module:

```kotlin
// build-logic/convention/src/main/kotlin/PublishingConventionPlugin.kt
class PublishingConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("maven-publish")
        pluginManager.apply("signing")

        // Android library modules expose a `release` software component once
        // singleVariant("release") { withSourcesJar(); withJavadocJar() } is
        // declared in the android { publishing { ... } } block. Pure-JVM
        // modules expose `java`. The plugin picks the right one.
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications.create<MavenPublication>("release") {
                    from(components[componentName()])
                    pom {
                        name.set(provider { "${rootProject.name}:${project.name}" })
                        description.set("Walt's open pass-handling kernel — ${project.name}")
                        url.set("https://github.com/walt-app/walt-passes-android")
                        licenses { license { name.set("Apache-2.0") } }
                    }
                }
                repositories {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/walt-app/walt-passes-android")
                        credentials {
                            username = providers.gradleProperty("gpr.user")
                                .orElse(providers.environmentVariable("GITHUB_ACTOR")).get()
                            password = providers.gradleProperty("gpr.token")
                                .orElse(providers.environmentVariable("GITHUB_TOKEN")).get()
                        }
                    }
                }
            }

            extensions.configure<SigningExtension> {
                isRequired = isReleaseVersion()  // SNAPSHOTs publish unsigned
                useInMemoryPgpKeys(
                    providers.environmentVariable("SIGNING_KEY").orNull,
                    providers.environmentVariable("SIGNING_PASSPHRASE").orNull,
                )
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }
}
```

The implementing bead must, additionally:

- Add `singleVariant("release") { withSourcesJar(); withJavadocJar() }` to the
  Android library convention plugin's `LibraryExtension.publishing { ... }`
  block, and `withSourcesJar()` + `withJavadocJar()` for the JVM library
  convention plugin.
- Decide whether to publish `-sources.jar` for `passes-storage` (the encrypted
  storage module is the most security-sensitive surface and the audit story
  benefits from sources being on Packages, not just on GitHub). Default
  recommendation: yes, ship sources for all three modules.

#### D2.2 Versioning and release workflow

- **Branch trunk**: `0.X.Y-SNAPSHOT` in `gradle.properties`. Pushes to `main`
  publish a SNAPSHOT to GitHub Packages on every successful CI run on
  `main` (a separate workflow from the PR-validation workflow already in
  `.github/workflows/ci.yml`).
- **Release**: a tagged commit (`v0.X.Y` annotated tag) triggers
  `.github/workflows/release.yml`. The workflow:
  1. checks out the tagged ref,
  2. asserts `gradle.properties` `walt.passes.version` matches the tag (no
     `-SNAPSHOT`),
  3. runs `./gradlew :passes-core:publish :passes-storage:publish :passes-ui:publish`
     against the `GitHubPackages` repository with `SIGNING_KEY` /
     `SIGNING_PASSPHRASE` from repository secrets,
  4. creates a GitHub Release with auto-generated notes from the tag's commit
     range.
- **Post-release**: a separate PR bumps `walt.passes.version` to the next
  `-SNAPSHOT`. This is the only place in the repo a version literal lives, so
  the bump is one line.

SemVer applies, with the strict constraint: any change to a module's public
API (`explicitApi = ExplicitApiMode.Strict` is already on for both convention
plugins; the surface that escapes the module is exactly what `dump-api`-style
tooling would catch) is a major bump until 1.0.0, and a minor bump after.

#### D2.3 Consumer auth for GitHub Packages

GitHub Packages requires authentication even for public packages. Consumers
add to their `~/.gradle/gradle.properties` (or set the equivalent environment
variables in CI):

```properties
gpr.user=<github-username>
gpr.token=<personal-access-token-with-read:packages>
```

The token requires only `read:packages`. walt-android's CI uses the
`GITHUB_TOKEN` injected into Actions runs; no new secret is needed.

The walt-android-side change to consume from GitHub Packages — declaring the
repository in `dependencyResolutionManagement.repositories` and dropping the
`includeBuild` line — is the cutover work item, tracked by a yet-to-be-filed
bead in the consumer repo. That bead replaces (does not extend)
`wlt-4pg`-equivalent composite-build wiring.

#### D2.4 Cutover plan from composite build to artifact resolution

The transition is staged so that the consumer can switch one module at a time
and roll back without coordinating a producer-side change:

1. **Producer**: ship the publishing pipeline. SNAPSHOTs land in GitHub
   Packages on each `main` push. The composite-build path stays supported.
2. **Consumer (preview)**: an opt-in flag (`waltPasses.useArtifacts=true` in
   `~/.gradle/gradle.properties`) routes the consumer's settings to declare
   the GitHub Packages repository instead of `includeBuild(...)`. Engineers
   sanity-check the SNAPSHOT path locally.
3. **Producer**: cut a `0.1.0` tagged release. Versions in walt-android
   switch from "no version specified" to "0.1.0".
4. **Consumer (default)**: flip the default for `waltPasses.useArtifacts` to
   `true`. Composite build remains an opt-in mode for engineers actively
   developing both repos.
5. **Producer (long-term)**: composite build stays in the matrix indefinitely.
   It is the right tool for active cross-repo development; the artifact path
   is the right tool for steady-state consumption.

### D3. Out of scope for this ADR / for the bead implementing it

- Maven Central. Designed for portability; not pursued in v1.5.
- Snapshots-on-PR. Only `main` SNAPSHOTs publish; PR builds remain validation-
  only. Avoiding fork-PR security exposure to publish credentials.
- Reproducible builds. Worth pursuing later but not part of this ADR.
- Per-module independent versioning. All three modules ship at the same
  version, locked through `walt.passes.version`. Independent versioning is
  reconsidered if `passes-core` ever stabilizes much faster than the Android
  modules (which is plausible — the JVM module is the smallest API surface).

## Consequences

- The producer-side composite-build coordinates are stable for v1, and the
  shape of `is.walt:passes-{core,storage,ui}` already matches what the
  GitHub Packages migration will publish.
- The version literal lives in exactly one place (`gradle.properties`), so
  the eventual release-workflow assertion in §D2.2 is one grep.
- The publishing pipeline is sketched but not written. The implementing bead
  is in scope for a v1.5 milestone; until it lands, third parties are asked
  to read the source on GitHub or use composite build at their own risk.
- ADRs 0001–0003 fix the API and theming contracts; this ADR fixes how those
  contracts are *delivered*. A future ADR may revisit the publishing target
  if Maven Central or another registry becomes a better fit.
