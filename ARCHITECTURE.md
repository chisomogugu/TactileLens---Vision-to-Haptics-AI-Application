# TactileLens Architecture

> **Purpose.** This document is the architectural source of truth for TactileLens. It describes the layer boundaries, package structure, data flow, renderer contracts, and the load-bearing decisions made on 2026-05-01 in the alignment session that preceded the audio + haptic integration. It supersedes `app/src/main/java/com/example/tactilelens/ui/INTEGRATION_GUIDE.md`, which is the original frontend-team integration note and remains valid only as a description of the pre-integration UI shape.
> **Audience.** Anyone joining TactileLens engineering, the agent doing the integration work, the ML team needing to know the contract surface, the frontend team needing to know what's in their column vs ours.
> **Last updated.** 2026-05-01.

---

## 1. Purpose

TactileLens is an on-device Android application that translates visual texture (a photo of a real-world surface) into real-time audio and haptic feedback. A user takes a photo, the app analyzes the texture, and a swipe-on-grid surface produces audio and vibration that approximate touching that surface in the real world.

The system has three load-bearing concerns:

1. **Vision-to-axes.** A texture-classification model maps a photo to a perceptual axis vector plus a suggested categorical material. The model is operated by the ML team and is a contract surface, not an implementation detail of this app.
2. **Axes-to-feedback.** Given the axis vector and material, the app produces continuous, real-time audio and haptic output that responds to the user's swipe gesture.
3. **The grid as a tactile surface.** The dotted grid in the Results screen is not decorative. It is the gesture surface where audio and haptic fire. Its visual density encodes the material; its dot-crossing events drive the haptic event train.

Every architectural decision in this document serves one of those three concerns.

---

## 2. Architectural principles

These are non-negotiable. They exist to keep the code separable, testable, and free of the "everything ends up in `MainActivity`" failure mode.

1. **Layered.** UI knows about ViewModels. ViewModels know about a composition root. The composition root owns renderers and clients. UI never calls a renderer directly.
2. **Interface-driven at the boundary.** `AudioRenderer`, `HapticRenderer`, and `AnalysisClient` are interfaces. Concrete impls are pluggable. This is what lets us swap the ML mock for a real client without touching the UI, and what lets us add an axis-driven sample blender behind the existing renderer interface without rewriting `ResultsScreen`.
3. **Composition root is explicit.** A single `AppContainer` constructed in `MainActivity.onCreate` owns the lifecycle of renderers and clients. ViewModels receive it via constructor argument. There is no service locator, no global, no DI framework. For a hackathon-scale app, plain constructor wiring is the right amount of structure.
4. **The grid is the UX surface, not an animation.** Touch events on the grid produce typed events (`onDotCross(velocity)`). The grid composable does the spatial detection and throttling; the renderer does the sound/feedback. Spatial logic stays in UI; rendering logic stays in `data/`.
5. **Material-or-axes routing.** When `AnalysisResult.material` is one of the four canonical values, the polished hand-tuned path runs. When it is null, the axis-driven procedural path runs. This single null-check is the entire branching logic for the dynamic-material story.
6. **No spec-implementation drift.** The five-knob audio constants and per-material haptic recipes live in source as named constants and `HapticTuning` data classes. Tuning rounds update those constants directly, not a separate config file. When the team agrees on values at the venue, those values are the values; they are checked into git.

---

## 3. Package structure

```
com.tactilelens.app/
├── MainActivity.kt                        Activity entry point, screen routing only.
├── AppContainer.kt                        Composition root. Owns renderers + analysis client lifecycles.
├── data/
│   ├── audio/
│   │   ├── AudioRenderer.kt               Interface. start / stop / setAxes / setMaterial / onContact.
│   │   └── SamplePackAudioRenderer.kt     Sole audio impl. Native-rate loop + velocity gain (Option A).
│   ├── haptic/
│   │   ├── HapticRenderer.kt              Interface. enable / onGestureStart / onSwipeMove / onSwipeEnd.
│   │   └── CompositionHapticRenderer.kt   Sole haptic impl. VibrationEffect.Composition primitives.
│   ├── analysis/
│   │   ├── AnalysisClient.kt              Interface. suspend analyze(uri: Uri): AnalysisResult.
│   │   └── MockAnalysisClient.kt          Today's impl. Cycles through canonical materials.
│   └── model/
│       ├── Material.kt                    enum WOOD/GLASS/ROCKS/SAND. Null = open vocabulary.
│       ├── TextureAxes.kt                 roughness, flatBumpy, friction, hardness in [0, 1].
│       ├── HapticTuning.kt                Per-material tunable knobs. @Volatile mutable at runtime.
│       ├── HapticRecipe.kt                Wire format for future ML output. Documented hook, not yet wired.
│       └── AnalysisResult.kt              The typed payload that flows from ML to renderers.
└── ui/
    ├── scanner/                           Lifted out of MainActivity for separation.
    │   ├── ScannerScreen.kt
    │   └── ScannerViewModel.kt
    ├── results/
    │   ├── ResultsScreen.kt               Header, stats pager, image, grid container.
    │   ├── ResultsViewModel.kt            Owns AnalysisResult; routes events to renderers.
    │   └── InteractiveGridZone.kt         Extracted from ResultsScreen. Per-material density,
    │                                      dot-crossing detection, throttled callback.
    └── theme/                             Frontend team's territory. Colors, typography, Material3.
        ├── Color.kt
        ├── Theme.kt
        └── Type.kt

app/src/main/assets/audio/
├── sand/loop.wav                          Ported from scaffold. ffmpeg-processed 3 s mono 44.1 kHz.
├── wood/loop.wav                          Future: source from RutgerMuller CC0.
├── glass/loop.wav                         Future: source from mickdow or ani_music.
└── rocks/loop.wav                         Future: source from alegemaate.
```

Specs live at the repo root, not under `app/`:

```
specs/
├── ARCHITECTURE.md                        This document.
├── plans/                                 Phased plans for non-trivial work.
└── handoffs/                              Session handoffs for context preservation.
```

---

## 4. Data model

The typed payload that flows through the app is:

```kotlin
data class AnalysisResult(
    val axes: TextureAxes,      // ML's four perceptual floats
    val material: Material?,    // ML's suggested categorical mapping; null = open vocabulary
    val confidence: Float,      // top-class confidence in [0, 1]
    val label: String,          // raw DTD label for display, e.g. "fibrous"
)

data class TextureAxes(
    val roughness: Float,       // smooth (0) to gritty (1)
    val flatBumpy: Float,       // flat (0) to bumpy (1)
    val friction: Float,        // slippery (0) to sticky (1)
    val hardness: Float,        // soft (0) to hard (1)
)

enum class Material { WOOD, GLASS, ROCKS, SAND }
```

The `material: Material?` nullability is the central architectural fork. It carries the load of the entire dynamic-material story:

- **Non-null** means ML is confident this is one of the four canonical materials. The polished hand-tuned recipe path runs. Audio plays the curated sample loop; haptic fires the per-material signature on touch-down and the per-material drag stream during swipes.
- **Null** means ML is not confident, OR the photo is of something outside the canonical set (a brick, a sweater, leather, anything). The axis-driven procedural path runs. Audio plays an axis-driven blend over a small archetype loop bank. Haptic fires the procedural recipe driven by axis values, with per-tick amplitude jitter and 1D-frequency-axis primitive selection.

`HapticRecipe` (a `List<HapticPrimitive>` wire format) is preserved in the model layer as a documented future hook. The `play(recipe)` method on `HapticRenderer` is the entry point. Neither is wired into the runtime today. This exists so that when the ML team migrates to wire format B (emitting primitive sequences directly), the migration is a one-call swap on the renderer, not a model-layer rewrite.

---

## 5. Audio pipeline

**One renderer impl ships: `SamplePackAudioRenderer`.** The DSP synthesis renderer (`KotlinAudioRenderer` in the scaffold) is not ported; samples-only is the production decision.

### How it works (Option A loop + gain)

The renderer plays a per-material WAV loop at native sample rate with output gain modulated by swipe velocity. There is no pitch shift, no rate scrub, no sample retriggering on contact.

1. On `start()`, walks the `Material` enum and loads `assets/audio/<material>/loop.wav` once into a per-material `FloatArray` via a small RIFF/WAVE parser. Held in memory for the renderer's lifetime.
2. Builds an `AudioTrack` at 44.1 kHz, mono, float PCM, `MODE_STREAM`, `PERFORMANCE_MODE_NONE`. The performance mode forces the legacy AudioFlinger path. Oboe + AAudio MMAP fails to bind on the S25 Ultra; do not change this without verifying on the demo device.
3. A coroutine loops `synthesize(out)` then `track.write(out, BLOCKING)`. The blocking write paces the loop at real-time playback rate.
4. Every `onContact(velocity)` event runs a one-pole symmetric LPF on `swipeVelocity` (tau ~25 ms at the typical Compose event rate) to defeat finger-jitter spikes at the input.
5. In the per-sample render loop:
   - `targetGain = swipeVelocity * GAIN_MAX`. `smoothGain += GAIN_ALPHA * (targetGain - smoothGain)` (gain slew tau ~45 ms; peaks swell, do not slam).
   - Two read pointers, A at `pos`, B at `pos + halfLen`, both advance by exactly 1 sample per iter so they stay phase-locked.
   - Equal-power crossfade: `wA = sqrt(1 - tPhase)`, `wB = sqrt(tPhase)` where `tPhase = 2|phase - 0.5|`. Sum-of-squares = 1. Hides the wrap without the +3 dB loudness bump that constant-gain triangular weights would produce mid-loop.
   - Soft saturator: `out[i] = tanh(mixed * smoothGain * SAT_DRIVE) / SAT_DRIVE`. Smoothly compresses transient peaks.
6. End of every 1024-sample buffer: `swipeVelocity *= DECAY_PER_BUFFER` (0.85, half-life ~100 ms). The lift fadeout when no `onContact` events arrive.

### Tuned constants (carry forward; do not change without device validation)

```kotlin
GAIN_MAX         = 0.25f       // -12 dB output ceiling
GAIN_ALPHA       = 0.00050f    // gain slew tau ~45 ms
VEL_LPF          = 0.7f        // velocity input LPF coefficient
SAT_DRIVE        = 1.8f        // tanh soft saturator drive
DECAY_PER_BUFFER = 0.85f       // half-life ~100 ms on lift
```

### Strategy B fallback (when material is null)

When `AnalysisResult.material` is null, the same renderer plays an **axis-driven blend over a small archetype loop bank**. The archetype banks are a planned addition; for v1 we ship a single neutral archetype (likely a granular sand-or-fabric loop) and let axes modulate gain envelope shape. Full axis-blend is a future enhancement; v1 routes to the neutral archetype to avoid silence on null material.

### Interface

```kotlin
interface AudioRenderer {
    fun start()
    fun stop()
    fun setAxes(axes: TextureAxes)
    fun setMaterial(material: Material?)
    fun onContact(velocity: Float)
}
```

The interface is minimal on purpose. Spatial information (where on the grid the touch is) does not enter the renderer; the grid composable resolves spatial events into `onContact(velocity)` calls.

---

## 6. Haptic pipeline

**One renderer impl ships: `CompositionHapticRenderer`.** It is the renamed-and-ported `AudioCoupledHapticRenderer` from the scaffold; the original name was misleading because Samsung firmware blocks audio-coupled haptics (`HapticGenerator`) on the demo device, so the implementation is purely Composition primitives.

### Two firing modes (both stay)

1. **Gesture start.** `onGestureStart(material, axes, velocity)` fires once per gesture (touch-down, material change, preview tap). Plays a multi-primitive signature recipe: a "stamp" that says "this is what wood feels like" or "this is what sand feels like" in one quick chord.
2. **Drag stream.** `onSwipeMove(material, axes, velocity)` fires *per dot crossing* during a drag (NOT on a fixed timer). The grid composable detects each crossing and calls this method; the renderer fires one primitive per call. This is the architectural change from the scaffold: in the scaffold the drag stream was time-throttled (every 55 ms regardless of motion), here it is event-driven by the grid.

The two-mode design exists because Composition primitives have intrinsic durations and cannot be truly streamed. The drag-stream pattern is "fire short primitives per gesture event, let the actuator's natural decay overlap them."

### Per-material signatures and drag primitives

| Material | Touch-down signature | Drag primitive | Drag amp |
| --- | --- | --- | --- |
| Wood | `THUD 0.55 + 80 ms + TICK 0.30` | `THUD` | 0.60 |
| Glass | `CLICK 0.85 + 60 ms + TICK 0.40` | `TICK` | 0.45 |
| Rocks | `8x LOW_TICK 0.65 @ 60 ms` | `LOW_TICK` | 0.55 |
| Sand | `16x LOW_TICK 0.25 @ 28 ms` | `LOW_TICK` | 0.45 |

These values were tuned on the S25 Ultra in prior sessions and survived multiple iterations. Treat them as the starting line for further venue tuning, not as the final spec. They live in `HapticTuning.kt` as `data class` defaults; the per-material `swipeShape()` function in the renderer reads the primitive-and-amp values.

### Velocity scaling

`velFactor = 0.85 + velocity * 0.15`, clamped to `[0.08, 1.0]`. The narrow `[0.85, 1.0]` range is a deliberate floor: a wider range collapses slow drags below feeling threshold. This was the "haptics feel too subtle" fix from the prior session. Do not widen without device testing.

### Procedural recipe (when material is null)

This is the path that runs for any photo outside the canonical four. v1 includes the improvements from the haptic groundwork plan Phases 2 and 3:

1. **Per-tick amplitude jitter (Wobble pattern).** Each primitive's scale is randomized within a `±0.1` window of the base. `randomScale(base, 0.1f)` clamps to `[0.01, 1.0]`. This is the single largest "feels alive vs feels mechanical" upgrade per Google's haptics-team-published Wobble sample.
2. **1D-frequency-axis primitive selector (Pulsar pattern).** Replaces the hard-bucket `if (sharp && bumpy)` 4-way selector with a continuous frequency-axis 3-way selector:
   - `sharpness > 0.66` → CLICK
   - `sharpness > 0.33` → TICK
   - else → LOW_TICK
   - Where `sharpness = hardness * 0.7 + (1 - flatBumpy) * 0.3`.

The probabilistic-selection variant (CLICK probability = sharpness², TICK = 2·s·(1-s), LOW_TICK = (1-s)²) is documented as a future enhancement in §12. It is the right answer for the swipe-stream case where the user fires 30+ primitives in a 2-second drag and the probability distribution becomes ergodic, but it costs reproducibility (same surface feels slightly different each tap), and v1 trades the smoother axis blend for the cleaner demo.

### Why two-mode and not single-mode

Single-mode designs were tried and failed:

- **Signature only.** Haptic dropped out as soon as the finger moved. Felt like one quick buzz then nothing.
- **Per-MOVE full signature firing.** Composition primitives cancel each other when re-enqueued. A 12-tick train fired 60x per second produced single ticks, not 12-tick trains.

The split (signature on START, lightweight train per dot crossing during MOVE) gives both a clear material identity AND continuous interactive feel.

### Samsung firmware constraints (do not retry)

`HapticGenerator.isAvailable()` returns `false` on S25 Ultra. PWLE and `BasicEnvelopeBuilder` (Android 16) are also blocked. Cirrus CS40L26 hardware supports both; Samsung firmware does not expose them. Composition primitives are the only AOSP wide-band path Samsung leaves accessible. Do not attempt audio-coupled haptics on this device.

### Interface

```kotlin
interface HapticRenderer {
    fun setEnabled(enabled: Boolean)
    fun onGestureStart(material: Material?, axes: TextureAxes, velocity: Float)
    fun onSwipeMove(material: Material?, axes: TextureAxes, velocity: Float)
    fun onSwipeEnd()
    fun stop()
    fun play(recipe: HapticRecipe)        // future hook for ML wire format B; not wired in v1
}
```

---

## 7. The grid as a tactile surface

The `InteractiveGridZone` composable is the user-input surface and the architectural centerpiece of the Results screen. It does three things:

### Per-material visual density

Dot spacing is a function of the active material. The visual grid encodes the surface character before the user touches it:

| Material | Dot spacing | Character |
| --- | --- | --- |
| SAND | ~30 px | Dense, fine grit |
| GLASS | ~40 px | Medium-dense, smooth |
| WOOD | ~60 px | Medium, warm contact |
| ROCKS | ~100 px | Sparse, big bumps |
| Null (axis-driven) | `60 - roughness * 30` px | Rough → dense |

The visual styling (color, dot radius, glow on touch, lerp toward GlowCyan) stays the frontend team's. The single parameter `dotSpacing` flows from material → composable.

### Dot-crossing detection

As the finger moves, the composable tracks pointer position and detects each time the finger crosses within a small radius of a dot center (the "crossing event"). Each crossing fires `onDotCross(velocity)`, which is plumbed by the ViewModel into `audio.onContact(velocity)` and `haptic.onSwipeMove(material, axes, velocity)`.

This is the point where haptic firing becomes event-driven (per dot) rather than time-driven (every 55 ms). The result: the user feels each dot as a discrete tactile bump. The dots gain physical mass.

### 40 ms minimum interval (anti-self-cancellation)

When a fast swipe would produce crossings closer than 40 ms apart, the throttle drops the excess. This protects against the LOW_TICK self-cancellation pattern observed on the S25 Ultra actuator (the SAND lesson from the prior session). The throttle lives in the grid composable, not in the renderer; the renderer should always fire what it is asked to fire.

### Audio is NOT event-driven

Audio remains continuous. The grid feeds `onContact(velocity)` to audio at the same throttled cadence (~12 Hz cap to prevent synth saturation), but audio's per-sample gain envelope smooths into a continuous swell-and-fade that does not align to dot crossings. Audio = "I am touching the surface." Haptic = "I am crossing physical things." Two complementary modalities.

---

## 8. Data flow

```
[User] takes photo
   |
   v
[ScannerScreen] saves to cache, sets imageUri
   |
   v
[ScannerViewModel] launches AnalysisClient.analyze(imageUri)
   |
   v  (suspend)
[AnalysisClient impl] -> ML model -> AnalysisResult
   |
   v
[ScannerViewModel] emits AnalysisResult; navigates to Results
   |
   v
[ResultsViewModel] receives AnalysisResult; calls:
   |  AppContainer.audio.setMaterial(result.material)
   |  AppContainer.audio.setAxes(result.axes)
   |  AppContainer.haptic.onGestureStart(result.material, result.axes, velocity = 0.7)
   v
[ResultsScreen] passes axes + material into InteractiveGridZone via composable params
   |
   v
[InteractiveGridZone] displays per-material grid; user swipes
   |
   v  per dot crossing (throttled at 40 ms)
[InteractiveGridZone] -> onDotCross(velocity)
   |
   v
[ResultsViewModel] -> AppContainer.audio.onContact(velocity)
   |              -> AppContainer.haptic.onSwipeMove(material, axes, velocity)
   v
[Renderers] produce sound + vibration
```

The flow is one-way. UI events flow down to renderers; renderer state does not flow back up to UI (renderers do not emit events; they consume them).

---

## 9. Composition root: AppContainer

```kotlin
class AppContainer(context: Context) {
    val audio: AudioRenderer = SamplePackAudioRenderer(context)
    val haptic: HapticRenderer = CompositionHapticRenderer(context)
    val analysis: AnalysisClient = MockAnalysisClient(context)

    fun start() {
        audio.start()
        haptic.setEnabled(true)
    }

    fun stop() {
        audio.stop()
        haptic.stop()
    }
}
```

Single instance, constructed in `MainActivity.onCreate`, started in `onStart`, stopped in `onStop`. ViewModels receive it as a constructor argument. This is the entire DI story; for an app this size, anything more (Hilt, Koin) is overkill.

When the Strategy B audio archetype-blender is added later, it is a second `AudioRenderer` impl behind the same interface; `AppContainer` selects between them based on runtime configuration. The change does not touch ViewModels or UI.

---

## 10. Locked decisions (from the 2026-05-01 alignment session)

These are the answers to the six questions that gated this architecture. Re-litigating any of them invalidates load-bearing parts of the design.

1. **Strategy: hybrid axis-driven (Strategy B with kinda A polish).** Per-material polish for Wood/Glass/Rocks/Sand. Axis-driven procedural fallback for everything else. `Material?` nullability in `AnalysisResult` is the single routing decision.
2. **ML contract: axes today, recipes later.** ML emits `(axes, material?, confidence, label)`. The renderer derives recipes from those. `HapticRecipe` and `play(recipe)` are kept as documented future hooks for when ML migrates to wire format B.
3. **Audio: samples-only.** The DSP renderer (`KotlinAudioRenderer`) does not come over from the scaffold. One `AudioRenderer` impl ships. Future axis-blender for null-material is a second impl behind the same interface.
4. **Haptic procedural: improved.** Per-tick scale jitter (Wobble) plus 1D frequency-axis primitive selector (Pulsar). The bucketed v1 (no jitter, hard thresholds) is rejected as too mechanical. The probabilistic v3 (sample primitive from `sharpness²` distribution) is documented as a future enhancement.
5. **Grid: per-material density, event-driven, 40 ms throttle.** Visual dot spacing matches material character. Each dot crossing fires `onDotCross(velocity)` → renderer. Cap at 40 ms minimum interval to prevent actuator self-cancellation.
6. **Frontend boundaries.** In bounds for integration: `InteractiveGridZone` (per-material density + crossing detection), `ResultsScreen` parameter list, `MainActivity` wiring, all new files under `data/` and `ui/results/`. Out of bounds: splash, theme, scan reveal animation, camera preview, top-level layout, header, back button, stat pager, dashed border, latency badge, page indicators.
7. **Package: `com.tactilelens.app`.** Renamed from the placeholder `com.example.tactilelens` before integration. Cheaper to do once now than on every new file.

---

## 11. Off-the-table approaches

These have been tried or evaluated and rejected. Re-trying them is a known time sink with documented failure modes.

- **Velocity-modulated rate scrubbing for samples.** Produced "whiplash" / "gunshots" because pitch-shifting recorded foley sounds artificial and crossfading a moving pointer through content with internal transients produces phasing artifacts. Option A (loop + gain only) is the answer.
- **Oboe with AAudio MMAP.** Fails to bind format on S25 Ultra and Galaxy A26. Use `AudioTrack` with `PERFORMANCE_MODE_NONE` (legacy AudioFlinger path).
- **HapticGenerator audio-coupled haptics on Samsung.** Firmware-blocked. Pixel-only path; defer to post-Samsung-API-exposure work.
- **PWLE / `BasicEnvelopeBuilder` on Samsung.** Firmware-blocked.
- **libpd / Kortholt / Pure Data patches.** Abandoned in the scaffold for the same MMAP failure. Do not reintroduce.
- **Per-MOVE firing of full signature recipes.** Composition primitives cancel each other when re-enqueued. Single primitives per crossing, not full chords.
- **Per-MOVE 60-120 Hz audio impulse firing.** Saturates the synth. Throttle to ~12 Hz on the audio path.
- **Time-driven drag stream below 50 ms.** LOW_TICK and similar primitives have intrinsic durations that collide on the S25 Ultra actuator. 40 ms is the floor we use because the grid's spatial throttle is the bottleneck, not the renderer's intrinsic timer.
- **Bundling Wwise or FMOD.** Considered and rejected. Engineering team is small; authoring tool overhead does not pay off without a dedicated audio designer.
- **Per-cell ML inference for grid.** N×N cells = N² inference calls. Too expensive for on-device, and the ML team is not delivering spatial output. Single-zone with one analysis per photo is the demo shape. Spatial extension is post-hackathon.

---

## 12. Open questions and future work

Tracked here so they don't get lost. Each item names the person or condition that unblocks it.

### ML team unblocks these

- **Wire format on the IPC.** Kotlin types are in-process. If ML lives in a separate process / model output / file, do they emit JSON, Proto, or construct the data class directly via TFLite output post-processing in Kotlin?
- **One recipe per photo or multiple?** When ML migrates to wire format B, does each photo get one recipe used for both touch-down and drag-stream, or different recipes per gesture mode?
- **Drag semantics with ML-emitted recipes.** Does the recipe play once-and-stop on each gesture, or loop continuously while the finger is down?
- **Velocity scaling with ML-emitted recipes.** Does the model bake velocity into amplitudes, or does the renderer scale on top?
- **Named-material signatures vs procedural sequences.** Does ML emit different recipe shapes when confident about a canonical material vs uncertain?

### Audio side

- **Source Wood / Glass / Rocks loops.** Sand has a working `loop.wav` already ported. The other three need sourcing per the curated list in `texture-sense-reference/specs/plans/2026-04-30-groundwork-material-contact-audio.md`. Strongest leads: RutgerMuller (Wood), mickdow or ani_music (Glass), alegemaate (Rocks). All CC0 or CC-BY.
- **Strategy B archetype loop bank.** v1 routes null-material to a single neutral archetype loop. Full axis-driven blend across 3-5 archetypes is a future enhancement; defer until v1 ships and proves the routing wires up cleanly.
- **Per-material engine choice.** v1 ships samples for all materials. If a future material works better in DSP (resonator-shaped impacts: a struck bell, a glass clink), swap by selecting a `Map<Material, AudioRenderer>` in `AppContainer` and adding the DSP renderer back behind the same interface.
- **Audio-haptic timing measurement.** Both fire on touch-down and during drag, but their relative timing has not been measured on this device. Google haptics principles call cross-modal sync load-bearing for "premium" feel. Worth instrumenting before pitch.

### Haptic side

- **Probabilistic primitive selection (Strategy C refinement).** Replace the 1D 3-way bucketed selector with sampling from a probability distribution (CLICK = sharpness², TICK = 2·s·(1-s), LOW_TICK = (1-s)²). Smooths the axis-boundary cliff. Right answer for swipe-stream where ergodic distribution becomes the felt character; wrong answer for gesture-start signature where reproducibility matters. If implemented, gate to swipe-stream only.
- **HapticTuning persistence (groundwork plan Phase 6).** Today values reset on app restart. DataStore + JSON serialization preserves across restarts and enables team-tuning workflow (multiple teammates tune separately, compare, lock the winner).
- **Primitive-support detection (groundwork plan Phase 1).** Vendor doc requires `arePrimitivesSupported()` query before composing; partial-support devices fail silently. Non-blocking on S25 Ultra (all primitives supported) but mandatory for any device the app ships beyond the demo.
- **Audio-coupled haptics on Pixel.** Out of scope for this app while the demo device is Samsung. Re-evaluate if the demo is ported to Pixel.

### App side

- **Real `AnalysisClient` impl.** Today `MockAnalysisClient` cycles canonical materials. The real client integrates with the ML team's inference pipeline (TFLite or remote) once the wire format is settled.
- **Per-cell grid.** Single-zone is the v1 demo shape. If a future demo wants "different parts of one photo feel different," extend `InteractiveGridZone` with per-cell `setMaterial` + `setAxes` calls. Same renderer interface; the grid is the only thing that changes.

---

## 13. Build and test workflow

```bash
# Build
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:assembleDebug

# Install + relaunch on demo device
ADB=~/Library/Android/sdk/platform-tools/adb
APK=app/build/outputs/apk/debug/app-debug.apk
$ADB install -r "$APK"
$ADB shell am force-stop com.tactilelens.app
$ADB shell monkey -p com.tactilelens.app -c android.intent.category.LAUNCHER 1

# Logcat (filter to our tags)
$ADB logcat -d --pid=$($ADB shell pidof com.tactilelens.app) | \
  grep -E "TactileLensAudio|TactileLensHaptic|FATAL"
```

Tags: `TactileLensAudio` and `TactileLensHaptic`. Demo device: `R3CXC0803XW` (Samsung Galaxy S25 Ultra). Vibration intensity must be at maximum in Settings → Sound and vibration → Vibration intensity (the default ~50% is easy to miss and makes haptics feel weak).

---

## 14. Sources

This architecture is the synthesis of two prior Claude sessions plus the existing TactileLens frontend. Documents that should be read together with this one:

- `app/src/main/java/com/tactilelens/app/ui/INTEGRATION_GUIDE.md` (frontend team's original integration note; valid for the pre-integration UI shape).
- `../texture-sense-reference/specs/haptics-lab.md` (the lab harness walkthrough plus the "Porting to the main repo" section that drove this architecture).
- `../texture-sense-reference/specs/plans/2026-04-30-groundwork-android-haptic-design.md` (six-phase haptic cleanup plan with vendor-doc citations; v1 of TactileLens lands Phases 2 and 3).
- `../texture-sense-reference/specs/plans/2026-04-30-groundwork-material-contact-audio.md` (audio sample-vs-DSP-vs-granular plan with academic citations; informs the Strategy B archetype-blender path).
- `../texture-sense-reference/specs/handoffs/2026-04-30_1555-material-sample-playback.md` (Option A pivot rationale).
- `~/.claude/projects/-Users-edmond-Projects-TextSense-texture-sense-reference/memory/project_samsung_haptic_apis.md` (Samsung firmware block evidence).
- `~/.claude/projects/-Users-edmond-Projects-TextSense-texture-sense-reference/memory/project_aaudio_kick.md` (AAudio MMAP failure evidence).

Both prior session transcripts live under `~/.claude/projects/-Users-edmond-Projects-TextSense-texture-sense-reference/`.
