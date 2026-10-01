# Morsecode — architecture

Morsecode is a **local file-sharing app**: pick files on one Android phone, move them to
another phone over **LAN or Nearby**, and let a third device with only a browser join the
same session through an embedded WebShare server. Everything happens on the local network;
nothing is uploaded to a service.

This document is the map the module boundaries in `settings.gradle.kts` refer to. It
describes what exists **now** and which milestone delivers what does not — the same
distinction the app itself makes through `FeatureReadiness` (see
[Feature readiness](#feature-readiness-and-the-no-fake-behaviour-rule)).

Companion documents:

- [`decisions/ADR-0001-toolchain.md`](decisions/ADR-0001-toolchain.md) — pinned versions and why each is not newer
- [`decisions/ADR-0002-webshare-server.md`](decisions/ADR-0002-webshare-server.md) — the embedded HTTP server's API 23 allowlist
- [`qa/lint-and-warnings.md`](qa/lint-and-warnings.md) — every lint/compiler warning that is accepted, and why
- [`fidelity-notes.md`](fidelity-notes.md) — how the UI tracks the Material 3 mockup
- [`release.md`](release.md) — signing and release builds

## Module map

| Module | Type | Responsibility | Sources at |
| --- | --- | --- | --- |
| `:app` | Android application | Single activity, Compose navigation graph, ViewModels, Hilt graph root, foreground services, permissions, notifications, WebShare asset hosting | **M1** |
| `:core-model` | Kotlin/JVM | Domain models, enums, the transfer state machine, formatters, `NetworkPorts`, `FeatureReadiness`, DI qualifiers | **M1** |
| `:core-design` | Android library | Mockup design tokens (colours, metrics, type) and the reusable Compose component set | **M1** |
| `:core-data` | Android library | Room database (entities, DAOs, mappers), DataStore settings, repositories, logging + redaction, crash recorder | **M1** |
| `:core-storage` | Android library | MediaStore / SAF / legacy-storage adapters, the runtime permission matrix, installed-apps reader | **M1** |
| `:core-transfer` | Kotlin/JVM | Protocol framing, checksums, resume, the queue engine | M5 |
| `:transport-lan` | Android library | UDP discovery beacons, TCP control and data channels | M6 |
| `:transport-nearby` | Android library | Google Play services Nearby Connections transport | M7 |
| `:webshare-server` | Kotlin/JVM | Embedded HTTP/1.1 server + local JSON API (ADR-0002) | M11 |
| `:media` | Android library | Media3 playback, MediaSession, metadata helpers | M10 |
| `webshare-ui` | npm (not Gradle) | TypeScript/CSS browser client; its production bundle is embedded into `:app` assets | M12 |

`:core-transfer`, `:transport-lan`, `:transport-nearby`, `:webshare-server` and `:media`
exist as configured modules with a build script and no sources yet, which is why CI reports
`NO-SOURCE` for their compile and KSP tasks.

### Dependency rules

1. **Nothing depends on `:app`.** It is the composition root and the only module that knows
   about the activity, the nav graph and the services.
2. **`:core-model` depends on nothing but Kotlin, coroutines and serialization.** It is the
   vocabulary every other module shares, and it is deliberately off the Android classpath so
   its contracts (states, transitions, formatters, port rules) are plain JVM tests.
3. **Android-specific code lives in exactly one place per concern.** Storage access in
   `:core-storage`, persistence in `:core-data`, pixels in `:core-design`, sockets in the
   transport modules.
4. **Transports are interchangeable.** `:transport-lan` and `:transport-nearby` will both
   implement the transport contract declared in `:core-transfer`; neither may reference the
   other, and `:app` picks one at runtime. The user-facing copy always says "LAN or Nearby".
5. **JVM modules may not touch `android.*`.** That rule is what makes ADR-0002's API 23
   allowlist enforceable by construction.

## Layers

### UI — `:app/ui`, `:core-design`

Compose + Material 3, one activity (`MainActivity`), no fragments. Navigation is a single
`NavHost` in `navigation/MorseApp.kt` over the routes in `navigation/Routes.kt`:

- Bottom navigation, exactly as in the mockup: **CONNECT · FILES · HISTORY · SETTINGS**
  (`MorseDestination`).
- Nested routes reached from those tabs: `doctor`, `logs`, `crashes`, `help`, `webshare`,
  and `folder/{treeUri}`.
- `onboarding` is the start destination until it is completed; it issues the **contextual**
  storage request on its second card — the only permission prompt the first run shows.

Every screen is `Screen` + `ViewModel` in the same package (`connect`, `files`, `history`,
`settings`, `doctor`, `logs`, `crashes`, `onboarding`, `help`), with a `RootViewModel`
driving the start-destination decision. Screens read state with
`collectAsStateWithLifecycle()` and never mutate a repository directly.

`:core-design` holds the mockup's design language and nothing else:

- `tokens/MorseColorTokens.kt` — dark and light palettes, accent families (sunflower, leaf,
  ember, violet, sky), status colours, pressed/raised variants.
- `tokens/MorseMetrics.kt` — every dimension and fraction (radii `xs8/sm12/md16/lg24/xl28/full999`,
  paddings, pressed scale 0.97, the 180 ms `bezier(0.2, 0, 0, 1)` transition).
- `tokens/MockupTokens.kt` — the values lifted verbatim from the HTML mockup, kept separate
  so `tools/verify/token-parity.mjs` can assert 1:1 parity with the stylesheet.
- `theme/MorseTheme.kt`, `component/*` (`Atoms`, `Surfaces`, `Navigation`, `Overlays`,
  `TouchTarget`), `icon/MorseIcons.kt` + 61 generated vector drawables.

Type sizes live in `MorseType`, dimensions in `MorseMetrics` — one home per token kind, so a
mockup value is never duplicated in two places.

### Domain — `:core-model`

Pure Kotlin. The pieces the rest of the app leans on hardest:

- **`TransferState`** — the state machine: `allowedTransitions` is an explicit map,
  `canTransitionTo()` / `requireTransition()` enforce it, and `itemActions()` derives the
  per-file action matrix from the state (Completed gets no control icons, Failed gets Retry
  only). `TransferStateTest` covers every legal and illegal edge.
- **`ItemActions`** — the four booleans the UI is allowed to render, so a screen cannot
  invent a control the matrix does not permit.
- **`Session` / `Peer` / `NetworkPorts`** — session identity, peer records, and the three
  fixed ports (WebShare 33455, peer control 33456, discovery beacon 33457) plus
  beacon interval and staleness window.
- **`WebShareSession`** — `BrowserSession`, `BrowserSessionState`, `WebTransferRecord`,
  `WebTransferState`: the browser-pairing contract the server (M11) will implement.
- **`Formatters` / `MorseFormatters`** — byte, rate, duration and relative-time formatting,
  with the localised unit strings injected from `:app` (`di/AppModule.kt`) so the JVM module
  stays Android-free.
- **`FeatureReadiness`** — the milestone gate described below.
- **`model/di`** — dispatcher qualifiers (`IoDispatcher`, `DefaultDispatcher`, …).

### Data — `:core-data`

Room database `MorseDatabase` with nine entities and their DAOs:

| Table | Holds |
| --- | --- |
| `transfer_sessions` | One row per send/receive session: peer, direction, transport, aggregate state |
| `transfer_items` | Per-file rows: state, bytes, checksum, resume offset, error |
| `history` | Completed/failed entries the History tab lists |
| `recent_devices` | Peers worth offering again on the Connect tab |
| `log_entries` | App log, indexed by timestamp and level |
| `crash_reports` | Captured throwables, indexed by occurrence time |
| `browser_sessions` | WebShare sessions; stores a **token digest**, never a token |
| `saf_grants` | Persisted SAF tree grants and whether they are still readable |
| `web_transfers` | Browser upload/download records, resumable after a restart |

Entities never cross a module boundary: `db/Mappers.kt` converts to and from `:core-model`
types, and repositories (`TransferRepository`, `HistoryRepository`, `DeviceRepository`,
`DiagnosticsRepository`, `WebShareRepository`, `SettingsRepository`) are the only interface
the app sees. `DataStoreSettingsRepository` keeps settings in one Preferences DataStore
file (`files/datastore/morsecode_settings.preferences_pb`), falls back to defaults on a
corrupt store, and clamps every write to the ranges the model's `require` blocks accept.

Logging is a first-class concern: `MorseLogger` writes through `RoomMorseLogger`, and
**every** message and stack trace passes `LogRedactor` first — authorization headers,
bearer tokens, long opaque blobs, private app paths, `/storage/emulated/…` and `/sdcard/…`
are replaced with `[redacted]`, while SHA-256 digests are deliberately kept so a transfer
can still be audited. `CrashRecorder` captures uncaught throwables into `crash_reports`.

### Storage — `:core-storage`

The adapters that turn "the user's files" into `MediaItem`s on every supported API level:
`MediaStoreReader` (API 29+ and the legacy MediaStore), `SafTreeReader` (tree grants via
`DocumentFile`, with persisted-permission release), `InstalledAppsReader` (the Apps tab,
using the manifest's `<queries>` LAUNCHER intent rather than `QUERY_ALL_PACKAGES`), and
`DefaultMediaRepository` which composes them into one `MediaRepository`. The Photos,
Videos and Music flows merge MediaStore rows with matching files discovered recursively
inside valid SAF grants; the folder browser keeps using direct children so it remains
hierarchical. MediaStore permission is not required to read a file the user granted via SAF.

`permissions/PermissionMatrix` is the single source of truth for *which* permission a
feature needs *at this API level* — media read (split at 33), media write (23–28 only),
LAN discovery (fine location ≤ 32), Nearby (Bluetooth scan/advertise/connect ≥ 31),
notifications (≥ 33). Screens ask for their own set at the moment the user triggers the
action; nothing is requested at launch.

### Transports, media, WebShare (later milestones)

`:transport-lan` (M6) will own the UDP beacon on 33457 and the TCP control/data channels on
33456; `:transport-nearby` (M7) the Play services path, with `DOCTOR_NEARBY` checks landing
in the same milestone. `:core-transfer` (M5) holds the framing, checksums and resume engine
both plug into. `:media` (M10) wraps Media3 for received audio/video. `:webshare-server`
(M11) and `webshare-ui` (M12) implement ADR-0002.

## Dependency injection

Hilt, with `MorseApplication` as the `@HiltAndroidApp` root and nine `@HiltViewModel`s.
Graph shape:

- `:app/di/AppModule` — application-level bindings, including the localised
  `MorseFormatters` strings and dispatcher providers.
- `:core-data/di/DataModule` — database, DAOs, DataStore, logger, crash recorder.
- `:core-data/di/RepositoryModule` — `@Binds` from each implementation to its repository
  interface.
- `:core-storage/di/StorageModule` — SAF and MediaStore data-source adapters, app reader
  and `MediaRepository`.
- Dispatchers are qualified (`IoDispatcher`, `DefaultDispatcher`, …) and provided once.

The graph is complete: every `@Inject` constructor's dependencies are satisfiable, which
`tools/verify/refs.mjs` checks statically (31 `@Inject` constructors, 14 `@Provides`,
10 `@Binds`, 0 unsatisfied) and KSP/Hilt verifies for real in CI
(`:app:hiltJavaCompileDebug`).

## Feature readiness and the no-fake-behaviour rule

`core-model/FeatureReadiness.kt` pairs every feature area with the milestone that delivers
it (`CURRENT = 2`, `FINAL = 12`). The ready areas are `ONBOARDING`, `SETTINGS`,
`DESIGN_SYSTEM`, `PERSISTENCE`, `FILE_BROWSING`, `DIAGNOSTICS` and `DOCTOR_PLATFORM`;
`TRANSFER_ENGINE`, `LAN_TRANSPORT`, `NEARBY_TRANSPORT`, `BACKGROUND_SERVICE`,
`SESSIONS_AND_BROADCAST`, `MEDIA_PLAYBACK`, `WEBSHARE_SERVER`, `WEBSHARE_CLIENT` and
`DOCTOR_NEARBY` remain gated for their delivery milestones.

`ui/common/FeatureGate.kt` is how that stays honest on screen: an action whose area is not
ready opens an explanation sheet instead of pretending to work. There is no simulated
progress, no placeholder button that appears to succeed, and no sample data — a control
either does the real thing or says which milestone makes it real.

## Verification

Two layers, neither a substitute for the other.

**Local harnesses (Node, no toolchain required)**

- `tools/verify/token-parity.mjs` — 191 assertions that the Compose tokens, `MorseMetrics`,
  `MorseType` and the mockup's CSS agree value-for-value.
- `tools/verify/refs.mjs` — 31 groups of static checks over the Kotlin sources: version
  catalog consistency, Compose API shapes (`Modifier.padding` overloads, `infiniteRepeatable`
  vs `tween` parameters), visibility leaks, imports (including cross-package top-level names
  and 39 framework annotations), named-argument ↔ callee-parameter agreement across 202
  callables, the Hilt graph's satisfiability, resource-XML well-formedness, and milestone
  hygiene (no forbidden markers or stub language).
- `tools/gen/icons.mjs --check` — the 61 vector drawables match the mockup artwork.

**CI (`.github/workflows/android-ci.yml`, 18 steps)** — the authoritative gate, because the
Node harnesses cannot compile Kotlin: Temurin JDK 17 → SDK Platform 36 + build-tools 36.0.0
→ Gradle 8.13 (wrapper verified or generated) → `checkMilestoneHygiene` → `test` →
`lintDebug` for all seven Android modules → `:app:assembleDebug` →
`:app:assembleDebugAndroidTest` → summaries, lint findings and failure text republished as
check-run annotations → APK, reports and wrapper uploaded → the generated wrapper committed
back when it is not tracked yet. Lint findings are republished because the Actions log and
artifact hosts are unreachable from some environments; see
[`qa/lint-and-warnings.md`](qa/lint-and-warnings.md).

## Milestones

1. Foundations, design system, navigation, real file browsing, persistence, diagnostics — **delivered**
2. Settings, onboarding and design-system depth
3. File browsing depth (folders, sort, multi-select, Apps tab)
4. Diagnostics depth (Doctor checks, logs, crash reports, exports)
5. `TRANSFER_ENGINE` — framing, checksums, resume, queue
6. `LAN_TRANSPORT` — UDP discovery, TCP control/data
7. `NEARBY_TRANSPORT` + `DOCTOR_NEARBY`
8. `BACKGROUND_SERVICE` — foreground service, notifications, wake locks
9. `SESSIONS_AND_BROADCAST` — sessions, pairing, broadcast
10. `MEDIA_PLAYBACK` — Media3 players, MediaSession
11. `WEBSHARE_SERVER` — embedded HTTP server (ADR-0002)
12. `WEBSHARE_CLIENT` — TypeScript browser client embedded in assets
