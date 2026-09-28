# Release and signing

Release signing is configured from `keystore.properties` at the repository root. That file is
git-ignored — **no key material or password is ever committed**, and the build degrades to an
unsigned artifact when the file is absent rather than failing.

## 1. One-time setup

Create `keystore.properties`:

```properties
storeFile=/absolute/path/to/morsecode-release.jks
storePassword=…
keyAlias=morsecode
keyPassword=…
```

`app/build.gradle.kts` reads those four keys into the `release` signing config and applies it to
the `release` build type only when the file exists.

## 2. Producing artifacts

```bash
./gradlew :app:assembleRelease   # APK  -> app/build/outputs/apk/release/
./gradlew :app:bundleRelease     # AAB  -> app/build/outputs/bundle/release/
```

The release build type enables R8 (`isMinifyEnabled`) and resource shrinking, with rules in
`app/proguard-rules.pro`. Dependency metadata is excluded from the APK and kept in the bundle.

## 3. Store requirements tracked by the build

* `targetSdk = 36` — Google Play requires apps to target API 36 from 31 August 2026.
* `minSdk = 23` — a product requirement. `lint` runs with `abortOnError = true` and keeps
  `NewApi` enabled, so an unguarded API above 23 fails the build instead of crashing a device.
* `versionCode` / `versionName` live in `app/build.gradle.kts` (`1` / `1.0.0`); bump `versionCode`
  for every uploaded artifact.
* Debug builds carry `applicationIdSuffix = ".debug"` and `-debug` in the version name, so a debug
  build installs alongside a release build.

## 4. What is not releasable yet

`core-model/FeatureReadiness.kt` is the honest status board. At milestone 1 the transfer engine,
both transports, the background service, sessions/broadcast, media playback and the WebShare
server and client are still gated; the UI says so instead of simulating them. A store release
requires `CURRENT_MILESTONE` to reach `FINAL_MILESTONE` with an empty `gated` list, which
`tools/verify/token-parity.mjs` enforces.

## 5. Backup and data-extraction rules

`app/src/main/res/xml/backup_rules.xml` (API ≤ 30) and `data_extraction_rules.xml` (API 31+, both
`cloud-backup` and `device-transfer`) carry the same policy:

* **Included** — `datastore/` (preferences: theme, accent, sounds, duplicate policy, onboarding
  completion). These are worth restoring and contain no record of what was shared.
* **Excluded** — the whole Room database (`morsecode.db`, `-wal`, `-shm`), because it holds
  transfer history, logs and crash reports: a private record of file names and device names. Also
  excluded: `incoming/` (partial transfers), `exports/` (text exports the user created on purpose)
  and the cache.

Review both files when a new persisted data type is added; the default for anything that records
user activity is *exclude*.
