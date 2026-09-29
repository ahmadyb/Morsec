# Morsecode project handoff

You are continuing an existing Android project. Do not restart, redesign,
replace, or regenerate the repository.

## Repository

GitHub repository:
https://github.com/ahmadyb/Morsec

Working branch:
arena/01a0e98a-morsec

Last confirmed pushed commit:
d9cd14965255f4f096195d09a253da3a4f78b1e4

(The commit that adds the "Session state" section below is docs-only and sits on
top of it. Always trust `git ls-remote origin arena/01a0e98a-morsec` over any SHA
written in this file.)

Before changing anything:

1. Clone or open the existing repository.
2. Fetch all remote branches.
3. Check out `arena/01a0e98a-morsec`.
4. Run `git status`.
5. Run `git log --oneline --decorate -10`.
6. Read the files listed below.
7. Report the actual repository state.
8. Do not write code until you confirm the branch and existing work.

If the branch has commits newer than the SHA above, continue from the
newest remote commit. Never reset to the older SHA or overwrite newer work.

---

## Session state — 2026-09-29: CI validation of Milestone 1 is COMPLETE

Written by the agent that executed the CI-validation task. Every number here comes
from GitHub Actions output, not from local inspection: the development sandbox has
no JDK, Gradle or Android SDK and cannot download them, so **CI is the only place
this project is actually compiled**. Do not trust a local `./gradlew` attempt there.

### 1. Current branch

`arena/01a0e98a-morsec`, branched from `main` at `4e8cef8`. All work is pushed to
this branch only. History has never been rewritten, rebased over remote work, or
force-pushed.

### 2. Latest pushed commit SHA

`d9cd14965255f4f096195d09a253da3a4f78b1e4` — *"Clear 70 of the 111 lint warnings
and write the docs the build references"* — the newest commit that changes code.
The docs-only commit adding this section follows it.

Confirm before doing anything:

```bash
git ls-remote origin arena/01a0e98a-morsec
gh run list --branch arena/01a0e98a-morsec --limit 3
```

### 3. Latest green CI run

| Run | Commit | Result |
| --- | --- | --- |
| [#18](https://github.com/ahmadyb/Morsec/actions/runs/36577006338) | `d9cd149` | **success**, all 18 steps — newest code run |
| [#17](https://github.com/ahmadyb/Morsec/actions/runs/36574837295) | `18347b9` | **success** — the commit that first added this handoff file |
| [#16](https://github.com/ahmadyb/Morsec/actions/runs/36568731048) | `d29cd4c` | **success** — the first green run |

Workflow: `.github/workflows/android-ci.yml` — `ubuntu-latest`, Temurin JDK 17,
the preinstalled Android SDK plus `platform-tools`, `platforms;android-36` and
`build-tools;36.0.0`, Gradle 8.13 through `gradle/actions/setup-gradle@v4`.
Triggers: push to this branch, pull requests, `workflow_dispatch`.

Exact Gradle invocations, in order (all `--no-daemon --stacktrace`):

1. `checkMilestoneHygiene`
2. `test --continue`
3. `:app:lintDebug :core-design:lintDebug :core-data:lintDebug :core-storage:lintDebug :transport-lan:lintDebug :transport-nearby:lintDebug :media:lintDebug --continue`
4. `:app:assembleDebug`
5. `:app:assembleDebugAndroidTest`

### 4. Completed milestone

**Milestone 1 of 12 — implemented, compiled, tested and CI-verified.**
`core-model/FeatureReadiness.kt` reads `CURRENT_MILESTONE = 1`, `FINAL_MILESTONE = 12`;
it is bumped only when a milestone is genuinely delivered, and
`tools/verify/token-parity.mjs` fails if the final milestone is reached while
anything is still gated.

Milestone 1 acceptance is still **yours to give**: per the completion gate below,
Milestone 2 does not start until you approve explicitly.

### 5. Exact implemented features

**`:app`** (30 Kotlin files) — `MorseApplication` (`@HiltAndroidApp`), single
`MainActivity` (Compose, no fragments), navigation graph over
`Routes`: onboarding, connect, files, history, settings + nested logs, crashes,
doctor, help; bottom navigation exactly as the mockup (CONNECT · FILES · HISTORY ·
SETTINGS); 9 `@HiltViewModel`s; `FeatureGate` routing every not-yet-delivered
action to an honest explanation sheet.

- **Onboarding** — 4 cards, and the only first-run permission prompt: a real
  contextual storage request issued by card 2's primary button.
- **Connect** — transport copy is exactly "LAN or Nearby" (`transport_label`),
  recent devices come from Room, and every control that needs a later milestone is
  routed through `FeatureGate`: send/receive → `TRANSFER_ENGINE` (M5), broadcast →
  `SESSIONS_AND_BROADCAST` (M9), the WebShare card (which shows the real configured
  port and running state) → `WEBSHARE_SERVER` (M11).
- **Files** — five category tabs (Photos, Videos, Music, Apps, Files) via
  `CategoryTabRow`, three-column grid at the reference width
  (`metrics.gridColumnsAtReferenceWidth`), list layout for music/apps/files,
  multi-select with a selection bar (share outside the app, gated send, clear),
  sort sheet, SAF folder grants (persisted, revocation detected), real thumbnails
  (`ThumbnailLoader`), thin straight scrollbar overlays.
- **History** — Received/Sent direction tabs, real search backed by a Room query,
  date grouping (Today/Yesterday), per-entry overflow: open, share, send again
  (gated), remove from history, delete the file through `ContentResolver`.
- **Settings** — device name (rename dialog), accent-family swatches, theme mode,
  granted folders (SAF add/remove), a battery-optimisation shortcut, entries to
  Logs / Crashes / Doctor / Help, and replay-onboarding. The WebShare port is *not*
  a Settings control yet: it surfaces on the Connect card and in the Doctor's port
  check, and `DiagnosticAction.CHANGE_WEBSHARE_PORT` is gated — the settings row for
  it (within `NetworkPorts.USER_PORT_RANGE`) is Milestone 2–4 work.
- **Connection Doctor** — 10 checks that read the real device state: Wi-Fi
  transport, multicast permission, location-for-scan, runtime permissions, storage
  access, revoked grants (only shown when there are any), battery optimisation,
  notifications, Play services presence, and port conflicts. Each failing check
  offers a corrective action that launches a real system screen; the API 26
  notification-settings intent is guarded by `Build.VERSION_CODES.O` and falls back
  to the app details screen on API 23–25. The Nearby-specific checks
  (`DOCTOR_NEARBY`) land with milestone 7.
- **Logs / Crashes** — filter by level, export to a real file through
  `FileProvider`, share, clear; crash reports are viewable, exportable and
  clearable, and are never uploaded.

**`:core-model`** (14 files, pure JVM) — `TransferState` state machine with an
explicit `allowedTransitions` map and `requireTransition()`; `ItemActions`, the
per-file action matrix (Active: pause+cancel, Paused: resume+cancel, Queued:
cancel, Failed: retry only, Completed: none); `Session`/`Peer`; `NetworkPorts`
(WebShare 33455, peer control 33456, discovery beacon 33457, 1.2 s beacon
interval, 4× staleness window, user port range); `WebShareSession` contracts
(`BrowserSession`, `BrowserSessionState`, `WebTransferRecord`); `MediaItem`,
`History`, `Diagnostics`, `Settings` (clamping `require` blocks); `MorseFormatters`
with localised units injected from `:app`; `FeatureReadiness`; DI qualifiers.

**`:core-design`** (11 files + 61 generated vector drawables) — dark and light
palettes, accent families (sunflower, leaf, ember, violet, sky), status colours,
`MockupTokens` (values lifted verbatim from the mockup CSS), `MorseMetrics` (all
dimensions: radii 8/12/16/24/28/999, 180 ms `bezier(0.2,0,0,1)`, pressed scale
0.97), `MorseType` (all type sizes), `MorseTheme`, components (`Atoms`,
`Surfaces` incl. scrollbar overlays for `LazyListState` and `LazyGridState`,
`Navigation`, `Overlays`, `TouchTarget`), `MorseIcons`.

**`:core-data`** (19 files) — Room `MorseDatabase`: **9 entities / 9 DAOs**
(`transfer_sessions`, `transfer_items`, `history`, `recent_devices`,
`log_entries`, `crash_reports`, `browser_sessions`, `saf_grants`,
`web_transfers`), mappers so entities never cross a module boundary, repositories
(Transfer, History, Device, Diagnostics, WebShare, Settings), one Preferences
DataStore file with corrupt-store fallback and clamped writes, `MorseLogger` +
`RoomMorseLogger`, `LogRedactor` (authorization headers, bearer tokens, long
opaque blobs, app-private paths, `/storage/emulated/…`, `/sdcard/…` → `[redacted]`;
SHA-256 digests deliberately kept), `CrashRecorder`, Hilt modules.

**`:core-storage`** (7 files) — `MediaStoreReader`, `SafTreeReader` (tree grants,
persisted-permission release), `InstalledAppsReader` (uses the manifest's
`<queries>` LAUNCHER intent, never `QUERY_ALL_PACKAGES`), `DefaultMediaRepository`,
`PermissionMatrix` (per-API-level permission sets: media read split at 33, write
only ≤ 28, fine location ≤ 32, Bluetooth scan/advertise/connect ≥ 31,
POST_NOTIFICATIONS ≥ 33).

**Manifest** — contextual permission set with the tightest `maxSdkVersion` each
one allows; `FileProvider` over cache `exports/` and files `shares/`;
include-only backup and data-extraction rules (only `files/datastore/` is ever
backed up — the database, `incoming/`, `exports/` and the cache stay on device);
adaptive launcher icon with background, foreground and monochrome layers.

**Modules with a build script and no sources yet** (correct for Milestone 1; CI
reports `NO-SOURCE` for them): `:core-transfer` (M5), `:transport-lan` (M6),
`:transport-nearby` (M7), `:media` (M10), `:webshare-server` (M11). The npm
project `webshare-ui` (M12) does not exist yet.

**Tooling and docs** — `tools/gen/icons.mjs` (+ `icons.json`) generates and checks
the 61 drawables; `tools/verify/token-parity.mjs` (191 assertions, mockup ↔
Compose tokens); `tools/verify/refs.mjs` (31 check groups: catalog consistency,
Compose API shapes, visibility leaks, imports, named-argument ↔ parameter
agreement across 202 callables, Hilt graph satisfiability, resource-XML
well-formedness, milestone hygiene). Docs: `README.md`, `doc/architecture.md`,
`doc/decisions/ADR-0001-toolchain.md`, `doc/decisions/ADR-0002-webshare-server.md`,
`doc/qa/lint-and-warnings.md`, `doc/fidelity-notes.md`, `doc/release.md`.

### 6. Tests and build artifacts

- **JVM unit tests: 69 distinct `@Test` methods in 6 suites, 0 failures, 0
  skipped.** CI reports **98 test executions across 9 report files**, because the
  three Android library modules run their unit tests for both the debug and the
  release variant: `ModelContractTest` 14, `TransferStateTest` 14,
  `MorseFormattersTest` 12 (all `:core-model`, single variant), `ColorTokensTest`
  12 (`:core-design`, ×2), `LogRedactorTest` 10 and `MappersTest` 7 (`:core-data`, ×2).
- **`checkMilestoneHygiene`: passes** — no `TODO(`/`FIXME`/stub markers in the 10
  source roots.
- **Android lint: 0 errors, 41 warnings** across 7 module reports. Every warning
  has a written verdict in `doc/qa/lint-and-warnings.md`.
- **Artifacts uploaded by every run:** `morsecode-debug-apk`
  (`app-debug.apk` **17.90 MiB**, `app-debug-androidTest.apk` **1.10 MiB**),
  `test-and-lint-reports` (JUnit XML, lint XML/HTML/text, the captured Gradle
  logs), `gradle-wrapper` (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`,
  `gradle-wrapper.properties`).
- **Gradle wrapper: committed** (`b2e0664`, created by CI because this sandbox
  cannot download Actions artifacts). Runs #16, #17 and #18 all logged *"the
  committed wrapper was used as-is … nothing was regenerated"*, so CI is verified
  against the committed wrapper.
- **Instrumentation tests are assembled, not executed** — the workflow has no
  emulator. `:app:assembleDebugAndroidTest` proves `src/androidTest` (including
  `MorseTestRunner`) compiles; running it needs `connectedDebugAndroidTest` on a
  device or an emulator job.

### 7. Known issues

1. **Two approved-UI behaviours are still missing in Files:** horizontal swipe
   between the five categories (no `HorizontalPager` anywhere yet), and the
   internal folder browser — `Routes.FOLDER` (`folder/{treeUri}`) is declared but
   has no `composable(...)` registration and no screen, so the breadcrumb /
   no-up-arrow decisions are not implemented yet. `Routes.WEBSHARE` is likewise
   unregistered, correctly, until milestone 11–12.
2. **41 lint warnings**, all with a verdict in `doc/qa/lint-and-warnings.md`:
   27 `UnusedResources` (mockup tokens reserved for later milestones — do not
   delete them, `token-parity` asserts them), 11 `PluralsCandidate` (single-locale
   English copy from the mockup), 2 `SelectedPhotoAccess` (Android 14 partial
   photo access, deferred to the media-depth work), 1 `OldTargetApi` (targetSdk 36
   is deliberate, ADR-0001).
3. **28 Kotlin compiler warnings**: `androidx.hilt.navigation.compose.hiltViewModel`
   is deprecated (its replacement package ships in the `androidx.hilt` line that
   requires compileSdk 37, and this project pins 1.3.0), plus Kotlin 2.2
   annotation-use-site-target notices on resource-id annotations. Neither is
   suppressed in code.
4. **No emulator in CI**, so nothing verifies runtime behaviour — only
   compilation, unit tests, lint and packaging.
5. **Five modules are empty** until milestones 5–12 (see §5).
6. **Sandbox limits:** no local Android build; Actions **logs and artifacts cannot
   be downloaded** (blob hosts unreachable); `gh workflow run` returns 403 with the
   bot token, so runs are triggered by pushing. That is why the workflow
   republishes failure text, lint findings and the headline test/lint/APK numbers
   as check-run annotations — keep that mechanism when editing the workflow.
   Recipe:
   ```bash
   JOB=$(gh run view <run-id> --json jobs --jq '.jobs[0].databaseId')
   gh api repos/ahmadyb/Morsec/check-runs/$JOB/annotations --paginate      --jq '.[] | "[\(.annotation_level)] \(.title) :: \(.message)"'
   ```
7. **The sandbox's local `.git` rolled back five times** (HEAD reverted to
   `4e8cef8`, worktree intact, committed work appearing untracked). Recovery, in
   one shell so it cannot be interrupted: set the fetch refspec, `git fetch origin
   +refs/heads/arena/01a0e98a-morsec:refs/remotes/origin/arena/01a0e98a-morsec`,
   `git reset --mixed origin/arena/01a0e98a-morsec`, `git branch --set-upstream-to=…`,
   then `git status` before committing. **Never `reset --hard`** (it deletes
   uncommitted work) and never force-push. The GitHub token also expires
   mid-session: re-authenticate in Arena, never paste a token into chat.
8. **`README.md`'s build snippet is one line stale** — it still says to run
   `gradle wrapper --gradle-version 8.13`; the wrapper is committed, so `./gradlew`
   works directly.
9. **Release builds are unsigned** unless `keystore.properties` exists
   (`doc/release.md`); CI builds debug only.

### 8. Next unstarted milestone

**Milestone 2** — the first of the README's 2–4 band: *"Depth inside the
milestone-1 areas: full Files browsing/selection and the per-file action matrix,
History search and filters, Settings completeness, Logs/Crashes export."* No new
`FeatureArea` unlocks before milestone 5 (`TRANSFER_ENGINE`), so milestone 2 must
not introduce transfer, transport, service, playback or WebShare behaviour — it
deepens browsing, selection and the screens that already exist, to full mockup
fidelity. Milestones 5–12 stay planned, in order.

### 9. The exact first task for the next AI

**Step 0 — verify, do not assume (no code yet):**

```bash
git fetch origin '+refs/heads/*:refs/remotes/origin/*'
git checkout arena/01a0e98a-morsec && git status
git log --oneline --decorate -10
gh run list --branch arena/01a0e98a-morsec --limit 3
node tools/verify/refs.mjs && node tools/verify/token-parity.mjs && node tools/gen/icons.mjs --check
```

Expect: tip at or after `d9cd149`, a clean tree, the latest run green, and
31/31 · 191/191 · 61 from the harnesses. Then read, in this order:
`doc/AI_HANDOFF.md`, `doc/architecture.md`, `doc/qa/lint-and-warnings.md`,
`README.md`, `doc/fidelity-notes.md`, `doc/decisions/ADR-0001-toolchain.md`, and
the Files/History/Settings sections of `doc/morsecode_master_build_prompt.md`
against the same screens in `doc/morsecode_material3_mockup.html`.

**Then ask for explicit approval to begin Milestone 2 and stop.** Writing
Milestone 2 code before that approval violates the completion gate below.

**Step 1 — the first code task, once approved** (both parts are verified gaps,
listed as known issue 1):

1. **Swipe between Files categories.** Add `rememberPagerState` + `HorizontalPager`
   to `FilesScreen` with exactly five pages, bound to the *same* state the tab strip
   already drives (`FilesViewModel` tab selection), so a swipe and a tab tap are one
   behaviour with one source of truth. `CategoryTabRow` stays pinned above the pager
   and must never scroll away, must not gain a native horizontal scrollbar, and the
   selected tab keeps bold accent text, the subtle selected tint and the straight
   underline. Keep the three-column grid, the thin straight scrollbar overlays and
   the existing per-tab empty/permission states; `LaunchedEffect` must survive a
   page change without re-showing a consumed one-shot message.
2. **The internal folder browser for `Routes.FOLDER`.** Register
   `composable(Routes.FOLDER)` with the `treeUri` argument, add a screen that lists
   children through `MediaRepository`/`SafTreeReader.children(treeUri)`, keeps the
   breadcrumb visible below the sticky header, has **no upward-arrow control**, and
   reuses the Files selection model (same per-item action rules, same selection bar).

**Step 2 — the gate after every change, unchanged from milestone 1:** run the three
Node harnesses, push, watch CI to green (`gh run watch`), read failures through the
annotations recipe above, fix the cause (never baseline, disable an error-severity
check, delete a test or add `continue-on-error`), compare the result against the
mockup, and report what was implemented and what remains. Then, and only then,
continue with the rest of milestone 2.

### 10. Product decisions that must not be reversed

Everything under **Approved UI decisions** below still stands, unchanged. These
were added during CI validation and carry the same weight:

- **The toolchain pins move together or not at all** (ADR-0001): AGP 8.13.2,
  Gradle 8.13, Kotlin 2.2.20, KSP `2.2.20-2.0.4`, compile/target SDK 36, minSdk 23,
  JDK 17, Compose BOM 2026.04.01, Hilt **2.58**, `androidx.hilt` **1.3.0**. Hilt
  2.59+ needs AGP 9; `androidx.hilt` 1.4.0 and Compose 1.12 need compileSdk 37.
  Bumping one row without the rest breaks `checkAarMetadata`.
- **minSdk 23 is real, not nominal.** `NewApi` lint stays enabled in every module;
  API-level differences are handled with `Build.VERSION` branches (as the Doctor's
  notification action now does), never with a suppression that hides a dead button.
- **Backup policy is include-only:** just `files/datastore/`. The Room database,
  `incoming/`, `exports/` and the cache never leave the device. Do not add
  `<exclude>` entries for paths outside an include — that is the
  `FullBackupContent` error this project already paid for.
- **UI strings are resolved in composition** (`stringResource` / hoisted vals),
  never through `LocalContext.current` inside a lambda or an effect.
- **Lint keeps `abortOnError = true` with no baseline.** The only disabled checks
  are four warning-severity ones (`GradleDependency`, `NewerVersionAvailable`,
  `AndroidGradlePluginVersion`, `MissingTranslation`), each justified in
  `doc/qa/lint-and-warnings.md`.
- **Nothing is faked ahead of its milestone.** Gated actions go through
  `FeatureGate` and say which milestone makes them real; `CURRENT_MILESTONE` is
  bumped only on delivery. No simulated progress, no placeholder control that
  appears to succeed, no sample data.
- **The Compose BOM must stay on `implementation`, `testImplementation` *and*
  `androidTestImplementation`** in any module using a BOM-managed artifact — a
  BOM-managed dependency on a configuration without the platform resolves to an
  *empty* version and breaks resolution plus lint's model task.
- **The committed Gradle wrapper is authoritative.** CI verifies it instead of
  regenerating it, and only commits a new one if the tracked copy is missing.
- **Design tokens have exactly one home each:** dimensions in `MorseMetrics`, type
  sizes in `MorseType`, mockup-derived values in `MockupTokens`, colours in
  `MorseColorTokens`. `token-parity.mjs` (191 assertions) enforces agreement with
  the mockup and must stay green.
- **Logs are redacted before they are written**, and SHA-256 digests are kept on
  purpose so a transfer stays auditable.
- **CI must keep republishing its own diagnostics as annotations** (failures, lint
  findings, test/lint/APK totals): in some environments that is the only readable
  output of a run.

---

## Authoritative product files

Read these completely before proceeding:

1. `morsecode_master_build_prompt.md`
2. `morsecode_material3_mockup.html`
3. `README.md`
4. `doc/fidelity-notes.md`
5. `doc/release.md`
6. Existing Gradle configuration
7. Existing source and test modules
8. Any `AI_HANDOFF.md`, milestone report, or CI workflow in the repository

The master prompt defines the application requirements.

The HTML file is the authoritative visual and behavioral reference.
Do not replace its approved UI with a generic Material template.

## Product summary

Morsecode is a local file-sharing Android application supporting:

- Android 6/API 23 through the latest Android version
- LAN or Nearby device discovery
- Android-to-Android transfers
- Bidirectional sessions
- Phone-to-multiple-phone broadcasts
- Background transfers
- Photo, video, music, apps, and file browsing
- Image viewer
- Music player
- Video player
- Embedded WebShare server and browser client
- Logs, crash reports, diagnostics, and history
- No cloud, accounts, advertisements, analytics, or automatic crash uploads

## Approved UI decisions

Do not reverse these decisions:

- Use native Kotlin and Jetpack Compose.
- Minimum Android SDK is API 23.
- The transport label is `LAN or Nearby`.
- There is no Manual IP control.
- There is no QR pairing control.
- Mobile Files categories are:
  - Photos
  - Videos
  - Music
  - Apps
  - Files
- All five category tabs fit the phone width.
- Category tabs remain sticky while scrolling.
- Category tabs have no native horizontal scrollbar.
- The selected tab has bold accent text, a subtle selected tint, and a
  straight underline.
- Horizontal swipe changes mobile file categories.
- Photo and video grids use three columns at the reference phone width.
- Mobile scroll indicators are thin straight lines.
- The redundant address bar above mobile Files/Categories was removed.
- The internal folder browser does not have an upward-arrow control.
- Its breadcrumb stays visible below the sticky header.
- The separate transfer Queue screen was removed.
- Queue controls are integrated into transfer screens.
- Transfer screens are:
  - Sending + receiving
  - Receiving + sending back
- Per-file actions are:
  - Active: Pause and Cancel
  - Paused: Resume and Cancel
  - Queued: Cancel
  - Failed: Retry only
  - Completed: no transfer action icons
- Retry is per file, not global.
- There is only one global Pause all / Resume all action.
- The heading action clears completed items for that section.
- Bottom transfer actions are:
  - Add files
  - Pause all / Resume all
  - Background
  - End
- Image and video viewer overflow buttons were removed.
- WebShare has no `All videos` pseudo-folder.
- WebShare has no duplicate video table below the video grid.
- WebShare Quick Access and folder panels remain pinned while content
  scrolls.
- WebShare breadcrumbs remain visible while file rows scroll.
- Crash reports are viewable, exportable, and clearable.
- Crash reports are never uploaded automatically.

## Milestone state

Milestone 1 was implemented and pushed.

Reported implementation includes:

- 10 Android modules
- Kotlin/Compose application shell
- Hilt
- Room
- DataStore
- Storage adapters
- Navigation foundation
- Design tokens and reusable components
- Onboarding
- Connect
- Files
- History
- Settings
- Logs
- Crash reports
- Connection Doctor
- Help
- Node-based token/reference verification
- 69 JVM unit tests

**Update 2026-09-29:** the environment limitation below was removed by moving the
build into GitHub Actions. Those 69 tests have now really been executed (98 test
executions across 9 reports, 0 failures), the project really compiles, Hilt and
Room really process, lint really runs (0 errors) and both APKs really assemble.
See "Session state" for the runs.

Milestone 1 is therefore **implemented and CI-verified**; it is still waiting for
your explicit acceptance before Milestone 2 begins.

The previous environment could not compile Android code because it had
no JDK, Gradle, or Android SDK and could not download them. That is still true of
the sandbox — CI is the only build path.

Node verification scripts are useful, but they are not substitutes for:

- Kotlin compilation
- Compose type checking
- Hilt graph processing
- KSP/Room generation
- Android resource processing
- Manifest merging
- Android lint
- JVM tests
- APK assembly

## Exact starting point — COMPLETED 2026-09-29

The first task was CI validation of Milestone 1. It is **done**: the workflow
exists, it is green, the wrapper is committed, and CI has been re-verified against
that committed wrapper. The evidence and the next task are in "Session state"
above (§3, §6, §9).

Do not begin Milestone 2 without explicit approval.

Create or inspect:

`.github/workflows/android-ci.yml`

Use GitHub Actions to provide:

- Ubuntu runner
- JDK 17
- Android SDK Platform 36
- Gradle 8.13
- Generated Gradle wrapper
- Dependency resolution
- Kotlin/Android compilation
- Unit tests
- Android lint
- Debug APK assembly

The CI workflow must run:

- `checkMilestoneHygiene`
- `test`
- `lintDebug`
- `:app:assembleDebug`

It must upload:

- Debug APK
- Test reports
- Lint reports
- Generated Gradle wrapper files, if the repository still lacks them

After pushing the workflow:

1. Trigger it using GitHub CLI.
2. Monitor it.
3. Read complete failure logs.
4. Fix every build, test, lint, resource, dependency, Hilt, KSP, Room,
   manifest, and Kotlin error.
5. Push fixes.
6. Rerun CI.
7. Repeat until green.
8. Download and commit the verified Gradle wrapper if it is absent.
9. Run CI again using the committed wrapper.

All nine steps were carried out. Step 8 needed one adaptation: the sandbox cannot
download Actions artifacts, so the workflow commits the generated wrapper back to
the branch itself (with `GITHUB_TOKEN`, which cannot retrigger a run) — commit
`b2e0664`. Step 9 then happened three times: runs #16, #17 and #18 all built with
the committed wrapper.

Nothing was hidden or bypassed to get there: no lint baseline, no
`abortOnError = false`, no deleted or skipped test, no `continue-on-error` on a
build step. The only checks switched off are four *warning*-severity ones, each
with a written justification in `doc/qa/lint-and-warnings.md`. Every failure was
repaired at its cause across runs #1–#15 (toolchain pins, Gradle DSL, Hilt/KSP
wiring, 13 app-module Kotlin errors, the Compose BOM on test configurations,
backup rules, and six composition-scope resource reads).

Do not hide or bypass compiler/lint/test failures merely to make CI green.

## Completion gate for the current task

Stop before Milestone 2.

The current task is complete only when you provide:

1. A green GitHub Actions run URL.
2. The final pushed commit SHA.
3. Exact Gradle tasks executed.
4. Unit-test count and results.
5. Android lint result.
6. A downloadable debug APK artifact.
7. Confirmation that the Gradle wrapper is committed.
8. A list of warnings or remaining limitations.

**Status: all eight were provided** — §3 (run URLs), §2 (SHA), §3 (Gradle tasks),
§6 (tests), §6 (lint), §6 (APK artifacts), §6 (wrapper committed), §7 (warnings
and limitations).

Only after I explicitly approve Milestone 1 may you begin Milestone 2.

## Anti-drift rules

- Do not create a new project.
- Do not switch to Flutter, React Native, or XML layouts.
- Do not redesign the approved interface.
- Do not remove existing modules because they look complex.
- Do not replace real requirements with simulated behavior.
- Do not skip API 23 compatibility.
- Do not advance to another milestone without permission.
- Do not claim success without a real build and test run.
- Do not reset, force-push, or rewrite working Git history.
- Do not modify the master prompt or mockup unless I explicitly request it.
- Do not discard existing work because it has compile errors; repair it.
- Keep commits focused and push them to the existing working branch.

Begin by verifying the repository and branch. Then report what you found
and execute only the CI-validation task described above.
