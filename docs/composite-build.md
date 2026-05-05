# Consuming walt-passes-android via composite Gradle build (v1)

Status: current for v1. This is the supported way for a consumer Gradle build to
depend on the three `walt-passes-android` library modules until the GitHub
Packages migration described in [ADR 0004](adr/0004-distribution-and-github-packages.md)
ships.

The DECISIVE CONSTRAINT recorded against the parent epic `wpass-9vv` is that
trust-claim-bearing logic lives only in this repository: walt-android consumes
the implementations directly rather than parallel-implementing them. Composite
build is what makes "directly" cheap in the Gradle sense — the consumer's
compiler reads the same source files that any auditor reads on GitHub.

## Coordinates

The three modules publish under group `is.walt`. The artifactId of each module
equals its Gradle project name. Versioning follows ADR 0004: the trunk carries
a `-SNAPSHOT` suffix; tagged releases drop it.

| Coordinate                                 | Module             | Origin                              |
|--------------------------------------------|--------------------|-------------------------------------|
| `is.walt:passes-core:0.1.0-SNAPSHOT`       | `:passes-core`     | Pure Kotlin/JVM library (jar)       |
| `is.walt:passes-storage:0.1.0-SNAPSHOT`    | `:passes-storage`  | Android library (aar)               |
| `is.walt:passes-ui:0.1.0-SNAPSHOT`         | `:passes-ui`       | Android + Compose library (aar)     |

Group, version, and artifactId are stamped on every module by the convention
plugins (`KotlinLibraryConventionPlugin`, `AndroidLibraryConventionPlugin`).
The single source of truth is `gradle.properties` keys `walt.passes.group` and
`walt.passes.version`. To bump the trunk version, edit those — never set
`group` or `version` in a module's `build.gradle.kts`.

## Consumer setup

The consumer is expected to check out `walt-passes-android` as a sibling of its
own repo:

```
~/workspace/walt/
  android/                       ← consumer repo
    android-main/
  passes-android/                ← producer repo (this one)
    passes-android-main/
```

In the consumer's `settings.gradle.kts`, register an included build that points
at the producer's checkout:

```kotlin
// android/android-main/settings.gradle.kts
includeBuild("../../passes-android/passes-android-main")
```

Then declare normal Gradle dependencies in any module that needs the kernel.
Gradle's composite-build dependency substitution matches `(group, name)` and
swaps the external coordinate for the included project at configuration time,
so the consumer never resolves these from a Maven repository:

```kotlin
// android/android-main/core/data-passes/build.gradle.kts
dependencies {
    implementation("is.walt:passes-core")
    implementation("is.walt:passes-storage")
}

// android/android-main/feature/passes/build.gradle.kts
dependencies {
    implementation("is.walt:passes-core")
    implementation("is.walt:passes-ui")
}
```

A version is not required on the dependency declaration; substitution ignores
it. A version may be supplied for documentation, in which case it should match
the trunk's `walt.passes.version` so a future cutover to artifact resolution
(see ADR 0004) keeps working without edits to call sites.

## Caveats and constraints

1. **Sibling-checkout assumption.** The `includeBuild(...)` path is filesystem-
   relative. Consumers that put repos in non-sibling locations adjust the path
   themselves; producer-side guidance assumes the layout above to match how
   walt-android operators are already set up.
2. **No third-party use of composite build is supported.** The trust-claim
   audience is reading source on GitHub; the build-mode audience is walt-
   android. Third parties that want to depend on `walt-passes-android` are
   welcomed but should wait for the v1.5 GitHub Packages release described in
   ADR 0004 instead of replicating the composite-build workflow.
3. **Build-logic does not get composed.** The producer's `build-logic/`
   convention plugins are scoped to the producer and do not become available
   to the consumer's modules. The consumer keeps its own conventions; the
   `is.walt.passes.*` plugin IDs are an internal contract here.
4. **Version catalogs are independent.** The consumer's `libs.versions.toml`
   and the producer's are separate. `:passes-core` does not export third-
   party deps it does not `api(...)`; the consumer pulls in its own copies of
   shared transitives the same way it does for any other Maven dependency.
5. **CI must check out both repos.** Consumer CI (the closed walt-android
   repo) must clone `walt-passes-android` next to itself before running
   Gradle. Without the included build present on disk, configuration fails
   with "Project ... not found" before any compilation happens.

## Trust-claim framing

This file exists in the open repo on purpose. A reader investigating "what does
Walt actually run when it parses my pass" finds:

- a `wallet` app (closed source) that imports `is.walt:passes-core`,
- a path on disk that resolves to source files in this repository, and
- a chain of build-logic plugins, written in this repository, that compile
  those files.

The chain is short and uniform across the three modules; nothing is renamed,
relocated, or rewritten on the way into walt-android. That is the property the
audit story trades on, and composite build is what preserves it cheaply during
v1.
