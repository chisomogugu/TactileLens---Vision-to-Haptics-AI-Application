# Groundwork: android-compose-camera-ml-ui-redesign — 2026-05-01

## Current state

- Two screens implemented in Jetpack Compose: `ScannerScreen` (live camera + tap-to-capture) and `ResultsScreen` (axes + dot grid). All UI lives in `app/src/main/java/com/tactilelens/app/MainActivity.kt` and `app/src/main/java/com/tactilelens/app/ui/results/ResultsScreen.kt`.
- Single hand-rolled palette in `app/src/main/java/com/tactilelens/app/ui/theme/Color.kt` — six tokens, all blue-cyan family (`DeepSpace #04111B`, `NebulaBlue #0A2333`, `GlowCyan #61E7FF`, `VividBlue #3253DC`, `PanelInk #0F1D2A`, `MistWhite #EAF8FF`).
- Type defaults to Material 3 baseline (`Roboto`-ish system stack) with manual `letterSpacing` and `fontWeight` overrides scattered inline.
- Interactive dot grid in `ui/results/InteractiveGridZone.kt` uses `VividBlue` dots on the `NebulaBlue/DeepSpace` background — fails contrast.
- Axis values shown as raw monospace numbers (`0.42`, `0.78`, etc.). Useful for testing, reads as a debug HUD in a demo.

## Solutions surveyed

Reference repos (Mode B):

- **android/compose-samples — Jetcaster** (https://github.com/android/compose-samples/tree/main/Jetcaster) — qualifies: maintained by Google's Jetpack Compose team, large multi-screen app, custom theming that extracts color from media. Conventions:
  - `Jetcaster/core/designsystem/src/main/java/com/example/jetcaster/designsystem/theme/Color.kt` — semantic color tokens (`primary`, `surface`, `onSurfaceVariant`) wrapped in a custom `JetcasterColorScheme` data class, not a flat color list.
  - Top-level `JetcasterTheme` composable wires colors + typography + shapes in one place; every screen consumes via `MaterialTheme.colorScheme`.
- **android/compose-samples — JetLagged** (https://github.com/android/compose-samples/tree/main/JetLagged) — qualifies: most "instrument-like" of the official samples (sleep tracker), demonstrates custom path-based graphs, unusual layouts. Conventions:
  - `JetLagged/.../ui/theme/Theme.kt` — uses a constrained palette of off-whites, neutrals, and one accent color; avoids pulling default Material 3 colors.
  - Custom-drawn timeline / graph components in `ui/sleep/SleepGraph.kt` use `Canvas` + `Path` instead of stock components for technical visualizations.
- **android/nowinandroid** (https://github.com/android/nowinandroid) — qualifies: Google's reference architecture sample, actively maintained, dynamic-color-aware. Conventions:
  - `core/designsystem/src/main/java/com/google/samples/apps/nowinandroid/core/designsystem/theme/Color.kt` — centralized color constants imported by `Theme.kt`; avoids the `Color(0xFF...)` literals scattered throughout components.
  - Component library in `core/designsystem/src/main/java/.../component/` — `NiaButton`, `NiaTopAppBar`, `NiaTextButton`. Branded wrappers around Material 3, not direct M3 usage.

**Convergence note:** all three samples (a) wrap Material 3 in a project-named theme rather than using M3 defaults directly, (b) define color tokens in one central file with semantic names, (c) build branded component wrappers (`NiaTopAppBar`, etc.) rather than scattering Material 3 components inline. Our current code does none of these — everything is inline. **Adopting the wrap-and-centralize pattern is the highest-leverage refactor.**

## Canonical guidance consulted

- **Material 3 Expressive (Google, May 2025 launch, ongoing)** (https://blog.google/products/android/material-3-expressive-android-wearos-launch/) — load-bearing principle: "more emphasis on shape, motion, and personalization." For technical apps, the relevant takeaway: typography hierarchy carries more weight than chromatic decoration. Headlines bigger and bolder; secondary type smaller and quieter. We violate this by treating every text element as roughly the same weight.
- **Pixel UI / Pixel Camera design language (2026 trend reporting)** (https://www.androidauthority.com/google-pixel-ui-3025558/) — load-bearing principle: "feature-rich doesn't have to be complicated... balances functionality with clean, minimal aesthetics." Pixel Camera in particular: edge-to-edge preview, monochrome HUD, single accent color reserved for "in-progress" state, no chrome between user and the photo.
- **Snapdragon brand (Qualcomm, 2021 standalone rebrand)** (https://hothardware.com/news/qualcomm-snapdragon-look-colors-bold-rebrand) — relevant data: Snapdragon's tier palette (Gold / **Snapdragon Red** / Nickel / Gunmetal / Midnight). For a Qualcomm-judged demo, a **Snapdragon-adjacent red accent on a Midnight-adjacent canvas** is a deliberate, non-literal hat-tip the judges will notice without being branded literally.
- **Qualcomm AI Hub web app** (https://aihub.qualcomm.com/) — relevant principle: light canvas, generous whitespace, "hub" structure with image tiles + minimal chrome. Our demo deliberately diverges (dark canvas) because (a) camera apps work better dark, (b) the haptic dot grid needs dark for contrast, (c) instrument feel > marketing feel. We adopt the *restraint and hierarchy* without the literal palette.

## What's good (do not change)

- `app/src/main/java/com/tactilelens/app/AppContainer.kt` — composition root and renderer wiring stays as-is; only UI layer is being redesigned.
- `app/src/main/java/com/tactilelens/app/data/model/AnalysisResult.kt` — data contract is correct and survives the redesign.
- `app/src/main/java/com/tactilelens/app/ui/results/InteractiveGridZone.kt` — per-material spacing logic + grid math are sound; only the visual styling (colors, dot rendering) changes.
- `app/src/main/java/com/tactilelens/app/MainActivity.kt:171-203` — the `LaunchedEffect(imageUri)` two-stage capture+analyze flow is the right structure; only the surrounding chrome changes.
- `app/src/main/java/com/tactilelens/app/ui/results/ResultsScreen.kt` material-dropdown override mechanism — the interaction is good, only the visual treatment changes.

## Design DNA

**Instrument-grade.** The app is a tactile measurement instrument that happens to live on a phone. Reference frame is laboratory equipment / oscilloscope / pro audio plugin, not "AI app." Three commitments that fall out:

1. **Near-black canvas, no blue tint.** Cyan-on-deep-blue is the AI dashboard cliche. We move to neutral carbon (#0A0A0B) so the only chroma in the UI is intentional and load-bearing.
2. **Single chromatic accent, used sparingly.** Snapdragon-adjacent red (#FF4040 — "Pulse") used ONLY for: live signal (ripple from tap), confidence indicator dot, axis-bar end-cap. Everything else is monochrome (Bone / Mercury / Iron / Slate / Carbon).
3. **IBM Plex type system.** Plex Sans for UI, Plex Mono for metrics. Free, open, distinctive without being trendy. Loaded via Google Fonts Compose API — no asset bundling.

This is correct for THIS app because: (a) judged by Qualcomm — Snapdragon-adjacent red is a deliberate non-literal hat-tip; (b) vision-to-haptics — the bone-on-carbon palette puts the user's finger and the live photo at center; (c) runs on NPU — instrument vocabulary (latency pill in mono, sparse chroma) reads as "real measurement" not "AI demo."

## Design system

### Color palette

| Token | Hex | Use |
|---|---|---|
| Carbon | `#0A0A0B` | Canvas background |
| Slate | `#131316` | Raised surfaces (cards, photo frame) |
| Iron | `#1F1F24` | Separators, axis-bar tracks, interactive backgrounds |
| Graphite | `#3A3A42` | Disabled / low-emphasis |
| Mercury | `#9BA0A8` | Secondary text, axis pole labels |
| Bone | `#E5E7EB` | Primary text, dot-grid dots, axis fill bar |
| Snow | `#FAFAFA` | Reserved (max contrast, used sparingly) |
| **Pulse** | **`#FF4040`** | **Sole chromatic accent** — confidence indicator, axis end-cap, tap ripple, "REC"-style live signal |

### Typography

- **Display / UI:** [IBM Plex Sans](https://fonts.google.com/specimen/IBM+Plex+Sans) (weights: 400, 500, 600). Loaded via [Google Fonts Compose API](https://developer.android.com/jetpack/compose/text/fonts#downloadable-fonts).
- **Metrics:** [IBM Plex Mono](https://fonts.google.com/specimen/IBM+Plex+Mono) (weights: 400, 500). Same loading mechanism.

| Style | Size | Line | Weight | Tracking | Use |
|---|---|---|---|---|---|
| Display | 48sp | 52sp | 500 | -0.02em | Material name on Results |
| Title | 22sp | 28sp | 500 | 0 | Section titles |
| Body | 14sp | 20sp | 400 | 0 | Instructions |
| Label | 11sp | 14sp | 600 | 0.12em uppercase | Axis labels, status labels |
| Metric L | 28sp | 32sp | 500 mono | -0.02em | Confidence value |
| Metric S | 12sp | 16sp | 500 mono | 0 | Latency pill, session counter |

### Spacing

4 / 8 / 12 / 16 / 24 / 32 / 48 / 64 dp. No 20 / 28 / 36 — keep the rhythm tight.

### Motion

Single ease everywhere: `cubic-bezier(0.2, 0.8, 0.2, 1)` (Material's "emphasized decelerate"). Durations:

- 100 ms — touch feedback (dot scale, button press)
- 240 ms — state transitions (chip open, dropdown)
- 480 ms — reveal animations (axis bars filling, photo entering)
- 800 ms — page transitions (Scanner → Results)

No bouncy springs. No elastic overshoots. Decisive linear time.

## Component anatomy

### Material chip

Replaces the current "{MATERIAL_NAME}" with literal curly braces.

- 48dp height, 4dp corner radius (not pill).
- Pulse-color 8dp dot on the leading edge.
- Vertical stack inside: `DETECTED` (label, Mercury) above the material name (Display, Bone).
- Trailing chevron icon (Mercury) when dropdown is available.
- Background: `Slate` with 1px `Iron` border. Subtle.

### Axis row (numbers REPLACED with polar adjective scale)

This is the biggest semantic change. Per user feedback: "instead of the numbers just showing (that was relevant and useful for testing)".

Current: `ROUGHNESS  0.42`
New: a polar-anchored scale with a position marker. Each axis has two opposing words (smooth↔coarse, soft↔hard, slick↔sticky, sparse↔dense). The bar shows where this material lands between them. No raw number.

```
ROUGHNESS                              ← 11sp Mercury label, optional
SMOOTH ━━━━━●━━━━━━━━━━ COARSE          ← 12sp Mercury poles, Bone bar, Pulse marker
```

Marker is a 4dp Pulse-colored vertical bar over a Bone-filled track. Track is 1dp tall on Iron, fills to marker position with Bone. Reads as a measurement ruler.

### Latency pill

`NPU · 23 ms` in Plex Mono, 12sp, Bone on Slate background, 2dp corners, 6dp horizontal pad. Top-right corner of photo. No glow, no border.

### Dot grid (THE fix)

Replace blue-on-blue with bone-on-carbon.

- Background: `Carbon` (the canvas).
- Dot: 6dp circle, `Bone` color, **18% opacity** baseline.
- Touched dot: 100% opacity, scale 1.6x, 100ms ease.
- Ripple: 1dp `Pulse` ring expanding from touched dot, 480ms, scale 0→8x, opacity 1→0. One per dot crossing.
- Per-material dot spacing unchanged from existing code.

### Camera HUD (Scanner)

- Edge-to-edge preview (no rounded card frame).
- Four corner crosshairs in `Bone` (each 24dp L, 2dp stroke). Inset 32dp from preview edges.
- Center reticle: 40dp circle (1dp `Bone` 60% opacity stroke) with crosshair lines through it.
- "TAP ANY SURFACE" instruction below preview, Bone Title.
- Subtitle "Or use shutter to frame" Mercury Body.
- Shutter: a precise 64dp wide × 4dp tall `Bone` bar. NOT a ring. Tappable. Hover/press → Pulse color. "FRAME" Label below in mono.

## Cleanup phases

### Phase 1: Replace color palette + typography + remove raw axis numbers

**Why.** The single highest-leverage visual upgrade in the smallest blast radius. Reference: Jetcaster + Now in Android both centralize tokens in one file (Convention 1 above). User feedback: "instead of the numbers just showing (that was relevant and useful for testing)" — replacing numbers with polar-adjective scales removes the dev-tool feel.

**Reference pattern.**
- `compose-samples/Jetcaster/core/designsystem/src/main/java/com/example/jetcaster/designsystem/theme/Color.kt` — semantic tokens via `data class JetcasterColorScheme(...)`.
- Material 3 expressive guidance (above) — typography hierarchy carries more weight than chromatic decoration.

**Files affected.**
- /Users/edmond/Projects/TextSense/TactileLens/app/src/main/java/com/tactilelens/app/ui/theme/Color.kt
- /Users/edmond/Projects/TextSense/TactileLens/app/src/main/java/com/tactilelens/app/ui/theme/Theme.kt (verify exists / create)
- /Users/edmond/Projects/TextSense/TactileLens/app/src/main/java/com/tactilelens/app/ui/theme/Type.kt (verify exists / create)
- /Users/edmond/Projects/TextSense/TactileLens/app/src/main/java/com/tactilelens/app/ui/results/ResultsScreen.kt (axis-row rewrite)
- /Users/edmond/Projects/TextSense/TactileLens/app/build.gradle.kts (Google Fonts dep)

**Steps.**
1. Add Google Fonts dep to `app/build.gradle.kts`: `implementation("androidx.compose.ui:ui-text-google-fonts:1.7.5")` (or current).
2. Rewrite `ui/theme/Color.kt` with the 8 tokens above. Keep old names as `@Deprecated` typealiases pointing to new ones for one phase so call sites compile.
3. Create / overwrite `ui/theme/Type.kt` with the 6-style scale above using `GoogleFont("IBM Plex Sans")` and `GoogleFont("IBM Plex Mono")`.
4. Wire into `ui/theme/Theme.kt` via a `TactileLensTheme` composable that sets `MaterialTheme.colorScheme` + `typography`.
5. In `ResultsScreen.kt`, replace the four `StatRow("ROUGHNESS", "%.2f".format(...))` rows with a new `AxisScaleRow(name, leftPole, rightPole, value)` composable using the polar-anchored design above. Map values to poles:
   - Roughness → SMOOTH / COARSE
   - Hardness → SOFT / HARD
   - Friction → SLICK / STICKY
   - Density → SPARSE / DENSE
6. Delete the alternate `StatGraphView` page from the `HorizontalPager` — it was a debug visualization, the new bars carry the load.
7. Replace dot-grid colors in `InteractiveGridZone.kt`: dots use `Bone @ 18%`, ripple uses `Pulse`, background uses `Carbon`.
8. Remove all literal `Color(0xFF...)` hex calls from `MainActivity.kt` and `ResultsScreen.kt`; route through theme tokens.

**Verification.**
- [x] `./gradlew :app:compileDebugKotlin` passes.
- [x] `./gradlew :app:assembleDebug` produces an APK.
- [ ] Manual: launch app, capture a photo, confirm: (a) no blue dots, (b) dot grid visible against background, (c) axis rows show pole-words instead of `0.42` numbers, (d) material chip has no curly braces, (e) text uses Plex (not Roboto).
- [x] `grep -RnE "0xFF(04111B|0A2333|61E7FF|3253DC|0F1D2A|EAF8FF)" app/src/main` returns 0 lines in source code (only docs in INTEGRATION_GUIDE.md still reference; non-shipped).

**Effort.** Medium — ~2 hours. The compose-samples-style centralized theme is a 30-min refactor; the axis-row redesign + dot-grid recolor are 1 hour; verification is 30 min.

**Trigger.** Now. Highest leverage per minute spent.

**Implementation note (Phase 1 done).** Switched from Google Fonts API to bundled IBM Plex TTFs in `app/src/main/res/font/` after the `R.array.com_google_android_gms_fonts_certs` reference failed to resolve and adding the cert XML wasn't worth the time risk. Fonts now ship with the APK (~880 KB total, negligible vs current 155 MB) and have zero Play Services dependency at runtime — better demo reliability anyway.

### Phase 2: Restructure Results layout (hierarchy + chip + photo block)

**Why.** Current Results has small photo + small stats panel side-by-side, then a chip below, then a dot grid — five floating elements with no focal hierarchy. M3 Expressive guidance: typography (and scale) carry the hierarchy. Reference: Jetcaster's full-bleed media headers establish the photo as the dominant element.

**Reference pattern.**
- `compose-samples/Jetcaster/.../ui/podcast/PodcastDetailsScreen.kt` — hero image at top, full screen width, with overlay metadata.
- See ASCII mockup in `## Mockups` below.

**Files affected.**
- /Users/edmond/Projects/TextSense/TactileLens/app/src/main/java/com/tactilelens/app/ui/results/ResultsScreen.kt (full layout rewrite)

**Steps.**
1. Restructure `ResultsScreen` Composable into vertical sections (no Row at the top): top app bar, full-width photo block (320dp tall), axes block, dot-grid block.
2. Photo block becomes the hero: full bleed left/right, gradient fade to Carbon at the bottom 50% so material chip + confidence overlay readably.
3. Move material chip to bottom-left of photo overlay; move confidence (now Plex Mono Display) to bottom-right.
4. Axes block stacks 4 `AxisScaleRow`s vertically with 16dp gap.
5. Dot-grid block fills the remaining space below axes; small `TOUCH TO FEEL` Label header with `DRAG · NN` meta on the right.
6. Top app bar: back arrow + `RESULT · 002` (mono session counter) on the left, latency pill on the right.

**Verification.**
- [ ] Side-by-side photo before/after Phase 2 — focal hierarchy is photo > material > confidence > axes > grid.
- [ ] Try the demo: judge can read what the app DOES from the Results screen alone, in 3 seconds.

**Effort.** Medium — ~1.5 hours.

**Trigger.** After Phase 1 ships and looks right. Skip if time runs out — Phase 1 alone is a defensible improvement.

### Phase 3: Restructure Scanner layout

**Why.** Current Scanner crops the camera feed inside a small rounded card with a generic white-ring shutter. Pixel Camera reference (above): edge-to-edge preview, monochrome HUD, precise shutter. The current card-framed preview reads as "embedded widget" not "primary tool."

**Files affected.**
- /Users/edmond/Projects/TextSense/TactileLens/app/src/main/java/com/tactilelens/app/MainActivity.kt (`ScannerScreen` Composable, ~lines 264-425).

**Steps.**
1. Remove the rounded `Card` wrapping `CameraPreview`. Preview now goes edge-to-edge.
2. Add `CameraHud` Composable: 4 corner crosshairs (Bone) + center reticle. Inset 32dp from preview edges.
3. Replace shutter ring (`Box.size(80.dp)…border(4.dp, Color.White)`) with a precise 64×4dp Bone bar in a tappable Box. Pulse on press.
4. Keep "TAP ANY SURFACE" instruction strip above the shutter; restyle in Title (Bone) + Body (Mercury).
5. Top app bar: `TACTILELENS` wordmark left, monospace session counter (`001`) right.

**Verification.**
- [ ] Preview is edge-to-edge.
- [ ] Shutter is the bar, not the ring.
- [ ] HUD crosshairs visible without obscuring the live image.

**Effort.** Low — ~1 hour.

**Trigger.** After Phase 2 ships and Results screen looks right.

### Phase 4 (optional): Motion polish

**Why.** Material 3 Expressive: motion carries identity. Not strictly required but reads as "polished" rather than "static."

**Files affected.**
- ResultsScreen.kt (entrance animations on axis bars)
- InteractiveGridZone.kt (per-dot scale + ripple)
- MainActivity.kt (Scanner → Results transition)

**Steps.**
1. Axis bars: `LaunchedEffect` triggers `Animatable` from 0f to value, 480ms, staggered 80ms each.
2. Dot grid: per-touch scale `Animatable` (0.85→1.6 in 100ms) + ripple emission via `LaunchedEffect`.
3. Scanner→Results page transition: 800ms cross-fade with photo scaling from preview position to hero position (shared element).

**Verification.**
- [ ] Recording of Scanner→Results shows axis bars sweeping to position over ~700ms total.
- [ ] Touching the dot grid produces visible ripple from each touched dot.

**Effort.** Medium — ~2 hours. Skip if time-pressured.

**Trigger.** Only after Phases 1-3 ship.

## Mockups

### Scanner screen (post-Phase 1+3)

```
┌─────────────────────────────────────────┐
│  9:41                          5G  100%│ ← system status bar
├─────────────────────────────────────────┤
│  TACTILELENS                       001  │ ← Plex Mono 11sp tracked, Mercury / Bone
│  ─────────────────────────────────────  │ ← 1px Iron divider, 24dp inset
│                                         │
│  ┌─                              ─┐     │ ← Bone L-corners, 2dp stroke, inset 32dp
│                                         │
│                                         │
│                                         │
│                                         │
│                ╭─────╮                  │
│                │  +  │   ← 40dp circle, │
│                ╰─────╯     1dp Bone 60% │
│                                         │
│                                         │
│                                         │
│                                         │
│  └─                              ─┘     │
│         (live preview, edge-to-edge,    │
│          ENTIRE area is tap-to-capture)│
│                                         │
│         Tap any surface                 │ ← Title, Bone
│         Or use shutter to frame         │ ← Body, Mercury
│                                         │
│              ▬▬▬▬▬▬▬▬                   │ ← 64×4dp Bone bar, tap to capture
│              FRAME                      │ ← Label, Mercury, mono
└─────────────────────────────────────────┘
   Carbon canvas everywhere outside preview
```

### Results screen (post-Phase 1+2, no raw numbers)

```
┌─────────────────────────────────────────┐
│  9:41                          5G  100%│
├─────────────────────────────────────────┤
│  ←  RESULT · 002          NPU · 23 ms   │ ← Mono labels, latency pill on right
│  ─────────────────────────────────────  │
│                                         │
│   ┌───────────────────────────────────┐ │
│   │                                   │ │
│   │                                   │ │
│   │      [captured photo, 320dp tall, │ │
│   │       full bleed, gradient fade   │ │
│   │       to Carbon at bottom 50%]    │ │
│   │                                   │ │
│   │                                   │ │
│   │  ╭─────────╮              0.84    │ │ ← chip BL, confidence BR
│   │  │● DETECTED│             ──────  │ │   "0.84" Plex Mono 28sp Bone
│   │  │  Wood    │           CONFIDENCE│ │   "CONFIDENCE" Mercury Label
│   │  ╰─────────╯                      │ │   ● = Pulse 8dp dot
│   └───────────────────────────────────┘ │
│                                         │
│   ROUGHNESS                             │ ← Mercury label
│   SMOOTH ━━━━━●━━━━━━━━━━━ COARSE       │ ← Bone fill, Pulse 4dp marker
│                                         │
│   HARDNESS                              │
│   SOFT   ━━━━━━━━━━━━━━━●━ HARD         │
│                                         │
│   FRICTION                              │
│   SLICK  ━━━━━━●━━━━━━━━━━ STICKY       │
│                                         │
│   DENSITY                               │
│   SPARSE ━━●━━━━━━━━━━━━━━ DENSE        │
│                                         │
│   TOUCH TO FEEL              DRAG · 64  │ ← Bone label / Mercury meta
│   ─────────────────────────────────────│
│   · · · · · · · · · ·                   │ ← Bone @ 18% opacity dots
│   · · · · · · · · · ·                   │   on Carbon background
│   · · · · ◯ · · · · ·                   │ ← Pulse ring expanding
│   · · · · ● · · · · ·                   │ ← active dot: Bone 100%, scale 1.6x
│   · · · · · · · · · ·                   │
│   · · · · · · · · · ·                   │
└─────────────────────────────────────────┘
```

Numbers are gone from the user-facing axis rows. Pole-anchored scale carries the meaning. The ONE number that survives is `0.84` confidence — but it's now hero-sized in Plex Mono, treated as a primary readout, not a debug HUD.

## Out of scope

- Adaptive layouts for tablets / foldables — not relevant for hackathon demo on a phone.
- Light mode — dark is the right default for a haptics demo. Not building a light variant.
- Localization — copy stays English.
- Accessibility audit (TalkBack walkthrough, contrast certification) — quick visual contrast was prioritized but no formal a11y pass.
- Animated material chip when ML re-classifies during runtime — defer to post-hackathon.
- Replacing the raw `0.84` confidence number with a glyph — kept as a number because confidence is the one place a percentage genuinely communicates faster than a glyph.

## Hand-off

Run `/grill-me` against this plan to stress-test the tradeoffs. Then `/implement specs/plans/2026-05-01-groundwork-android-compose-camera-ml-ui-redesign.md` to execute Phase 1.
