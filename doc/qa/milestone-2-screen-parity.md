# Milestone 2 — screen parity

Every mobile state in the mockup's screen registry (`doc/morsecode_material3_mockup.html`,
§7 SCREEN REGISTRY) against the Compose graph that ships in the app. The registry's
WebShare-browser group (`wHome`…`wPlayer`, `frame:'win'`) is a browser application, not a
phone screen, and belongs to milestones 11–12; it is out of scope for this table.

How to read the status column:

* **exact** — the state renders in the app, is reachable, and is asserted by a test.
* **acceptable adaptation** — the state renders with a documented, deliberate difference
  (the decision and its reason live in `doc/fidelity-notes.md`).
* **gated** — the state's *production behaviour* is created by a later milestone's engine;
  the app routes the control that would reach it through `FeatureGate`, which names that
  milestone, instead of pretending the state exists.
* **missing** — nothing here. A missing screen would be a defect of this audit.

Verification backbone: `RouteRegistrationTest` (8 tests) holds every declared active route
to a registration, every navigation call to a registered destination, every registered
destination to a path from the start, and the whole catalogue free of the removed queue /
manual-address / pairing shapes. The per-flow route tests (`BroadcastRouteTest`,
`TransferRouteTest`, `ViewerRouteTest`, `MusicPlayerRouteTest`, `VideoPlayerRouteTest`)
hold each argumented route's contract.

## Getting started

| Mockup ID | Base fn | Screen | Route | Implementation | Reachability | Visual state | Tests | Gate | Known difference | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `onb1` | `onboarding` | Welcome | `onboarding` | `ui/onboarding/OnboardingScreen.kt` | First-run start destination; Settings → Replay onboarding | Card 1 of 4, progress "step 1 of 4" | `LibraryAndSystemScreensTest` (walks all four cards to finished) | — | The registry lists four ids because a page cannot turn; the app is one route with four cards — the same four states | exact |
| `onb2` | `onboarding` | Permissions | `onboarding` | same | Card 2's Continue | Explains storage before requesting | same (declines via "Not now", the path that must advance) | — | Card 2's primary issues the real contextual storage request — the only first-run permission prompt (approved) | exact |
| `onb3` | `onboarding` | WebShare pitch | `onboarding` | same | Card 3 | Browser explanation | same | WebShare itself → `WEBSHARE_SERVER` (M11) | none | exact |
| `onb4` | `onboarding` | Ready | `onboarding` | same | Card 4 → Open Morsecode finishes | Completion card | same | — | none | exact |
| `help` | `help` | Help & FAQ | `help` | `ui/help/HelpScreen.kt` | Settings → Help; Connect → Help | Accordion FAQ + Doctor shortcut | `LibraryAndSystemScreensTest` (opens an answer, fires the Doctor shortcut, asserts Settings lit) | — | none | exact |

## Connect

| Mockup ID | Base fn | Screen | Route | Implementation | Reachability | Visual state | Tests | Gate | Known difference | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `connect` | `connect` | Connect hub | `connect` | `ui/connect/ConnectScreen.kt` | Bottom bar CONNECT; onboarding finish | Radar region, Send/Receive, Broadcast entry, WebShare card (real port), Recent devices | `ConnectScreenTest` (3: broadcast entry, send/receive destinations, help action); `RouteRegistrationTest` | Send/Receive → `TRANSFER_ENGINE` (M5); WebShare card → `WEBSHARE_SERVER` (M11) | Radar sweep is static while discovery is gated; the counts and recents are real state (`ConnectScreen` KDoc) | exact |
| `discover` | `discover` | Discovery · single | — | `ConnectScreen`'s discovery region + gate | Connect hub | Idle / blocked states with the honest "Discovery is off" copy and a gated Start discovery | `ConnectScreenTest` (destinations); gate dialogs asserted across screens | Live single-peer discovery → `LAN_TRANSPORT` (M6) / `NEARBY_TRANSPORT` (M7) — discovery without a transport cannot exist, and no simulated scan is shown | The gated dialog names the milestone instead of animating fake discovery | gated |
| `discoverM` | `discover` | Discovery · multi-select (broadcast picker) | `broadcast/pick` | `ui/broadcast/BroadcastPickScreen.kt` | Connect → "Broadcast to several phones" | Discovered-phone rows, selected/unselected, ≥2 rule with hint, queue summary | `BroadcastScreensTest` (31), `BroadcastSelectionTest` (11), `BroadcastReachabilityTest` (360 dp), `AccessibilitySemanticsTest` (bar lit), `BroadcastRouteTest` | — | One route replaces the registry's `S.multi` flag — same screen, two states; devices come from the approved M2 fixture, not a simulated scan (§4.9) | exact |
| `consentP` | `consent` | Consent · peer | — | — (dialog built with the inbound session) | Inbound peer connection (none can arrive yet) | Accept / reject sheet | gate dialogs (`TransferScreensTest`, `BroadcastScreensTest`, `MusicPlayerScreenTest`, `FolderScreenTest`) | Inbound sessions → `TRANSFER_ENGINE` (M5) + transports (M6/M7) | No inbound connection exists to consent to before the engine; rendering the dialog untriggered would be a mockup page, not a screen | gated |
| `consentB` | `consent` | Consent · browser | — | — (WebShare server session) | First browser session on `:33455` | Accept / reject sheet | — | `WEBSHARE_SERVER` (M11) | Same justification as peer consent; the Connect card already shows the real port | gated |
| `webshare` | `webshare` | WebShare control | `webshare` (reserved, `Routes.reserved`) | Connect card today; destination reserved | Connect hub card | Address + sessions + hotspot | `RouteRegistrationTest` (reserved is declared, unregistered, never navigated to) | `WEBSHARE_SERVER` (M11) | Reserved-not-registered is asserted, so the vocabulary is complete without a hollow screen | gated |
| `doctor` | `doctor` | Connection Doctor | `doctor` | `ui/doctor/DoctorScreen.kt` | Settings → Connection Doctor; Help → Open Connection Doctor | Title, refresh, check list | `LibraryAndSystemScreensTest` (renders + refresh); `RouteRegistrationTest` | Nearby checks → `DOCTOR_NEARBY` (M7) | none | exact |

## Duplex transfer

| Mockup ID | Base fn | Screen | Route | Implementation | Reachability | Visual state | Tests | Gate | Known difference | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `sending` | `sending` | Sending + receiving | `session/{layout}` (sending-first) | `ui/transfer/TransferScreens.kt` | Connect → Send | Peer card, sections, per-file rows with the action matrix, bottom bar (Add files · Pause all · Background · End) | `TransferScreensTest` (44 incl. End confirmation dialog at :654, matrix per state), `TransferViewModelTest` (15), `TransferRulesTest` (26), `TransferRouteTest` (5), `ResponsiveAuditTest` (412 dp, 360 dp, font ×1.5) | Add files → `TRANSFER_ENGINE`; Background → `BACKGROUND_SERVICE` (asserted dialogs) | One route token draws both registry views — the two ends of one session (§4.8) | exact |
| `receive` | `receive` | Receive · listening (idle) | — | — (listener with no session) | Connect → Receive before any peer exists | Idle listener | gate assertion: Connect's Receive destination test (`ConnectScreenTest`) | `TRANSFER_ENGINE` (M5) + `BACKGROUND_SERVICE` (M8) — an idle listener is a foreground service and a transport; there is nothing real for it to do yet | Gated rather than a screen full of invented listening animation | gated |
| `receiving` | `receiving` | Receiving + sending back | `session/{layout}` (receiving-first) | same destination | Connect → Receive (with session) | Same session from the other end | same suite as `sending` | same | none | exact |
| transfer-row states | — | Per-file matrix | — | `ui/transfer/TransferComponents.kt` (`TransferRowView`) | — | Active/Paused/Queued/Failed/Completed controls exactly per matrix | `TransferRulesTest` (26), `TransferScreensTest` row-matrix tests, `AccessibilitySemanticsTest` (controls name the file; completed row exposes none) | — | Completed rows carry **no** control semantics — asserted, not assumed | exact |
| end confirmation | — | End dialog | — | `DuplexTransferScreen` | Bottom bar → End | "End this session?" with the unfinished-count choice | `TransferScreensTest:654/661`, `BroadcastScreensTest:383` | — | none | exact |
| background gate | — | Background action | — | bar → `FeatureGate` | Bottom bar → Background | Gate sheet naming `BACKGROUND_SERVICE` and its milestone | `TransferScreensTest:622-643` formats the full gate body | `BACKGROUND_SERVICE` (M8) | none | gated |
| add-files gate | — | Add files action | — | bar → `FeatureGate` | Bottom bar → Add files | Gate sheet naming `TRANSFER_ENGINE` | `TransferScreensTest:618-632` | `TRANSFER_ENGINE` (M5) | none | gated |

## Broadcast (five states)

| Mockup ID | Base fn | Screen | Route | Implementation | Reachability | Visual state | Tests | Gate | Known difference | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `discoverM` (picker state) | `discover` | Recipient picker | `broadcast/pick` | `ui/broadcast/BroadcastPickScreen.kt` | Connect → Broadcast | ≥2 rule, shortfall hint, selected count, disabled start | see picker row above; `ResponsiveAuditTest` (320 dp not required — reference proof in `BroadcastReachabilityTest`) | — | §4.9 | exact |
| `bsender` | `bsender` | Broadcast sender | `broadcast/sender?chosen={chosen}` | `ui/broadcast/BroadcastSenderScreen.kt` | Picker → Connect & broadcast | Sticky header, files with nested per-recipient rows, batch summary, bar | `BroadcastScreensTest`, `BroadcastViewModelsTest` (25), `BroadcastSessionTest` (58), `BroadcastReachabilityTest`, `ResponsiveAuditTest` (320/360/landscape) | — | one row per recipient under each file; no shared percentage anywhere (§4.9) | exact |
| `breceivers` | `breceivers` | Broadcast receiver | `broadcast/receiver/{recipient}` | `ui/broadcast/BroadcastReceiverScreen.kt` | Sender → recipient row; fixtures point the route at any phone | Sender identity, FROM badge, per-file receive states, counts wash, bar | `BroadcastScreensTest`, `BroadcastViewModelsTest`, `BroadcastReachabilityTest`, `AccessibilitySemanticsTest` | — | The registry's three phone frames are three states of **one** screen — route argument + fixtures, no bezels (§4.9) | exact |
| `bcomplete` (sender) | `bcomplete` | Sender completion | `broadcast/sender/complete` | `ui/broadcast/BroadcastCompleteScreens.kt` (`BroadcastSentScreen`) | Sender → See completion | "Broadcast complete", per-recipient results, Clear heading action, three-stat wash | `BroadcastScreensTest` (incl. partial-vs-full wording), `BroadcastViewModelsTest`, `BroadcastReachabilityTest` | Clear/remove-history semantics operate on fixtures (no engine store yet) | "All N phones verified" only when earned (§4.9) | exact |
| `bcomplete` (receiver) | `bcomplete` | Receiver completion | `broadcast/receiver/{recipient}/complete` | same file (`BroadcastReceivedScreen`) | Receiver → See completion | FROM card, received rows, Open folder heading action, final summary | `BroadcastScreensTest`, `BroadcastReachabilityTest` | Open folder → `TRANSFER_ENGINE` (M5) gate per spec | Reference's average-speed line only while something moves (§4.9) | exact |

## Files and media

| Mockup ID | Base fn | Screen | Route | Implementation | Reachability | Visual state | Tests | Gate | Known difference | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `fPhotos` | `files` | Files · Photos | `files` (tab) | `ui/files/FilesScreen.kt` | Bottom bar FILES | 3-column grid, day sections, sticky tabs | `ResponsiveAuditTest` (320/360/600: tabs fit, tap turns page, selection bar), `AccessibilitySemanticsTest` (selected tab semantics, checkbox), `MorseCategoryPagerTest` (6, core-design) | — | none | exact |
| `fVideos` | `files` | Files · Videos | same | same | tab tap/swipe | Day-grouped grid / empty state | same | — | empty state asserted at 320 dp | exact |
| `fMusic` | `files` | Files · Music | same | same | tab | Track list | same | — | none | exact |
| `fApps` | `files` | Files · Apps | same | same | tab | APK list | tab presence asserted | — | none | exact |
| `fFiles` | `files` | Files · Browser | same | same | tab | Categories + SAF folders | same | — | no `/storage/emulated/0` address bar (approved removal) | exact |
| `fBrowse` | `browse` | Internal storage | `folder/{treeUri}` | `ui/folder/FolderScreen.kt` | Files → granted folder | Breadcrumb below sticky header, selectable folders | `FolderScreenTest` (18), `MorseCrumbTest` (6) | — | breadcrumb is the way up; **no** upward-arrow control (approved) | exact |
| selection states | — | Files/folder selection | — | selection bar in `FilesScreen`/`FolderScreen` | header Select / tile circles | "%d selected" bar, per-item circles | `ResponsiveAuditTest` (320: bar names its count, tabs stay), `AccessibilitySemanticsTest` (checked state), `FolderScreenTest` | gated send → `TRANSFER_ENGINE` | none | exact |
| sort sheet | — | Sort | — | `FilesScreen` (MorseModalSheet) | header Sort | "Sort by" + keys + Ascending/Descending | `ResponsiveAuditTest` (320 dp: rows and both buttons reachable) | — | Folder browser has no sort sheet in the reference's mobile cells | exact |
| `viewer` | `viewer` | Photo viewer | `viewer/{itemId}?sort={sort}` | `ui/viewer/ViewerScreen.kt` | Photos tile | True-black deck, count, share/edit/delete/info | `ViewerScreenTest` (21), `ViewerRouteTest` (7), `MediaOpenTest` (9) | none for viewing | no overflow button (approved §4.5) | exact |
| `music` | `music` | Music player | `music/{itemId}?sort={sort}` | `ui/music/MusicPlayerScreen.kt` | Music row | Now-playing + transport + queue | `MusicPlayerScreenTest` (26), `MusicPlayerRouteTest` (6), gate tests | Background/MediaSession chrome → `MEDIA_PLAYBACK` (M10) gates where real playback needs the engine | UI-state controller only in M2 (approved) | exact |
| `video` | `video` | Video player | `video/{itemId}` | `ui/video/VideoPlayerScreen.kt` | Video row | Header (back + filename + metadata), scrubber ±10s, volume | `VideoPlayerScreenTest` (18), `VideoPlayerRouteTest` (5), `VideoPlayerViewModelTest` (19), `VideoTransportTest` (23) | playback → `MEDIA_PLAYBACK` (M10) | no overflow, no fullscreen button, no badge — asserted absent (group-3 correction) | exact |
| share sheet (where in scope) | — | Share | — | system share via `Intent` | selection bar Share / row share | hand-off to the system sheet | `MediaOpenTest`, folder/transfer tests | — | the mockup's in-app sheet is the platform share sheet on a phone — platform adaptation | acceptable adaptation |

## Library and system

| Mockup ID | Base fn | Screen | Route | Implementation | Reachability | Visual state | Tests | Gate | Known difference | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `histR` / `histS` | `history` | History (both tabs) | `history` | `ui/history/HistoryScreen.kt` | Bottom bar HISTORY | Received/Sent tabs, search, date groups | `LibraryAndSystemScreensTest` (tabs + search + lit bar); registered by `RouteRegistrationTest` | send-again → `TRANSFER_ENGINE` | none | exact |
| `settings` | `settings` | Settings | `settings` | `ui/settings/SettingsScreen.kt` | Bottom bar SETTINGS | Device, accent, appearance, transfer, system, diagnostics entries | `LibraryAndSystemScreensTest` (entries + lit bar) | WebShare port row → M11 (M1 handoff); battery shortcut is real | none | exact |
| `logs` | `logs` | Logs | `logs` | `ui/logs/LogsScreen.kt` | Settings → Logs | Filter, export .txt, clear | `LibraryAndSystemScreensTest` (export, errors-only, clear, lit bar) | — | none | exact |
| `crashes` | `crashes` | Crash reports | `crashes` | `ui/crashes/CrashesScreen.kt` | Settings → Crash reports | "Stored only on this device", view (card), Share, Clear | `LibraryAndSystemScreensTest` (a real report: badge, type, Share, Clear, lit bar) | — | never uploaded (copy asserted) | exact |
| `logShare` | `logs` | Logs · share sheet | — | Logs → Export → `Exports.writeText` + system share | Logs export | hands the real file to the share sheet | `LibraryAndSystemScreensTest` (export button); export path covered by M1 | — | platform share sheet instead of the mockup's drawn sheet | acceptable adaptation |
| `doctor` | `doctor` | Connection Doctor | `doctor` | see Connect group | Settings / Help | checks + refresh | `LibraryAndSystemScreensTest` | `DOCTOR_NEARBY` (M7) | none | exact |
| `help` | `help` | Help & FAQ | `help` | see Getting started | Settings / Connect | accordion | `LibraryAndSystemScreensTest` | — | none | exact |

## Route inventory (19 registered + 1 reserved)

`onboarding`, `connect`, `files`, `history`, `settings`, `logs`, `crashes`, `doctor`,
`help`, `folder/{treeUri}`, `viewer/{itemId}?sort`, `music/{itemId}?sort`,
`video/{itemId}`, `session/{layout}`, `broadcast/pick`, `broadcast/sender?chosen`,
`broadcast/sender/complete`, `broadcast/receiver/{recipient}`,
`broadcast/receiver/{recipient}/complete` — registered in `MorseApp.kt`;
`webshare` — declared, reserved, **not** registered, never navigated to (all four
properties asserted). No `queue`, `manual`, `qr` or `pair` route exists anywhere in the
catalogue (asserted against both names and values).

## Summary

* Mobile registry states: **33** (5 + 7 + 3 + 3 + 9 + 6).
* **Implemented and tested: 28.** **Gated with a named milestone: 5** (`discover` live,
  `consentP`, `consentB`, `webshare`, `receive` idle listener — each the production
  behaviour of a later milestone, each with its control routed through `FeatureGate`).
  **Missing: 0.**
* The gates are milestones 5–11: `TRANSFER_ENGINE`, `LAN_TRANSPORT`,
  `NEARBY_TRANSPORT`, `BACKGROUND_SERVICE`, `WEBSHARE_SERVER`, `DOCTOR_NEARBY`.
  No Milestone-3-and-beyond behaviour was started by this audit: no engine, no transport,
  no service, no Media3, no WebShare server.
