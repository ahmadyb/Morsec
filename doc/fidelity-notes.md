# Fidelity notes — reference mockup → implementation

This file records how `doc/morsecode_material3_mockup.html` is transcribed into the app, the
decisions taken where the reference is ambiguous, and the drift that was found and fixed during
milestone 1. It exists so a reviewer can check the visual layer without re-deriving it.

## 1. What the reference is

`morsecode_material3_mockup.html` is a single-file simulation: one `<style>` block (~47 KB), a
`SC.<key> = () => ...` screen builder per screen, and a device-frame harness that drives them.

Reproduced: the mobile app — screens, components, colours, type, spacing, motion, copy.

Deliberately **not** reproduced (simulator chrome, not product):

| Excluded | Why |
| --- | --- |
| Desktop top bar (theme/accent/reduced-motion switches) | These are developer controls; in the app they are Settings rows |
| Left screen registry | Navigation is the bottom bar + nested pushes |
| Phone bezel, notch, status-bar mock | Real device chrome |
| Gallery / comparison mode | Review tooling |
| The simulation's engine tick and scripted peers | Real discovery arrives with the transports (milestones 6–7); nothing is faked before then |

The reference's own data (peer names `Ravi's Redmi`, `Pixel 7X`, `Samsung A14`, `Office Laptop`,
the sample log/crash rows) is used **only** in tests and previews, never shipped as content.

## 2. How the CSS cascade is transcribed

The style block is layered: a prototype pass first, then a Material 3 pass (from roughly line 440)
that redeclares the same custom properties and selectors. **The last declaration wins**, and any
transcription that reads the file top-down and stops at the first match will be wrong.

Token scopes in the reference and their Kotlin equivalents:

| CSS scope | Kotlin |
| --- | --- |
| `:root` | `MockupTokens.raw["…\|root"]` (dark defaults + accents `sunflower`) |
| `html[data-theme="dark"]` / `["light"]` | `…\|dark`, `…\|light` |
| `html[data-accent="leaf\|ember\|violet\|sky"]` | `…\|leaf` etc. |

`--accInk` is overridden only for `violet` and `ember` (`#FFFFFF`); `leaf` and `sky` inherit
`#1A1400` from `:root` — the token table mirrors that rather than repeating a value.

`color-mix(in srgb, A p%, B)` is not a Compose primitive, so each occurrence is recorded as a
`DerivedMix` with an explicit kind:

* `SOLID` — `B` is a colour (e.g. `var(--card)`): interpolate `A` over `B`.
* `ALPHA` — `B` is `transparent`: the result is `A` with alpha `p`.

Confusing the two changes the rendered colour, which is why the parity harness compares the kind
as well as the operands (see §4).

## 3. Drift found and fixed in milestone 1

Each row was caught by comparing the final (last-wins) CSS against `core-design`. All six are now
fixed and pinned by a harness assertion.

| Token | Was | Reference says | Fix |
| --- | --- | --- | --- |
| `header.minHeight` | 60 (+ an invented `minHeightCompact` 56) | `.hd { min-height: 56px }` in the M3 pass | 56; the compact variant was removed — it existed in no CSS rule |
| `sheet.maxHeightFraction` | 0.76 | `.sheet { max-height: 82% }` | 0.82 |
| `tile.radius` / `tile.padding` | 12 / 10+6 (prototype pass) | `.tile { border-radius: var(--m3-r-md); padding: 12px 7px }` | 16 / 12+7 |
| `statCard.*` | missing | `.statcard { r-lg; padding 16px }`, `b { 30px }`, `span { 9px }` | added as their own component tokens (the summary cards are not `.tile`) |
| bottom-nav selected pill | `navPillSelected` = accent @ 16% alpha | `.bn button.on .pill { background: var(--m3-primary-container) }` | pill now uses `colors.primaryContainer` (accent @ 20% solid over card); the stale mix was deleted |
| `scrollThumb` | accent @ 62% **alpha** | `color-mix(in srgb, var(--acc) 62%, var(--t3))` — solid over `--t3` | kind corrected to `SOLID` with partner `t3` |
| `radarInner` | accent @ 7% alpha mix | final `.radar { background: radial-gradient(circle, var(--m3-primary-container), transparent 70%) }` | mix deleted; `RadarIndicator` paints `primaryContainer` → transparent at 70% |

Confirmations (already correct, now asserted rather than assumed): `--m3-primary-container` =
accent 20% solid over card; outline = `t2` @ 42% alpha; chip backgrounds = 18% alpha of
accent/ok/warn/err; chip outline border = accent @ 45%; `.ab button.pri .pill` = accent @ 14%;
radar ring/sweep = accent @ 25%/42%; focus ring = accent @ 52%; `.files-tabs button.on` =
accent @ 8%; selected bottom-nav label colour is `--t1` with weight 700 (the accent-coloured label
in the prototype pass is overridden).

## 4. Verification harnesses

Runnable anywhere with Node 18+; each exits non-zero on drift.

```bash
node tools/gen/icons.mjs --check    # 61 vectors: icons.json <-> drawables <-> MorseIcons.kt
node tools/verify/token-parity.mjs  # reference CSS <-> MockupTokens.kt
node tools/verify/refs.mjs          # every reference in the sources resolves
```

`token-parity.mjs` (191 checks) parses the reference CSS with comments stripped, last declaration
wins per (property, scope), and compares:

* **Raw colours** — every `MOCKUP-RAW` entry against its CSS custom property, in both directions
  (a colour the reference declares but Kotlin omits is also a failure).
* **Derived mixes** — 19 `color-mix()` formulas, compared operand-by-operand including the
  solid/alpha kind, using a balanced-paren parser (the reference nests mixes inside
  `radial-gradient`/`conic-gradient`/`box-shadow` values).
* **Metrics** — ~100 dimensions, type sizes, weights, letter-spacings, radii, percentages and
  animation durations, each read from the exact selector (`transform`, `!important` and
  `var(--m3-r-*)` indirection are all handled).
* **Motion** — `:active` press scale and the `--m3-motion` cubic-bézier control points.
* **Onboarding** — the four slide badge gradients from the JS `slides` array.
* **Immersive constants** — the photo viewer / video player paint themselves with inline template
  styles, not custom properties, so those five constants are matched against the templates.
* **Feature gate** — `CURRENT_MILESTONE`, the live/gated split, and that nothing stays gated past
  the final milestone.

`refs.mjs` (20 checks) is the substitute for a compiler's symbol resolution: `metrics.*`,
`colors.*`, `motion.*`, `MorseIcons.*`, `MorseTextStyles.*`, `R.string/.drawable/.color/.mipmap`,
`@string/@color/@drawable/@mipmap` in XML, plus wiring sanity (every `@HiltViewModel` annotated,
every screen composable reachable from the navigation graph, every route constant used, bottom-nav
order, Application/Activity declared in the manifest) and the milestone-hygiene marker scan that
mirrors `./gradlew checkMilestoneHygiene`. String literals are blanked before scanning so token
*keys* are not mistaken for token *references*.

## 5. Known representational differences

These are places where CSS and Compose cannot be identical; the chosen approximation is recorded
so it is a decision, not an accident.

* **Hairline scrollbars** — CSS paints a 2 px overlay scrollbar with a 24 px minimum thumb on the
  scroll container. Compose draws the same geometry with `Modifier.morseScrollbar`, but the CSS
  thumb fades in on hover, which has no touch analogue; the Compose version fades with scroll
  activity instead.
* **Conic sweep** — `.radar::after` is a `conic-gradient` from accent @ 42% to transparent at 28%;
  `Brush.sweepGradient` starts at 3 o'clock in Compose, so the brush is rotated to start at
  12 o'clock to match CSS.
* **`box-shadow`** — the reference's elevation tokens (`--m3-e1/e2/e3`) are transcribed as Compose
  elevation + the M3 surface tint rather than blur/offset triples, per Material 3 guidance.
* **Letter-spacing** — CSS `em` values are stored as ems in the token table and multiplied by the
  font size at construction, so the absolute `sp` offset stays correct if a size changes.
* **`prefers-reduced-motion`** — mapped to `MorseMotion.reducedMotion`, which collapses durations
  to a single frame rather than disabling the state change.

## 6. Milestone 1 screens

Onboarding (four-card tour, contextual permissions, persisted completion), Connect, Files
(photos/videos grid with day sections and select-all groups, music rows, apps, files), History,
Settings, Logs, Crashes, Connection Doctor and Help.

The Connection Doctor's checks are real platform queries — Wi‑Fi/Ethernet transport state,
multicast lock support, the API ≤ 32 location requirement for scanning, missing media
permissions, SAF grant count and revoked grants, battery-optimisation exemption, notification
permission, Play services package presence, and an actual `ServerSocket.bind` on ports
33455/33456/33457 to detect conflicts. Its transport-dependent checks are gated until
milestones 6–7 and say so.

Anything whose feature area is still gated routes to an honest dialog naming the milestone that
delivers it. No control simulates work.

## 7. Milestone 2 additions

**Files category pager.** The reference drives `.files-tabs` and the swipeable content as one
control (`data-tabswipe`, `.scr{touch-action:pan-y}`), so `MorseCategoryPager` keeps the strip
pinned and puts a `HorizontalPager` under it. Two details are decisions rather than accidents:

* A tap animates the pager; a swipe reports the **settled** page. Reporting every intermediate
  page would make the highlight flicker mid-drag and would load categories the finger merely
  travelled past.
* Each page renders its own category's content from `FilesUiState.contents`, which is cached per
  category. A page being swiped towards therefore shows that category, returning to a visited
  category is instant, and a category whose query has not answered yet shows `MorseLoading`
  rather than claiming to be empty.

The strip itself is unchanged: equal weights, no scrolling and therefore no horizontal scrollbar,
48 dp rather than the reference's ~34 dp for the touch floor (already recorded in §3's
confirmation list), and the three-column grid still comes from
`metrics.gridColumnsAtReferenceWidth`.

**The internal folder browser.** The reference draws a path bar only for WebShare (`.crumb`,
`.wsplit .crumb{position:sticky}`); the mobile app has no folder browser screen, and §4.3 states
what one must satisfy if it shows a path bar. `MorseCrumb` takes the WebShare pill's visual
language — `border-radius: var(--m3-r-full)`, `background: var(--raised)`, `padding: 11px 16px`,
`gap: 6px`, a 13 px folder glyph, monospaced labels, `--t2` segments, `--t3` separators, accent on
hover — and the differences are:

* 48 dp tall instead of ~40 dp, and the vertical padding is that height, so a level meets the
  touch floor (§11). The same call the category strip makes.
* The label uses `monospacedAddress` (11 sp), the mobile nested address-bar size, rather than the
  WebShare crumb's 12 px.
* The current level is `--t1` and carries a state description; the reference colours every segment
  `--t2` because on a desktop the cursor already shows which one you are on.
* A path longer than the phone scrolls inside the pill and keeps its tail on screen, with no
  scrollbar drawn. WebShare paths are short, so the reference never needs this.
* Press feedback replaces `:hover`, as everywhere else in the app.

Sticky behaviour follows the reference's structure rather than a scrolling app bar: the header and
the crumb are siblings *above* the scrolling list, so the crumb is always below the header, is
never behind it, and stays put while rows scroll.

There is no upward-arrow control and no `..` row, per §4.3. The breadcrumb is the way up, and the
browser walks its levels in place: one destination holds the whole walk, the level being shown is
written to saved state as it moves, and back — the header chevron and the system gesture alike —
means "up one level" until the granted folder is reached, where it means "leave the browser".
Walking in place rather than stacking a destination per level is what keeps the path in one piece
of state, so a restored browser comes back to the level that was being read.

A SAF grant reaches *down* from what the user picked and never up, so the granted folder is the
browser's ceiling. The breadcrumb still shows the levels above it, because a path without them is
not honest, but they are not targets: `MorseCrumb` draws them as text rather than as controls. For
the same reason a typed path is read against the grant, not against the level being shown, which is
what makes a pasted copy of the path land where it says it does.

Selection belongs to the level it was made in: changing level starts from nothing selected, and
each level starts at its top, because a scroll position belongs to the rows it was for.

**Copying and editing a path.** §4.3 allows both "only where safe and meaningful". Copy puts the
visible path (`Internal storage/Download/2026`) on the clipboard. Editing is offered because a deep
tree is tedious to tap through, and it is safe because the typed text is resolved by `SafPaths`
against the grant the user actually gave: a SAF uri, the copied display path, an absolute document
id, the emulated-storage path, a volume-relative path and a grant-relative path are all tried, and
only a result **inside the grant** is accepted. `..` and another scheme are refused outright rather
than reinterpreted, and the resolved level is checked against the platform before the browser
claims a folder exists — a level that cannot be read is reported as unavailable, which is also how
an empty folder is told apart from a revoked grant.

**The image viewer (§4.5).** The reference is `SC.viewer` plus the `.swipe` block, which carries
its own comment — `/* photo viewer: an edge-to-edge swipe deck, no arrow buttons */` — and
`endSwipe()`, which is where the deck's behaviour actually lives. The screen is literal black
(`.screen{background:#000}`), the header is `padding:4px 16px 10px` with the back control on
`rgba(255,255,255,.08)` in white, the name in white bold and the metadata line in `#8A8A8A` as
`${n} of 15 · 4032 × 3024 · 2.4 MB`. Slides are `padding:0 6px` with a 6 px radius picture, the
hint is `‹ swipe to browse ›` in the mono face at 10 px on `rgba(0,0,0,.45)`, `bottom:34px`, and
the dots are `bottom:14px` — idle 5 px on `rgba(255,255,255,.3)`, current `var(--acc)` at
`width:14px; border-radius:3px`. The action row is centred, `gap:14px`, `padding:18px 0 26px`:
four 44 px circles on `#1A1A1A` with `#DDD` glyphs and a fifth, Send, on `var(--acc)` with
`var(--accInk)`. All of those numbers are transcribed as they stand, in dp.

* **The deck wraps.** `endSwipe` takes each end modulo the set (`go_>0?(n<15?n+1:1):(n>1?n-1:15)`)
  and the track is built as three slides — previous, current, next — with the same modulo, so the
  reference has no first photograph and no last one. The app does this with a `HorizontalPager`
  over a page count far larger than the set (`ENDLESS_PAGES`), started in the middle and aligned so
  that `page % count` is the photograph being shown. Different mechanism, same behaviour, and the
  reason for it is that a pager is what already handles the drag, the fling and the settle — the
  reference rebuilds its triplet on every commit, which a real deck cannot do without a seam. The
  deck is also kept *inside* the aligned part of that range: the list arrives after the first
  composition, when the pager is still at page 0, and a deck left below its aligned window has no
  previous page to wrap to. Re-aligning there is a jump without animation, because the photograph on
  screen does not change and there is nothing for the user to see move.
* **Wrapping is also what keeps the drag off the system back gesture.** A horizontal drag in the
  middle of the screen never means the same thing as the edge swipe Android owns, and because both
  ends wrap there is no page at which a drag would have to be refused.
* **The commit threshold is not re-implemented.** The reference commits past
  `max(46px, width*0.22)`; the pager uses its own touch slop and fling velocity, which is the same
  decision expressed in the platform's units. The animation is the pager's rather than the
  reference's `.22s cubic-bezier(.22,.61,.36,1)`, and `MorseTheme.motion.reduced` replaces the
  animation with a jump — a preference the reference, being a document, has no way to express.
* **Dots are drawn for 2 to 20 photographs.** The reference always has exactly 15, which is the one
  set size its dots are designed for; a device with 4 000 photographs would draw 4 000 dots across
  a 411 dp screen, and 4 000 dots are not an indicator. Past that the "n of m" line in the header
  carries the position, which is where the reference puts it too.
* **The picture is the real file.** The reference paints `linear-gradient(160deg, …)` because a
  document has no photographs. The app has no image-loading dependency and does not add one for a
  screen that shows one frame at a time: the file is measured with `inJustDecodeBounds`, then
  decoded at the largest power-of-two `inSampleSize` that still leaves the frame at least as long
  as the screen, on IO, with the last few frames in a byte-sized `LruCache` so swiping back is
  instant. A frame that has not decoded shows `MorseLoading` on black rather than a gradient that
  would claim to be the photograph.
* **Edit, Delete and Info are real.** In the reference all three are `data-act="toast"` — the
  simulator's way of having no platform. The app asks the platform: Edit sends `ACTION_EDIT` with
  read and write grants and reports honestly when nothing on the device can edit the image; Delete
  confirms first and then asks the platform, through the storage layer where every other
  MediaStore operation lives: a resolver delete up to API 28, `RecoverableSecurityException`
  on API 29, `MediaStore.createDeleteRequest` from API 30. The three answers stay
  distinguishable all the way to the screen, because they mean different things — on API 29
  the user's agreement is a *permission* and the row is still on the device until the app
  deletes it again, where from API 30 the platform deletes it while asking. So `RESULT_OK`
  is reported as a deletion only once the platform has actually deleted something, and a
  refusal, a cancellation and a deletion are three different sentences. Info
  shows what was reported and says *Unknown* for what was not, instead of showing `0 × 0` — a kind
  the app inferred from the file name is not a type the platform reported, so the Type row says
  *Unknown* too rather than passing `image` off as a MIME type.
* **Share goes to the system chooser,** as it already does from the Files selection bar. The
  reference opens an in-app sheet (`sharesheet('viewer')` → `IMG_2043.jpg` / `4.1 MB · image/jpeg`),
  which is the simulator standing in for the chooser; the app does not keep a second, private list
  of share targets next to the platform's.
* **Send is gated.** `sendone` queues the photograph and navigates to the sending screen, which is
  the transfer engine — milestone 5. Until then the control is present, named "Send this photo", and
  routes to the honest gated dialog, per §17's rule that nothing is simulated in a gated feature's
  place.
* **There is no overflow button and nothing clickable over the image,** which §4.5 states and the
  `.swipe` comment states again. The viewer screen test asserts both: `action_more` does not exist,
  and no node with a click action has the current page as an ancestor.
* **Insets.** The reference is drawn inside a fixed bezel with a painted status bar, so its header
  starts at the top of the screen. The app is full-bleed and puts `statusBarsPadding()` on the
  header and `navigationBarsPadding()` on the action row: still edge to edge, but no control under
  a system one.
* **The order travels with the route.** The Files sort is not persisted, so the viewer is told which
  order to read the same list in (`viewer/{itemId}?sort=name.asc`) — otherwise "3 of 15" would be a
  different 3 of 15 from the one the user counted before tapping. The photograph being shown is
  written to saved state as the deck settles, so a restored viewer comes back to it, and a restored
  position past the end of a list that has since changed is not honoured.
* **A photograph that is gone is said to be gone.** If the opened id is no longer on the device the
  viewer shows `MorseEmptyState` rather than a black screen, because black is what the viewer looks
  like while it is working. The header stays — a viewer with nothing in it still has to be possible
  to leave — and the action row does not, because there is nothing left to act on. For the same
  reason the header draws no empty name and no empty metadata line.

### Music player (§4.6)

The screen is transcribed cell for cell from `SC.music`: a header whose label is the mono
`NOW PLAYING` (`MorseTextStyles.nowPlayingLabel`, already reserved in milestone 1), the 190 dp
artwork on an 18 dp radius with 6/22 dp margins, the title at `music.titleSize` over its
"artist · album" line, `scrub('music','big')` with `margin:16px 0 2px`, elapsed and remaining at
the two ends of one `.meta` line, the transport with `gap:22px;margin:18px 0 20px` and the 64 dp
accent play circle with its 30 dp glow, the four-cell strip of `height:56px` cells with 10 px
labels and hairlines above and below, `.sec` "Up next · N songs", and the `.li` queue rows — all
of it above `bottomNav('files')`, because a track is opened from Files and belongs to it.

* **One scrubber, in the design system.** The reference's own comment is "one scrubber for every
  player", so `MorseScrubber` lives in `core-design` with the `big` (5 dp bar) and `dark`
  (white-at-20% track) variants the video player will need, and the seek arithmetic —
  fraction ↔ position, clamped at both ends — is `ScrubberMath`, asserted as arithmetic rather
  than as whichever gesture a test happened to perform. It fills with `progressStart →
  progressEnd` on `progressTrack` at `progressRadius`, and the knob is
  `progress.knob` inside a 3 dp ring of accent at 26%, which is the `box-shadow` the reference
  spreads around it.
* **The knob appears under the finger, not before it.** `.scrub:hover .knob{opacity:1}` has no
  answer on a phone, so the knob is drawn while dragging and not otherwise — the same
  `.scrub.drag .bar>i{transition:none}` rule is honoured by switching the fill's animation off
  under the finger and under reduced motion.
* **Two sizes are the transcribed ones, not the CSS ones.** `progress.knob` is 14 dp where the CSS
  says 13 px, and `progress.scrubberPadding` is 10 dp where `.scrub` says `padding:7px 0`. Both
  were transcribed in milestone 1 as touch-floor enlargements and are asserted by the token
  harness, so the player follows the tokens. That padding is also part of the touch target: the
  gesture modifier is applied before it, so the finger has 24 dp to hit rather than the 4 dp bar,
  which is the only reason `padding:7px 0` is in the reference at all.
* **A tap seeks, and that needed the gesture written out.** The reference seeks in its
  `pointerdown` handler; Compose's `draggable` reports nothing until touch slop is exceeded, so a
  scrubber built on it would drag and would not answer a tap at all — half of "click anywhere on
  the track or drag the knob to seek" missing. `MorseScrubber` reads the gesture itself, seeking on
  the way down and consuming every move, which is also what `.scrub{touch-action:none}` means for
  a scrubber inside a scrolling list.
* **There is no clock, and nothing pretends to be one.** The reference advances `S.music.pos` in
  its ticker; this build has no audio engine, so the position moves only when the user seeks it,
  and a track ends only when `MusicPlayerViewModel.trackEnded()` is called. That method exists now
  rather than in milestone 10 because "repeat this track", "repeat the queue" and "stop at the end
  of the queue" are three different answers and the repeat control would otherwise be a switch
  that switches nothing; all three are tested. Media3, MediaSession, audio focus and the
  notification remain milestone 10, and `FeatureArea.MEDIA_PLAYBACK` stays gated until then.
* **The header's third cell.** The reference ends its header with an overflow button that has no
  action. This one ends with the app's gate for `MEDIA_PLAYBACK` — the same idiom as the viewer's
  Send, which says plainly that playback arrives in milestone 10 and that nothing has been
  simulated in its place — and becomes a spacer of the same size once the gate opens, so the label
  stays centred either way. A menu with nothing in it would have been the placeholder the product
  forbids.
* **The transport and the strip do their jobs.** In the reference, shuffle, previous, next, repeat
  and all four strip cells are toasts. Here they are state: shuffle and repeat change what next
  and end-of-track mean, previous and next walk the queue and wrap at both ends, Save and Liked
  answer one like state, Share hands the file to the platform, and Queue scrolls to the queue.
* **Save and Liked are one idea drawn twice.** The reference gives both cells a heart and no
  behaviour; the master prompt has no favourites store. So one per-track state answers both, is
  restored with the player, and does not outlive it — persisting likes belongs with the milestone
  that owns saved tracks, not with a screen. The Liked cell carries the accent while the state is
  on, where the reference hardcodes it on.
* **Artwork is the platform's or the reference's stand-in.** From API 29 `ContentResolver
  .loadThumbnail` returns a track's embedded album art, decoded through the same `ThumbnailCache`
  the Files grid uses. Below that, the MediaStore thumbnail tables hold images and video only —
  their row ids are per table, so asking the image table for an audio row could answer with
  somebody else's picture — and a track shows the reference's own gradient tile with its black
  note. The artwork is decorative to a screen reader: the title and its artist line are the next
  things on screen and naming the file again would say it twice.
* **The queue is the list Files was showing.** The route carries the tapped track's id *and* the
  sort token (`music/{itemId}?sort=name.asc`), so "Up next · 9 songs" counts the nine the user was
  looking at. The count is a plurals resource: the reference prints "9 songs" from a raw number
  and would print "1 songs".
* **Queue rows are tappable; the reference's are inert.** Selecting a row shows that track from
  its start and deliberately does not begin playing: the transport keeps whatever the user last
  asked of it. The shown track's row carries the accent title the reference gives it, on
  `MorseListRow`'s selected wash.
* **The playing row's tile is the accent, not the gradient.** The reference paints the playing
  row's 34 px tile with the artwork's amber gradient; that gradient is a stand-in for a picture,
  and at row size the app's accent says "this is the one" without borrowing artwork that may not
  exist. Other rows keep `--raised` and `--t3`, as drawn.
* **A selected list row is not yet announced as selected.** `MorseListRow` draws selection but sets
  no `SemanticsProperties.Selected`, anywhere in the app. Adding it is a design-system change that
  belongs with the multi-select work in milestones 3–4 rather than with this screen; the test
  asserts the row the model selects is the one drawn, and that the shown track is exactly one row.

### Video player (§4.7)

The stage is the reference's own gradient, stop for stop: `radial-gradient(circle at 60% 60%,#1b1a10,#000
70%)`, with the 70% measured the way CSS measures a circle gradient of unspecified size — along the ray to
the farthest corner. With no decoder until milestone 10 that glow is what separates the picture area from
the `#0B0B0B` control bar beneath it, so the token is `videoSurfaceGlow` and the parity harness now checks
it against the template's own inline style, the way it already checks `viewerBackdrop` and `viewerControlBar`.
The stop is a constant in the screen rather than a token because the document writes it inline in one place
and nowhere else; the two colours it runs between are tokens.

* **The seek control is the same component as the music player's.** `.scrub.big.dark` — the taller bar on a
  track of white at 20% — is `MorseScrubber(big = true, dark = true)`, unchanged. Its tap-and-drag gesture,
  its touch-target padding and its reduced-motion behaviour are asserted once, in `MorseScrubberTest`, in
  both variants; this screen asserts only that a seek reaches the paper, because a synthetic drag inside a
  whole screen is a statement about the injection framework rather than about the player.
* **The volume strip is a gesture surface, not ten buttons.** It answers on the way down and for every move
  of the same pointer, which is what "Volume is a real slider — click any bar to set the level" means for a
  finger, and it announces itself as an adjustable range whose action calls the same callback. The reference
  lights a bar at 75% on `:hover`; a phone has no cursor, so no state is drawn for one. The speaker is
  drawn 30 dp as the reference draws it and reports a 48 dp touch box as §11 requires, which is part of why
  the row below needs the width rule.
* **One width rule, from the reference's own arithmetic.** Five controls at their drawn sizes fit the 411 dp
  phone this document was laid out for. Four 48 dp touch boxes plus a volume control of ten bars and a
  readout need 397 dp of the 324 dp a 360 dp phone has left after the bar's padding, so the width the row
  needs is computed from the tokens it is drawn with, and on a narrower device the volume control takes the
  line below the transport. Nothing moves at the reference's width and no control is ever pushed off the
  screen; the screen test renders it at 360 dp to say so.
* **The header is back, the name and its metadata, and nothing else.** No overflow menu (§4.7), no
  fullscreen switch — the reference draws that on its WebShare player, not on the phone's — and no standing
  feature badge. The gate for `MEDIA_PLAYBACK` is an answer to an action a user asks for, not furniture on a
  screen they are merely looking at, so this header renders it nowhere: it is the music player, whose
  approved header does carry one, that invokes it. The screen test asserts the header by the exact set of
  content descriptions rendered, so a control added later has to be added to that set deliberately.
* **It opens at 0:00, paused.** The reference opens a clip already playing at 28% of its length because its
  clock is a simulation with something to show for it. Nothing here advances on its own, so a position nobody
  put there would be a claim about playback that is not happening. `VideoTransportRules.skip` and `.seek` are
  pure functions, so the ±10 second controls and the scrubber clamp by one rule rather than three that agree
  today, and the twenty-two tests that ask for the same input twice are checking that no clock exists.
* **Fullscreen is model state with no control in this screen.** `VideoTransport.fullscreen`,
  `VideoTransportRules.toggleFullscreen` and its `SavedStateHandle` round-trip are kept and tested because
  §4.7 asks this player for fullscreen/orientation handling and Media3 will need somewhere to put it, but the
  approved Milestone 2 header exposes no such action and the state changes nothing on screen today. Locking
  the orientation and hiding the system bars are the window's business and arrive with the real player in
  milestone 10, which is why nothing here reaches for an Activity.
* **Subtitles have three states and honestly report the one this build can have.** Nothing here can open a
  container and enumerate its tracks until Media3 lands, so a clip reports `UNAVAILABLE` and the control
  says so when pressed rather than switching a track that cannot appear. The state behind it already knows
  `OFF` from `ON`, so the control is a switch that switches something the day a track exists; the screen
  test drives all three.
* **A nudge says what it did; the time above the scrubber is the answer.** The reference's toast also prints
  the time it landed on. Composing that sentence needs a resource read inside a click lambda, which is the
  lint rule this codebase keeps itself to, so the toast names the action and the elapsed label — moving on
  the same clamp the toast would have quoted — carries the position. The two mute toasts keep the
  reference's wording and its remembered level, that level being formatted in the view model so the
  sentence can be composed before the click happens.
* **A header correction, recorded because it is the shape of thing that drifts.** The first version of this
  screen carried a fullscreen switch and a standing `MEDIA_PLAYBACK` badge in the header, on the reasoning
  that §4.7 asks for fullscreen handling and every other gated screen shows its gate. Both were wrong for
  this screen: the approved header is three things, and a badge that is always on display is a claim about
  the build rather than an answer to a request. The three strings those controls used are deleted with them,
  so lint's unused-resource warning does not grow a tail.
* **The 42nd lint warning was mine, and it is fixed rather than documented.** `AutoboxingStateCreation` at
  `Scrubber.kt:118` appeared when the scrubber's gesture was rewritten: the track width is an Int, and
  `mutableStateOf` boxes it on every size change. It is held in `mutableIntStateOf` now, so the count is back
  to the 41 the viewer left behind and the video player's own code adds none — the volume metrics and the
  glow token that this group added are read through `MockupTokens` like every other token.
