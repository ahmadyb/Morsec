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
