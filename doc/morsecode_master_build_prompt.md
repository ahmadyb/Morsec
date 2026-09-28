# MORSECODE — MASTER BUILD PROMPT

## Role and mission

You are the principal Android engineer, frontend engineer, networking engineer, product designer, QA engineer, and release engineer responsible for building **Morsecode**, a production-quality local file-sharing application.

Build the complete application from the supplied interactive UI reference:

- **Authoritative UI reference:** `morsecode_material3_mockup.html`
- **Mockup version:** Material 3 interactive mockup v2.4
- **Product name:** Morsecode
- **Android compatibility:** Android 6.0 / API 23 through the latest stable Android version
- **Primary platforms:** Android phones and modern desktop/mobile web browsers on the same local network

The HTML mockup is not disposable inspiration. It is the visual and behavioral source of truth. Inspect its CSS tokens, screen registry, state, labels, actions, themes, spacing, shapes, navigation, progress behavior, file grids, media controls, transfer states, and responsive WebShare pages before implementing anything.

Do not build a generic file-sharing application. Build **this exact product**.

---

# 1. Non-negotiable result

Deliver a fully working Android application and its embedded WebShare client. The final product must:

1. Closely match the approved mockup in dark and light themes.
2. Share files from Android to Android.
3. Share files bidirectionally between Android and a PC browser.
4. Support phone-to-phone, phone-to-multiple-phones, and bidirectional sessions.
5. Discover peers over **LAN or Nearby**.
6. Continue active transfers safely when the app is backgrounded or the screen turns off.
7. Resume interrupted transfers instead of restarting them unnecessarily.
8. Browse photos, videos, music, documents, folders, and eligible applications.
9. Play video and music with functional seek and volume controls.
10. Display images in a swipeable image viewer.
11. Run on API 23 without crashes or unsupported-API failures.
12. Follow modern storage, notification, foreground-service, and security requirements on current Android versions.
13. Contain no fake buttons, placeholder interactions, hard-coded progress simulation, TODO screens, or silently nonfunctional controls.

A feature is not complete merely because its screen exists. Every visible action must perform its stated function or be removed.

---

# 2. Recommended implementation stack

Use a native Android architecture. This is the preferred stack because reliable background transfer, local networking, storage access, notifications, media playback, and API 23 compatibility require direct Android platform integration.

## 2.1 Android application

- Kotlin
- Gradle Kotlin DSL
- `minSdk = 23`
- Target and compile against the latest stable Android SDK available in the build environment
- Jetpack Compose with Material 3 for application UI
- Compose Navigation
- Kotlin coroutines and Flow
- Hilt for dependency injection
- Room for durable history, sessions, queue state, resumable offsets, and crash-report metadata
- DataStore for settings and lightweight preferences
- AndroidX Media3 for audio and video playback
- WorkManager for deferred cleanup/indexing work
- A foreground service for active discovery, WebShare hosting, and transfer sessions
- `DocumentFile`, Storage Access Framework, MediaStore, and version-aware storage adapters
- Google Play services Nearby Connections as the Nearby transport, isolated behind a transport interface

Use the latest stable dependency versions that are mutually compatible and still support API 23. Do not use alpha or snapshot dependencies unless there is no stable alternative and the decision is documented.

## 2.2 WebShare

- TypeScript
- Semantic HTML
- Local CSS using the exact Morsecode design tokens
- A small component architecture without a runtime-heavy framework unless the existing repository already standardizes one
- The production web bundle must be embedded in Android assets and served locally by the phone
- No CDN resources, external fonts, analytics, trackers, or internet dependencies
- Use relative URLs only

## 2.3 Local server

Implement or select a lightweight Android-compatible HTTP server after proving API 23 support. It must support:

- Concurrent clients
- Streaming responses without loading whole files into memory
- HTTP byte ranges and `206 Partial Content`
- `HEAD`
- Multipart or streamed uploads
- Resumable uploads
- Download cancellation
- Folder ZIP streaming
- Correct MIME types
- Content length where known
- Safe path resolution
- Session authentication
- Graceful shutdown

Do not select a server library solely because it works on desktop JVM. Prove it works on Android API 23 and does not depend on unsupported JVM APIs.

---

# 3. Repository and module structure

Use clear module boundaries. A suitable layout is:

```text
/
├── app/                         Android application shell
├── core-model/                  Shared domain models and state enums
├── core-design/                 Compose tokens and reusable components
├── core-storage/                MediaStore, SAF, legacy storage adapters
├── core-transfer/               Queue, framing, checksum, resume engine
├── transport-lan/               LAN discovery and socket transport
├── transport-nearby/            Nearby Connections implementation
├── webshare-server/             Embedded local HTTP server and API
├── webshare-ui/                 TypeScript/CSS browser application
├── media/                       Playback and media metadata helpers
├── benchmark/                   Optional performance/startup benchmarks
├── docs/                        Protocol, architecture, security, release notes
└── morsecode_material3_mockup.html
```

If a smaller module structure is more maintainable, explain the deviation. Do not put networking, storage, UI, and service code into one monolithic activity.

---

# 4. UI source-of-truth rules

## 4.1 General fidelity

Reproduce the mobile application portion of the mockup, not the outer simulator workbench. The simulator’s desktop top bar, left screen registry, phone bezel, gallery mode, and mock device notch are documentation tools and must not appear inside the Android app.

Match the application UI in:

- Color roles
- Typography hierarchy
- Corner radii
- Elevation and tonal surfaces
- Insets
- Component spacing
- Icon sizes
- Touch-target sizes
- Selected states
- Error, warning, success, queued, paused, receiving, and sending states
- Bottom navigation
- Sticky headers and tabs
- Dialogs and bottom sheets
- File grids
- Transfer progress rows
- WebShare navigation and responsive behavior

Use Roboto/system typography on Android. Do not fetch fonts from the network.

## 4.2 Responsive mobile behavior

The design reference is approximately a 360 × 740 dp phone. It must also adapt cleanly to:

- 320 dp-wide Android 6 phones
- 360–412 dp common phones
- Large phones
- Foldable panes
- Tablets
- Landscape orientation
- Display-size and font-size accessibility settings

Do not hard-code a single physical pixel size. Preserve the mockup’s visual proportions using dp, sp, window size classes, and adaptive constraints.

## 4.3 Mobile Files interface

The Files destination contains these sticky category tabs:

- Photos
- Videos
- Music
- Apps
- Files

Requirements:

- Tabs stay visible while content scrolls.
- All five tabs fit the phone width.
- No native horizontal scrollbar is visible.
- The selected tab uses bold accent text, a subtle selected tint, and a clear straight underline.
- Horizontal swipe changes to the previous or next category.
- Vertical gestures continue to scroll normally.
- Photo and video grids display three tiles per row at the reference phone width.
- Selection controls must remain independently tappable.
- Sorting must be real and preserve scroll position where practical.

The Files category screen must not show the redundant `/storage/emulated/0` address bar above Categories.

The internal folder browser may show a breadcrumb/path bar, but it must:

- Remain below the sticky header
- Never be hidden behind the header
- Remain visible while rows scroll
- Not contain the removed upward-arrow control
- Support breadcrumb navigation, copying the path, and editing/pasting a path only where safe and meaningful

## 4.4 Scrollbars

Mobile content scrollbars must be unobtrusive hairlines, not large pills. They should be approximately 2 dp where explicitly rendered, with a straight accent-toned thumb and transparent track. Native Android scroll indicators may be used if they meet this appearance.

Desktop/WebShare scrollbars may be slightly wider but must remain subtle.

## 4.5 Image viewer

Implement:

- True-black immersive background
- Swipe left/right between images
- Current item count and metadata
- Share
- Edit through a compatible external editor intent where available
- Delete with confirmation and recoverable behavior where Android storage APIs allow it
- File information
- Send this image

Do not add the removed unexplained overflow button.

## 4.6 Music player

Implement:

- Artwork/placeholder presentation matching the mockup
- Track title, artist, and album
- Play/pause
- Previous/next
- Seek by tapping or dragging
- Elapsed and remaining time
- Shuffle and repeat modes
- Volume and mute behavior where application-level volume is appropriate
- Queue/up-next list
- Background playback with MediaSession and notification controls
- Audio focus and interruption handling

## 4.7 Video player

Implement:

- Play/pause
- Seek by tapping or dragging
- ±10-second controls
- Elapsed and total time
- Volume and mute controls
- Fullscreen/orientation handling
- Subtitle control when subtitle tracks exist
- HTTP range playback for WebShare
- Playback-state restoration

Do not add the removed unexplained overflow button.

---

# 5. Navigation and screen behavior

Implement all approved mobile states represented by the mockup:

## Getting started

- Welcome
- Permission explanation
- WebShare explanation
- Ready/completion
- Help and FAQ

Request permissions contextually. Never request every permission immediately without explanation.

## Connect

- Connect hub
- Live discovery state
- Single-device discovery
- Multi-device selection for broadcast
- Explicit inbound peer consent
- Explicit browser-session consent
- WebShare control
- Connection Doctor

Transport copy must say **LAN or Nearby**.

Do not include Manual IP entry or QR pairing unless the approved mockup is changed later.

## Transfers

Use two symmetrical duplex views:

1. **Sending + receiving**
2. **Receiving + sending back**

A connected session is bidirectional. Either side can add files without pairing again.

### Per-file action matrix

- Sending or receiving: Pause and Cancel
- Paused: Resume and Cancel
- Queued: Cancel
- Failed: Retry only
- Completed: No pause, resume, cancel, or retry icon

Retry is per file. Do not add a global “Retry failed” button.

The single global pause/resume-all action belongs in the bottom transfer action bar. Do not duplicate it above and below the file list.

The small action aligned with a section heading is Clear completed, represented by the approved trash/clear icon. It must remove only completed rows in that section.

The bottom transfer actions are:

- Add files
- Pause all / Resume all
- Background
- End

Meanings:

- **Add files:** opens the file picker and app media browser.
- **Pause all / Resume all:** changes all eligible active items in the relevant session.
- **Background:** leaves the full transfer screen while the foreground service and notification continue the session.
- **End:** asks for confirmation, closes the session, and handles queued/incomplete files according to the user’s choice.

Do not restore the removed separate Queue screen. Queue management is integrated into the duplex transfer screen.

## Broadcast

- Select at least two discovered phones.
- Create one independent delivery session per receiver.
- Display per-recipient progress.
- A slow, paused, failed, or disconnected receiver must not block other receivers.
- Show total bytes as batch size multiplied by recipient count.
- Allow per-recipient retry/reconnect where possible.
- Verify every delivered file.

## Library and system

- Received and sent history
- Settings
- Logs
- Crash reports
- Connection Doctor
- Help

Crash reports must be locally viewable, exportable as text, and clearable like logs. Never upload reports automatically.

---

# 6. Transfer engine

## 6.1 Core invariants

The engine must obey these rules:

1. No single stuck file blocks the remaining queue.
2. Paused transfers preserve their confirmed offset.
3. Retrying a failed file does not restart unrelated files.
4. Cancellation is explicit and reflected on both peers.
5. Completed files are immutable in the active session UI except for clearing their rows.
6. Every file is written to a temporary partial target first.
7. A file is moved/committed to its final target only after verification.
8. Interrupted transfers survive process recreation when possible.
9. Broadcast recipients are isolated from one another.
10. UI progress derives from the real transfer engine, never from timers or fake data.

## 6.2 Transfer protocol

Document the protocol in `docs/transfer-protocol.md`.

Use a versioned handshake containing at minimum:

- Protocol version
- App version
- Device/session identifier
- Display name
- Supported transports
- Supported features
- Maximum chunk size
- Resume capability
- Encryption capability

Each file descriptor should include:

- Stable transfer ID
- Batch ID
- Relative display name
- MIME type
- Byte length
- Last-modified time where available
- Folder/archive flag
- Checksum metadata

Use framed binary chunks. Include sequence/offset information. Use CRC32 or an equivalent inexpensive check per chunk and SHA-256 or an equivalently strong final integrity check for the full file.

The receiver acknowledges confirmed offsets. Resume from the last confirmed valid offset, not from an optimistic sender offset.

Apply backpressure. Do not read entire files into memory. Keep memory bounded on old Android devices.

## 6.3 State machine

Use explicit states such as:

- QUEUED
- NEGOTIATING
- SENDING
- RECEIVING
- PAUSED_LOCAL
- PAUSED_REMOTE
- VERIFYING
- COMPLETED
- FAILED_RETRYABLE
- FAILED_FINAL
- CANCELLED
- SKIPPED

State changes must be persisted and exposed as Flow to the UI.

## 6.4 LAN transport

Implement local-network discovery and transfer without internet access.

Suggested fixed defaults, matching the mockup unless conflicts require configurable fallback:

- WebShare HTTP: `33455`
- Peer control/data: `33456`
- Discovery beacon: UDP `33457`
- Discovery interval: approximately 1200 ms while actively discovering

Requirements:

- Handle multicast lock acquisition/release correctly.
- Handle network changes.
- Re-advertise after reconnecting.
- Do not bind only to loopback.
- Handle IPv4 reliably and support IPv6 where practical.
- Surface router/client-isolation failures through Connection Doctor.

## 6.5 Nearby transport

Use Nearby Connections when LAN transfer is unavailable or the user chooses a discovered Nearby peer.

- Hide the implementation behind the same transport interface as LAN.
- Keep queue and progress semantics identical.
- Handle required runtime permissions by Android version.
- Explain when Google Play services are missing or outdated.
- Do not silently fall back to an insecure or unsupported path.

## 6.6 Background behavior

During active discovery, transfer, or WebShare hosting:

- Use a correctly typed foreground service.
- Show an ongoing notification.
- Expose pause/resume/end actions where platform rules permit.
- Acquire Wi-Fi and wake locks only when necessary and release them deterministically.
- Recover service/session state after process recreation.
- Explain battery-optimization exemption rather than forcing it.
- Respect Android 12+ background-start restrictions and Android 13+ notification permission behavior.

---

# 7. Storage and file access

Create a version-aware storage layer.

## Android 6–9

- Request legacy read/write permissions only when needed.
- Handle denial and “don’t ask again.”
- Use direct paths only where permitted.

## Android 10+

- Use MediaStore for shared photos, videos, and audio.
- Use Storage Access Framework for arbitrary folders and documents.
- Persist URI grants.
- Use app-specific temporary storage for partial transfers.
- Use recoverable delete/write flows where required.

## Folder transfer

A selected folder is sent as one logical item and streamed as a ZIP without first creating an entire duplicate archive when possible.

- Preserve relative paths.
- Reject path traversal.
- Define symlink behavior.
- Avoid ZIP bombs on receive.
- Show realistic aggregate progress where source sizes are known.

## Duplicate policy

Support the settings shown by the mockup:

- Rename duplicates
- Overwrite
- Skip existing
- Ask every time

Apply the chosen policy consistently to peer transfers, browser uploads, and folder extraction.

## App/APK sharing

Implement APK sharing only within Android platform and distribution-policy limits.

- List eligible user-installed applications.
- Extract/share the base APK and clearly handle split APK packages.
- For split packages, export a documented bundle format or explain that multiple APKs are required.
- Do not claim a split application is installable as a single base APK when it is not.
- Use package visibility declarations responsibly.
- If full installed-app visibility requires restricted permissions, provide separate Play-compliant and sideload/enterprise behavior rather than hiding the policy issue.

---

# 8. WebShare

## 8.1 Session lifecycle

WebShare is a local server on the phone.

- It stays active until the user stops it or the service must terminate under a clearly surfaced condition.
- It must not silently time out while the user is actively using it.
- A new browser requires explicit acceptance on the phone.
- Accepted sessions receive a scoped, random token.
- The phone lists active browser sessions.
- The user can revoke a session.

The displayed address must reflect the reachable local interface and actual selected port.

## 8.2 WebShare pages

Build the approved responsive pages:

- Dashboard
- Photos
- Videos
- Music
- Documents
- Apps
- Files
- Video player

Do not reproduce removed duplicate UI:

- No “All videos” pseudo-folder
- No duplicate video table beneath the video grid

The Photos and Videos folder panels remain visible while their content scrolls.

The Files Quick Access panel must remain pinned and must not travel with the file rows. It contains:

- Internal storage
- Download
- DCIM
- Pictures
- Movies
- Music
- Documents
- WhatsApp where accessible
- Telegram where accessible
- Morsecode
- SD card where available
- Storage usage summary

The path/breadcrumb bar remains visible while WebShare file rows scroll.

On narrow browsers, transform persistent folder rails into compact horizontal folder selectors without losing access to them.

## 8.3 Browser capabilities

Implement real:

- Browsing
- Folder navigation
- Photo lightbox navigation
- File selection
- Direct download
- Multi-file download
- Download as ZIP
- Upload through browse and drag/drop
- Upload into the current folder
- Delete with confirmation
- Music playback
- Video playback using range requests
- Seek
- Volume
- Mute
- Fullscreen
- Correct cancellation and cleanup

## 8.4 HTTP API

Document endpoints in `docs/webshare-api.md`.

Use versioned local endpoints, for example `/api/v1/...`. Include:

- Session status
- Storage summary
- Folder listing
- Media categories
- Metadata
- Stream/download
- ZIP stream
- Upload initialization
- Upload chunk/resume
- Upload completion
- Delete
- Active transfer state

Never expose raw unrestricted filesystem paths to browser input. Map approved roots and SAF grants to opaque IDs.

## 8.5 WebShare security

Even on a local network:

- Require phone approval for new sessions.
- Use high-entropy bearer/session tokens.
- Validate `Origin` and host expectations.
- Protect state-changing requests from CSRF.
- Sanitize filenames.
- Block `..`, encoded traversal, absolute paths, and symlink escape.
- Rate-limit authentication and destructive operations.
- Expire revoked sessions immediately.
- Do not serve unrelated app-private files.
- Do not expose logs or crash reports through WebShare unless the phone user explicitly exports them.

---

# 9. Media indexing and metadata

Build a repository that merges:

- MediaStore media
- SAF-granted folders
- Legacy storage on supported old devices
- Received files

Load thumbnails asynchronously with bounded caches. Do not decode full-resolution photos into grid cells.

Read duration, dimensions, MIME type, size, modification time, album, artist, and title where available. Handle missing or malformed metadata without crashing.

Changes from transfers, uploads, deletes, and external media updates should appear without requiring an app restart.

---

# 10. Settings, logs, diagnostics, and privacy

Implement settings represented in the mockup:

- Theme
- Accent color
- Follow system
- Sounds
- Notifications
- Duplicate/conflict policy
- Broadcast peer limit
- Storage access
- Battery optimization
- Logs
- Crash reports
- Connection Doctor
- Replay onboarding
- Help and FAQ
- About/version

## Logs

- Timestamped levels such as INFO, WARN, and ERROR
- Filter errors
- Clear
- Export as plain text
- Include app version and useful device/runtime metadata
- Redact session secrets, tokens, and sensitive full paths

## Crash reports

- Store locally
- View in the app
- Export as text
- Clear
- Never upload automatically
- Redact secrets and private file content

Install a safe uncaught-exception recorder without creating crash loops or replacing platform behavior incorrectly.

## Connection Doctor

Check and explain:

- Wi-Fi/network state
- Peer subnet visibility
- Multicast capability/lock
- Nearby/Play services readiness
- Runtime permissions
- Notification permission
- Storage grants
- Battery optimization
- Foreground-service readiness
- Port conflicts
- Router/client isolation possibility

Every failed check must provide a useful corrective action.

## Privacy

Morsecode has:

- No account
- No cloud dependency
- No advertisements
- No analytics or trackers
- No automatic crash upload

Do not add telemetry SDKs.

---

# 11. Accessibility and localization readiness

- Minimum 48 × 48 dp touch targets for primary interactive controls
- Content descriptions for icons
- Logical TalkBack traversal
- Announce transfer state/progress changes without excessive chatter
- Do not encode status only by color
- Support font scaling without clipped critical actions
- Respect reduced-motion settings
- Respect system contrast where possible while retaining the approved design
- Put user-facing strings in resources
- Use plural resources
- Avoid concatenated English fragments that cannot be translated
- Use locale-aware dates, times, and file sizes

---

# 12. Error handling

Design explicit recovery for:

- Peer disappears
- Wi-Fi changes
- Nearby disconnects
- Receiver storage fills
- Permission revoked
- SAF grant revoked
- Source file deleted during transfer
- Checksum mismatch
- Port conflict
- Browser closes mid-upload
- Duplicate destination
- Unsupported media codec
- Partial ZIP transfer
- Process killed
- Device reboots

Errors must name the affected item and whether Retry is available. One failed item must not stall unrelated items.

---

# 13. Performance targets

Optimize for older API 23 hardware.

- Avoid loading full folders or whole media libraries into memory.
- Paginate or virtualize large lists.
- Use thumbnail caches with strict bounds.
- Stream all file data.
- Reuse buffers.
- Limit parallel transfers based on device and transport conditions.
- Avoid recomposition of entire screens for frequent progress ticks.
- Throttle persisted progress writes while preserving resumability.
- Keep UI interactions smooth while transferring.
- Ensure browser range seeking responds promptly.

Measure rather than guessing. Add benchmark or profiling notes for large libraries and multi-recipient broadcast.

---

# 14. Testing requirements

## Unit tests

Test:

- Transfer state machine
- Pause/resume offsets
- Retry behavior
- Clear-completed behavior
- Duplicate policy
- Filename/path sanitization
- Range parsing
- Content-range generation
- Folder ZIP paths
- Checksums
- Queue persistence
- Broadcast isolation

## Android instrumentation/UI tests

Cover at minimum:

- Onboarding and permission denial/recovery
- Sticky file tabs
- Swipe between file categories
- Three-column media grid
- Image-viewer swipe
- Music seek/play/pause
- Video seek/volume/mute
- Sending + receiving controls
- Receiving + sending-back controls
- Failed item shows Retry only
- Completed item shows no transfer-control icons
- Background transfer notification
- Crash-report view/export
- Theme switching

Run key tests on API 23, one mid-level API, and the latest available API.

## Integration tests

Test:

- Android-to-Android LAN transfer
- Android-to-Android Nearby transfer
- Resume after disconnect
- Bidirectional transfer
- Broadcast with one slow receiver
- Broadcast with one failed receiver
- Browser approval and revocation
- Browser upload/download
- HTTP range requests
- Large-file streaming
- Folder ZIP streaming
- Storage-full failure

## Web tests

Test desktop and responsive layouts, persistent folder/Quick Access panels, breadcrumb persistence, selection, upload, download, lightbox, and media controls.

## Visual regression

Capture reference screenshots at the mockup’s target dimensions for every registered state. Compare implementation screenshots for:

- Dark theme
- Light theme
- Sunflower accent
- Selected alternative accents
- API 23 phone dimensions
- 360 × 740 dp reference
- A 412 dp phone
- Tablet
- Desktop WebShare

Treat noticeable spacing, typography, component-shape, color, or sticky-position differences as defects.

---

# 15. Build and release requirements

Deliver:

- Debug APK
- Release-ready Android App Bundle configuration
- Reproducible Gradle build
- Release signing instructions without committing secrets
- ProGuard/R8 rules
- Network security configuration
- Backup/data extraction rules appropriate for sensitive session data
- Versioning strategy
- Open-source license notices
- README with setup, build, run, and test commands
- Architecture documentation
- Transfer protocol documentation
- WebShare API documentation
- Security and privacy documentation
- Known platform limitations

The project must build from a clean checkout using documented commands.

Do not commit:

- Signing keys
- Passwords
- Machine-specific absolute paths
- Tokens
- Generated dependency caches
- IDE-local configuration that should be ignored

---

# 16. Implementation sequence

Follow this order so visual work is connected to real behavior:

1. Inspect and document the complete mockup screen/state registry.
2. Create design tokens and reusable Compose/Web components.
3. Implement navigation and static screen parity.
4. Implement storage repositories and media indexing.
5. Implement persistent transfer models and state machine.
6. Implement LAN discovery and transfer.
7. Implement Nearby transport through the same interface.
8. Implement foreground service, notification, and process recovery.
9. Implement duplex sessions and broadcast isolation.
10. Implement image, music, and video experiences.
11. Implement WebShare server and browser API.
12. Implement the exact WebShare UI.
13. Implement logs, crash reports, Connection Doctor, and export.
14. Add unit, integration, UI, web, and visual-regression tests.
15. Profile API 23 performance and large transfers.
16. Produce release artifacts and documentation.

At the end of every phase:

- Build the project.
- Run relevant tests.
- Fix failures before continuing.
- Compare the UI to the reference.
- Record decisions and remaining risks.

---

# 17. Definition of done

Morsecode is complete only when all of the following are true:

- The app installs and launches on Android 6/API 23.
- It also follows current Android platform requirements.
- Every approved mockup screen has a real implementation.
- Dark/light themes and accents work.
- Mobile category tabs remain visible and swipe correctly.
- Photo and video grids use three columns at the reference width.
- LAN and Nearby discovery work.
- Android-to-Android transfer works in both directions.
- Broadcast recipients progress independently.
- Pause, resume, cancel, retry, verification, and resume-after-interruption work with real files.
- Completed transfer rows show no inappropriate action icons.
- Active transfers continue through Background mode using a foreground service.
- Image viewing, music playback, and video playback are functional.
- WebShare requires phone approval and supports real browse/upload/download/playback.
- HTTP range seeking works.
- Quick Access and folder panels remain pinned as specified.
- Logs and crash reports can be viewed, exported, and cleared.
- No analytics, cloud dependency, ads, or automatic crash uploads exist.
- Tests pass.
- The clean build is reproducible.
- There are no TODOs, fake data engines, placeholder actions, or known critical crashes.

---

# 18. Instructions for the implementing agent

Before writing code, return:

1. A concise analysis of the mockup and its screen registry.
2. The proposed repository/module structure.
3. The selected Android-compatible WebShare server approach and proof strategy for API 23.
4. The transfer protocol/state-machine plan.
5. A permission/storage compatibility matrix from API 23 through the latest Android API.
6. A milestone plan with test gates.

Then begin implementation without replacing the approved UX with default templates.

When ambiguity exists:

- First inspect the mockup.
- Preserve the approved visible behavior.
- Prefer reliable, secure local behavior.
- Document the decision.
- Ask a targeted question only if proceeding would create a meaningful product conflict.

Do not claim completion based on screenshots alone. Demonstrate builds, tests, real transfer flows, and browser behavior.
