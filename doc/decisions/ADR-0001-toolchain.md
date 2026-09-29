# ADR-0001 — Toolchain: pinned versions and why each one is not newer

- **Status:** accepted (2026-09-29)
- **Deciders:** build configuration (`gradle/libs.versions.toml`, every `build.gradle.kts`)
- **Referenced by:** `gradle/libs.versions.toml`, all module build scripts (lint `disable` blocks), `core-model` / `core-transfer` / `webshare-server` (`explicitApi()` notes)

## Context

Two requirements pull in opposite directions:

1. **minSdk 23 is a product requirement, not a fallback.** The master prompt asks for
   API 23 devices (Android 6.0) to be first-class: every dependency must still publish
   an API 23-compatible variant, and no code path may reach for an API that does not
   exist there.
2. **targetSdk must be the latest level Google Play accepts.** Play requires updates to
   target API 36 from 31 August 2026, so target 36 is the floor for a shippable build.

Meanwhile the newest Android Gradle Plugin line (AGP 9.x, September 2026) removed the
old DSL types this project's build scripts are written against, made JDK 17 the minimum,
and pulled the whole AndroidX ecosystem up with it: Compose 1.12 requires compileSdk 37,
`androidx.hilt` 1.4.0 declares `minCompileSdk 37` and needs AGP 9.1, and Dagger/Hilt 2.59+
requires the AGP 9 Gradle plugin. Adopting AGP 9 would therefore mean rewriting every build
script against a DSL that cannot be compiled or tested in a checkout without the Android
toolchain — blind writing, with `checkAarMetadata` as the only feedback loop.

A third constraint shapes everything: **not every checkout of this repository has a JDK,
Gradle or the Android SDK.** Compilation, KSP/Hilt processing, lint, unit tests and APK
assembly are proven in GitHub Actions (`.github/workflows/android-ci.yml`) instead, so a
toolchain choice is only as good as the CI run that verifies it.

## Decision

Stay on the last AGP 8.x line and pin every artifact to the newest stable release that
still builds against compileSdk 36 with JDK 17.

| Component | Pinned | Why not newer |
| --- | --- | --- |
| Gradle | **8.13** (wrapper committed) | AGP 8.13's minimum; 8.14+ brings no fix this build needs and would invalidate the committed wrapper |
| Android Gradle Plugin | **8.13.2** | Last 8.x patch (Jan 2026). AGP 9.x removed the DSL types used here and forces compileSdk 37 dependencies |
| Kotlin | **2.2.20** (+ `plugin.compose`) | Newest Kotlin that KSP `2.2.20-2.0.4` is tied to; KSP 2.3.x changed the release format |
| KSP | **2.2.20-2.0.4** | Last release in the tied `kotlin-ksp` format for 2.2.20 |
| compileSdk / targetSdk | **36** | Play's Aug 2026 requirement. API 37 needs AGP 9 + Compose 1.12 + androidx.hilt 1.4.0 |
| minSdk | **23** | Product requirement |
| JDK | **17** | AGP 8.13's requirement; also the toolchain for the three pure-JVM modules |
| Compose BOM | **2026.04.01** → Compose **1.11** | Compose 1.12 requires compileSdk 37 |
| Material 3 | via the BOM | Single source of truth; the BOM is added to `implementation`, `testImplementation` **and** `androidTestImplementation` — a BOM-managed artifact on a configuration the platform is not added to resolves to an *empty* version and breaks dependency resolution plus lint's model task |
| Dagger / Hilt | **2.58** | 2.59 (Jan 2026) made the AGP 9 Gradle plugin mandatory; 2.60.x needs AGP 9 |
| `androidx.hilt` | **1.3.0** | 1.4.0 declares `minCompileSdk 37` and requires AGP 9.1 |
| Room | **2.8.5** | Newest stable that builds against compileSdk 36 |
| Lifecycle | **2.9.1** | as above |
| DataStore | **1.1.7** | as above |
| `core-ktx` | **1.16.0** | as above |
| `activity-compose` | **1.10.1** | as above |
| Navigation Compose | **2.9.8** | 2.10.x tracks the compileSdk 37 line |
| WorkManager | **2.11.2** | 2.12.0 tracks the compileSdk 37 line |
| Media3 | **1.8.1** | Newest stable in the 1.8 line for compileSdk 36 |
| Coroutines / Serialization | **1.10.2** / **1.8.1** | Newest stable compatible with Kotlin 2.2.20 |
| Play services Nearby | **19.3.0** | Newest stable; minSdk 21 |
| `desugar_jdk_libs` | **2.1.5** | Only where a Java 8+ API is genuinely needed below API 24/26 |
| Test: JUnit / androidx.test / Espresso / Robolectric / Turbine | 4.13.2 / 1.2.1 / 3.6.1 / 4.14.1 / 1.2.0 | Newest stable of each |

No alpha, beta or snapshot artifact is used anywhere in the catalog.

### Explicit-visibility policy (`explicitApi()` off in the JVM modules)

`core-model`, `core-transfer` and `webshare-server` are Kotlin/JVM modules with
`explicitApi()` **off**. They are contracts consumed by `:app` and by each other, and they
already declare `public` / `internal` visibility and explicit return types on every
declaration by convention — which `tools/verify/refs.mjs` checks mechanically (visibility
leaks such as a `public` member exposing an `internal` type are compile errors regardless).
Turning on the strict flag would add churn (use-site targets, explicit `public` on nested
and override declarations) without changing what any other module can see. The Android
library modules follow the same written-out-visibility convention.

### Lint policy that follows from the pins

Every module runs lint with `abortOnError = true`, `xmlReport`/`textReport` on, and three
checks disabled: `GradleDependency`, `NewerVersionAvailable`, `AndroidGradlePluginVersion`
(the app additionally disables `MissingTranslation`, since the app ships exactly one
locale — the mockup's English copy). The three version checks are disabled **because of
this ADR**: while the toolchain is pinned, "a newer version is available" is permanently
true for 44 findings that cannot be acted on, and they drown the checks that can fail.
`NewApi` and `UnusedResources` stay enabled — `NewApi` is the automated API 23 gate.

## Consequences

- The build is reproducible from a bare checkout: the Gradle 8.13 wrapper
  (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) is committed, so CI and
  any developer machine run the same Gradle.
- Bumping any single row of the table above is not a one-line change: AGP, Compose BOM,
  Hilt and `androidx.hilt` move together, and the move is gated on compileSdk 37.
- **Revisit triggers:** (a) Play raises its target-API requirement to 37 (expected 2027);
  (b) the project adopts the AGP 9 DSL; (c) a dependency this app needs ships a fix only
  in a compileSdk-37 artifact. Any of these means adopting AGP 9 + JDK 17+ + compileSdk 37
  + Kotlin/KSP + Hilt 2.60+ + androidx.hilt 1.4.0 + Compose 1.12 as one change, then
  re-running the full CI gate.
- Deprecated-but-supported APIs are accepted while the pins hold. The clearest example is
  `androidx.hilt.navigation.compose.hiltViewModel`, deprecated in 1.3.0 in favour of a
  package that ships in the `hilt-lifecycle-viewmodel-compose` artifact of the
  compileSdk-37 line; the deprecation warnings are recorded in
  `doc/qa/lint-and-warnings.md` rather than suppressed.

## Verification

CI run [#16](https://github.com/ahmadyb/Morsec/actions/runs/36568731048)
(commit `d29cd4c`) is the evidence this toolchain is real, not aspirational:

- Kotlin/KSP compilation succeeded for every module that has sources at this milestone
  (`:app`, `:core-model`, `:core-design`, `:core-data`, `:core-storage`); the other five
  modules are configured and report `NO-SOURCE` until their milestone lands.
- Hilt's aggregate tasks (`:app:hiltJavaCompileDebug`, `hiltAggregateDepsDebug`) and Room's
  KSP tasks ran clean, so both annotation processors work on this exact pin set.
- `./gradlew test` was `BUILD SUCCESSFUL` — 69 test methods in 6 JVM suites, 0 failures.
- Lint: **0 errors** (111 warnings, itemised in `doc/qa/lint-and-warnings.md`).
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest` both produced APKs.
- All of it ran through the committed Gradle wrapper: the wrapper step logged
  "the wrapper is committed already; verifying it instead of regenerating", and
  `./gradlew --version` reported Gradle 8.13 from the tracked `gradle-wrapper.jar`.
