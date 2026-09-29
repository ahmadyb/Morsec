# Morsecode project handoff

You are continuing an existing Android project. Do not restart, redesign,
replace, or regenerate the repository.

## Repository

GitHub repository:
https://github.com/ahmadyb/Morsec

Working branch:
arena/01a0e98a-morsec

Last confirmed pushed commit:
dbe0253d5c45b139b08876187e091e763a18a45e

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
- 69 written but not yet executed JVM unit tests

The previous environment could not compile Android code because it had
no JDK, Gradle, or Android SDK and could not download them.

Therefore Milestone 1 is NOT accepted as complete yet.

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

## Exact starting point

Your first task is CI validation of Milestone 1.

Do not begin Milestone 2.

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
