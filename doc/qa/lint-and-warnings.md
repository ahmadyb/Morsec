# Lint and compiler warnings — the accepted position

Lint runs with `abortOnError = true` on all seven Android modules in CI
(`.github/workflows/android-ci.yml`), so **an error fails the build**. Warnings do not —
which is exactly why every one of them is written down here with a verdict. A warning that
nobody has decided about is a warning nobody will ever fix.

CI republishes the findings as check-run annotations ("Publish lint findings"), because the
Actions log and artifact hosts are unreachable from some environments. On a green run the
headline numbers arrive as `::notice::` annotations (`unit tests`, `lint`, `apk`); on a red
run every error-severity finding arrives with its file and line.

## Position at the first green run

Run [#16](https://github.com/ahmadyb/Morsec/actions/runs/36568731048), commit `d29cd4c`:
**0 lint errors, 111 warnings** across 13 check ids.

| Check | Count | Verdict |
| --- | --- | --- |
| `GradleDependency` | 27 | **Disabled** — toolchain is pinned, see ADR-0001 |
| `NewerVersionAvailable` | 14 | **Disabled** — same |
| `AndroidGradlePluginVersion` | 3 | **Disabled** — Gradle 8.13 is the committed wrapper |
| `UnusedResources` | 28 | 1 fixed (`ic_launcher_ink` is now the launcher glyph's fill); 27 **accepted**, see below |
| `PluralsCandidate` | 11 | **Accepted**, see below |
| `UseKtx` | 11 | **Fixed** — every `Uri.parse(x)` is now `x.toUri()` |
| `InlinedApi` | 9 | **Fixed** — 7 suppressed with a real justification, 2 replaced by a genuine API 26 guard |
| `SelectedPhotoAccess` | 2 | **Deferred** to the media-depth milestone, see below |
| `MonochromeLauncherIcon` | 2 | **Fixed** — both adaptive icons declare a `<monochrome>` layer |
| `OldTargetApi` | 1 | **Accepted** — targetSdk 36 is deliberate (ADR-0001) |
| `RedundantLabel` | 1 | **Fixed** — the activity's duplicate label is gone |
| `SdCardPath` | 1 | **Suppressed with justification** — it is a redaction pattern, not a path |
| `ModifierParameter` | 1 | **Fixed** — `MorseEmptyState` takes `modifier` as its first optional parameter |

Expected residual after that pass: **41 warnings** (`UnusedResources` 27, `PluralsCandidate`
11, `SelectedPhotoAccess` 2, `OldTargetApi` 1). The `lint` notice annotation on the next run
states the real number.

## Why the residual warnings stay

**`UnusedResources` (27).** `core-design` and `app/src/main/res` carry the mockup's complete
palette, metric set and copy — including values only later milestones consume (transfer
progress colours, WebShare session states, player chrome). `tools/verify/token-parity.mjs`
asserts 191 of those values match the mockup one-for-one, so deleting them to satisfy lint
would break fidelity to the source of truth. `UnusedResources` stays *enabled* on purpose:
it is how a token that no milestone ever adopts gets noticed. Each remaining finding should
become used, or be removed together with its token-parity assertion — not silenced.

**`PluralsCandidate` (11).** The app ships one locale: the mockup's English copy. Plurals
are already used where English itself differs — `connect_devices_nearby` ("1 device nearby"
/ "2 devices nearby"), `settings_crash_count`, `time_days_ago`. The remaining candidates
(`%1$d selected`, `%1$d items`, `Maximum %1$d phones`, `%1$d min ago`, …) read identically
for one and many in English, and the mockup specifies that exact copy. They get converted
when a second locale is added, which is also when `MissingTranslation` gets re-enabled.

**`SelectedPhotoAccess` (2).** Android 14's partial photo access means declaring
`READ_MEDIA_VISUAL_USER_SELECTED`, requesting it alongside the media permissions on API 34+,
and giving the user a way back in when they chose "Select photos" — otherwise the Files tab
silently shows a subset. That is a behaviour change across `PermissionMatrix`, the Files and
Settings screens and the Doctor, so it belongs to the file-browsing/media depth work rather
than to a warning cleanup. Until then the app requests full media access on API 33+ exactly
as it does on API 23–32, and both paths are covered by the permission matrix's unit tests.

**`OldTargetApi` (1).** targetSdk 36 with compileSdk 36 is the newest level AGP 8.13
supports, and the level Play requires from 31 August 2026. API 37 means AGP 9, Compose 1.12,
Hilt 2.60+ and `androidx.hilt` 1.4.0 together — one coordinated move, tracked as the revisit
trigger in ADR-0001.

## Deliberately disabled checks

`GradleDependency`, `NewerVersionAvailable`, `AndroidGradlePluginVersion` (all modules) and
`MissingTranslation` (`:app`). While the toolchain is pinned, 44 "a newer version is
available" findings are permanently true and permanently unactionable; leaving them on hides
the checks that can fail. `MissingTranslation` is off because there is one locale. The
reasoning and the revisit triggers are in
[`../decisions/ADR-0001-toolchain.md`](../decisions/ADR-0001-toolchain.md).

`NewApi` is **not** disabled anywhere: it is the automated API 23 gate, and it is the reason
the Doctor's notification action now branches on `Build.VERSION.SDK_INT` instead of firing an
API 26 intent that would silently do nothing on an Android 6 phone.

## Kotlin compiler warnings (not lint)

CI's annotation publisher buckets `w:` lines as `kotlin-warning` and prints the count per
log; two classes are known and accepted:

1. **`hiltViewModel` is deprecated** (`androidx.hilt.navigation.compose.hiltViewModel`:
   nine call sites across eight screens): "Moved to package: androidx.hilt.lifecycle.viewmodel.compose".
   The replacement ships in the `hilt-lifecycle-viewmodel-compose` artifact of the
   `androidx.hilt` line that declares `minCompileSdk 37`. This project pins
   `androidx.hilt` **1.3.0** (ADR-0001), so the deprecated function is the correct one to
   call today; it moves when the whole toolchain moves.
2. **Annotation use-site target** warnings ("This annotation is currently applied to the
   value parameter only, but in the future it will also be applied to field/property"),
   from resource-id annotations on constructor parameters and data-class properties
   (`MorseDestination`, `DoctorViewModel`, `OnboardingScreen`, `HelpScreen`, …). This is
   Kotlin 2.2 announcing a future default change; adding `@param:`/`@field:` use-site targets
   is a mechanical sweep best done together with the Kotlin bump in ADR-0001's revisit.

Neither class is suppressed in code: a suppressed warning cannot be re-measured, and both
are decisions recorded here instead.

## Debugging a red lint run without log access

```bash
JOB=$(gh run view <run-id> --json jobs --jq '.jobs[0].databaseId')
gh api repos/ahmadyb/Morsec/check-runs/$JOB/annotations --paginate \
  --jq '.[] | select(.annotation_level=="failure") | "\(.title)\n\(.message)\n"'
```

Titles are `<log> — <bucket>`; lint findings arrive as `lint errors: N part M` with
`path:line [CheckId] message` entries. The raw text and XML reports are also written to
`*/build/reports/lint-results-debug.{txt,xml,html}` and uploaded in the
`test-and-lint-reports` artifact for environments that can download it.
