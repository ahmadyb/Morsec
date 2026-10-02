# Morsecode

Local file sharing for Android: pick files, see nearby devices, send over **LAN or Nearby**, and
watch real progress with resume, verification and broadcast — plus an embedded **WebShare**
server so a browser on the same network can pull or push files without installing anything.

The product contract lives in [`doc/morsecode_master_build_prompt.md`](doc/morsecode_master_build_prompt.md).
The approved visual and behavioural reference is
[`doc/morsecode_material3_mockup.html`](doc/morsecode_material3_mockup.html) — the mobile app
portion only (the mockup's desktop top bar, screen registry, bezel, gallery mode and notch are
simulator chrome and are deliberately not reproduced).

## Stack

Kotlin, Jetpack Compose + Material 3, Coroutines/Flow, Hilt, Room, DataStore, Media3,
foreground services, LAN + Nearby transports, embedded HTTP server, TypeScript WebShare client.
`minSdk 23` (Android 6.0) is a product requirement, not a fallback; `compileSdk`/`targetSdk` are
the latest stable (36) and the toolchain is pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Modules

| Module | Kind | Responsibility |
| --- | --- | --- |
| `app` | Android app | Single activity, Compose navigation graph, Hilt root, permissions, services, screens |
| `core-model` | JVM | Domain types, transfer state machine, feature-readiness gate, formatters |
| `core-design` | Android lib | Design tokens transcribed from the reference, theme, icons, shared components |
| `core-data` | Android lib | Room database, DataStore settings, redacted log store, crash records, repositories |
| `core-storage` | Android lib | MediaStore/SAF/installed-app readers, permission matrix, thumbnails |
| `core-transfer` | JVM | Protocol framing, checksums, resume, queue scheduler, snapshot contracts — the pure engine delivered by milestone 3; wiring it to a transport is milestone 5 |
| `transport-lan` | Android lib | Wi‑Fi multicast discovery + socket transport (milestone 6) |
| `transport-nearby` | Android lib | Nearby Connections transport (milestone 7) |
| `webshare-server` | JVM | Embedded HTTP server + browser API (milestone 11) |
| `media` | Android lib | Media3 playback surfaces (milestone 10) |

Every module boundary exists from the first commit so later milestones add code, not structure.

## Verifying without a JVM

The Android toolchain cannot run everywhere this repo is checked out, so the invariants that do
not need a compiler are enforced by Node scripts (no dependencies, Node 18+):

```bash
node tools/gen/icons.mjs --check        # 61 generated vectors still match the reference artwork
node tools/verify/token-parity.mjs      # colours, color-mix formulas, metrics vs the reference CSS
node tools/verify/refs.mjs              # every token/resource/route reference resolves; hygiene gate
node tools/verify/transfer-limits.mjs   # documented protocol limits vs ProtocolLimits.kt; core-transfer imports
```

All four exit non-zero on drift. See [`doc/fidelity-notes.md`](doc/fidelity-notes.md) for what
each one compares and for the fidelity decisions behind the design system, and
[`doc/transfer-protocol.md`](doc/transfer-protocol.md) §2 for the limits the fourth one
polices.

## Building

Requires JDK 17, Android SDK Platform 36 and Gradle 8.13. The wrapper JAR is a binary artifact
and is not committed; regenerate it once, then use `./gradlew` as normal:

```bash
gradle wrapper --gradle-version 8.13     # creates gradlew, gradlew.bat and gradle-wrapper.jar
./gradlew checkMilestoneHygiene          # rejects TODO/stub markers in delivered sources
./gradlew test                           # unit tests (core-model, core-design, core-data, core-transfer)
./gradlew :app:assembleDebug             # debug APK -> app/build/outputs/apk/debug/
./gradlew :app:connectedDebugAndroidTest # instrumentation tests on a device or emulator
```

`local.properties` (SDK path) and `keystore.properties` (release signing) are git-ignored;
see [`doc/release.md`](doc/release.md).

## Milestones

| # | Scope | Status |
| --- | --- | --- |
| 1 | Structure, tokens, navigation, DI, persistence, storage readers, static screens, platform doctor | **delivered** |
| 2–4 | Depth inside the milestone‑1 areas: full Files browsing/selection and the per-file action matrix, History search and filters, Settings completeness, Logs/Crashes export | **delivered** as milestone 2 — viewer, music, video, duplex and broadcast screens plus the parity/navigation/responsive/accessibility audit; see [`doc/qa/milestone-2-screen-parity.md`](doc/qa/milestone-2-screen-parity.md) |
| 5 | Transfer engine — sessions, chunking, hashing, resume, verification (`TRANSFER_ENGINE`) | next |
| 6 | LAN transport — multicast discovery, TCP control/data, foreground service (`LAN_TRANSPORT`) | planned |
| 7 | Nearby Connections transport + the doctor's Play services checks (`NEARBY_TRANSPORT`, `DOCTOR_NEARBY`) | planned |
| 8 | Background service, notification controls, process-death recovery (`BACKGROUND_SERVICE`) | planned |
| 9 | Duplex sessions and isolated 1→N broadcast (`SESSIONS_AND_BROADCAST`) | planned |
| 10 | Image viewer, Media3 music and video playback (`MEDIA_PLAYBACK`) | planned |
| 11 | Embedded WebShare HTTP server + local JSON API (`WEBSHARE_SERVER`) | planned |
| 12 | TypeScript WebShare client (`WEBSHARE_CLIENT`) | planned |

The area names in brackets are the `FeatureArea` entries in `core-model`; `CURRENT_MILESTONE` is
bumped only when a milestone is actually delivered, and `tools/verify/token-parity.mjs` fails if
the final milestone is reached while anything is still gated.

Nothing is faked ahead of its milestone: `core-model/FeatureReadiness.kt` records which feature
areas are live, and the UI routes gated actions through an honest "not in this build" dialog
rather than a placeholder control.
