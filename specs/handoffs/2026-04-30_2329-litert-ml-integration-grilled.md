---
date: 2026-05-01T06:29:47Z
git_commit: e8c1f2e
branch: frontend-ui
repository: TactileLens
topic: "LiteRT ML integration: 8 architectural decisions locked, plan written, blocked on TFLite conversion"
tags: [handoff, litert, ml-integration, qualcomm-npu, hexagon, hackathon, audio, haptic, plan]
status: in-progress
last_updated: 2026-04-30
type: handoff
---

# Handoff: LiteRT ML Integration — Grilled Plan, Blocked on TFLite

## Task(s)

**Primary:** Integrate the ML team's two-model pipeline (U2Net segmentation + EfficientNet-Lite0 + Linear Head regression) into the TactileLens app via LiteRT CompiledModel API + QNN delegate on Hexagon NPU. Replace `MockAnalysisClient` with `LiteRTAnalysisClient`.

**Status:** Architecture and plan are **complete**. All 8 load-bearing decisions locked through a /grill-me session. Detailed 7-phase implementation plan written and committed at `specs/plans/2026-04-30-litert-ml-integration.md` (1108 lines). **Implementation has not started yet.**

**Blocker:** Encoder + head TFLite files do not exist on any branch. Only ONNX is committed (in `exports.zip` on `origin/haptics_model`). Someone must run the team's `colab_convert_encoder.ipynb` once on Google Colab to produce `efficientnet_lite0.tflite` and `linear_head.tflite`. The user said the teammate is uploading them. Phase 1 of the plan can start without this; Phase 3+ unblocks once the files land.

**Hackathon context:** This is the Qualcomm × Google Developer Hackathon (Apr 30 – May 1, 2026), Track 2 (LiteRT classical models on Snapdragon). Submission closes Friday May 1 at 1:00 PM PT. Judging tie-breaker is **NPU utilization**.

## Critical References

1. **`specs/plans/2026-04-30-litert-ml-integration.md`** — THE plan. 7 phases, locked decisions table at top. Read this first.
2. **`HACKATHON.md`** — hackathon constraints, judging criteria, the "newly created" rule risk, decision log.
3. **`ARCHITECTURE.md`** — current architecture source of truth. Renderers, materials, motion-gating, audio + haptic pipeline.

## Recent Changes

The session started with five materials shipped (`WOOD/PAPER/ROCKS/SAND/FABRIC`) and ended with a grill-me + plan. Direct code changes in this session:

**Earlier in the session (committed and pushed before /compact):**
- `app/src/main/assets/audio/sand/loop.wav` — re-processed with highpass 1200 Hz + 6 kHz hiss boost + lower output target so it sits in a different frequency band from rocks.
- `app/src/main/assets/audio/rocks/loop.wav` — re-processed from raw with lighter compression (ratio 2.5 vs 8) + 600 Hz mid-low boost; preserves transient impacts.
- `app/src/main/java/com/tactilelens/app/data/haptic/CompositionHapticRenderer.kt:118-181` — major refactor. Smooth materials (wood, paper) keep single primitive per crossing; high-friction materials (fabric, rocks, sand) now fire 60–80 ms `VibrationEffect.createWaveform` bursts per crossing for resistance feel. Bursts overlap-cancel during fast drag. Wood is QUICK_RISE not THUD.
- `app/src/main/java/com/tactilelens/app/data/model/HapticTuning.kt` — bumped haptic amplitudes 25–50% across all materials (commit `1041c95`); GLASS replaced with PAPER (commit `d06f2b9`); FABRIC added (commit `ef96618`).
- `app/src/main/assets/audio/paper/loop.wav` — Pixabay "crumping paper" by liecio, ffmpeg-processed (commit `d06f2b9`).
- `specs/plans/2026-04-30-litert-ml-integration.md` — new file, the plan (commit `e8c1f2e`).

**Session-end git state:** working tree clean except `local.properties` (teammate-path issue, deliberately excluded from every commit).

## Learnings

### Locked decision log (DO NOT re-litigate without surfacing in chat first)

| # | Decision | Outcome |
|---|---|---|
| Q1 | Contract | Accept ML team's `[rough, hard, friction, density]` as-is. Force `density → flatBumpy`. |
| Q2 | Scope | 2 logical models on NPU (U2Net + EfficientNet pipeline = encoder + head). |
| Q3 | API | LiteRT CompiledModel API + QNN delegate. **NOT** Interpreter+NNAPI. |
| Q4 | Compile | JIT (ship .tflite). AOT via AI Hub reserved for Phase 7 polish. |
| Q5 | Branch | `frontend-ui`. Defer main merge until after integration is proven. |
| Q6 | Glass | Re-add `Material.GLASS` (6 canonicals). Audio TBD per Phase 6. |
| Q7 | `map_primitives()` | Use ONLY for null/open-vocab gesture-start signature. Canonicals keep hand-tuned recipes. Drag stream unchanged. |
| Q8 | Latency UI | Persistent `"NPU · 87 ms"` pill on Results screen, always on. |

### Critical research findings

- **The ML team's helpers use the WRONG API for the hackathon.** They wrote `org.tensorflow.lite.Interpreter` + `useNNAPI = true` (legacy Interpreter API + NNAPI delegate, both deprecated). The hackathon AMA explicitly said "we are shifting from interpreter to compile model API." We rewrite the inference layer in our package. Their helpers are reference for shapes/preprocessing, NOT code to port.
- **Qualcomm QNN delegate is the recommended path.** Deps: `com.qualcomm.qti:qnn-runtime:2.34.0` + `com.qualcomm.qti:qnn-litert-delegate:2.34.0` + `com.google.ai.edge.litert:litert:1.0.x`.
- **`colab_convert_encoder.ipynb` does NOT use AI Hub.** It's a vanilla `onnx2tf` conversion. AI Hub is a separate optional optimization step. For our JIT path, the basic Colab notebook is enough.
- **Density vs flatBumpy mismatch is real.** ML team's "density" is "sparse features → dense features." Our `flatBumpy` is "flat → bumpy." We force the mapping per Q1-D and accept imperfect rendering on edge cases — the hand-tuned canonical recipes mask it for the 6 known materials.
- **System vibration intensity blocked us earlier.** `dumpsys vibrator_manager` showed `TOUCH = MEDIUM` (factor 1.0). Bumping to HIGH gives +20%. The user has to do this in Settings — `adb settings put` is intercepted by Samsung. Tell the next session to verify before re-tuning amplitudes.
- **Continuous-waveform haptic was a wrong turn.** Tried using `createWaveform` looped on touch-down with motion-stopping cancellation; broke motion-gating ("haptics fires constantly regardless of motion"). Reverted to per-crossing model + per-crossing waveform bursts for high-friction materials (the working design). **Don't try continuous waveforms again** unless the user explicitly asks.
- **Audio synthesis for glass was rejected.** User tried both pink-noise + bandpass and white-noise + bandpass synth versions; called both "horrible." Glass audio must come from a real foley clip (CC0). Path forward: same workflow as Pixabay paper sourcing.
- **Freesound CDN rate-limits aggressively.** My server-side IP got blocked after rapid requests; HTTP 000 timeouts. WebFetch also got blocked. Pixabay 403s WebFetch. User had to download the paper clip manually. The next session should be aware that direct CDN fetches from Anthropic infrastructure are unreliable for these CDNs.
- **The existing 7-second cosmetic delay** in `MainActivity.kt:163-177` is for the scan-reveal animation. Real LiteRT inference will be ~50-500 ms. Delay can be dropped post-integration if the demo doesn't need the animation, but this is not on the locked-decision list.

### Existing architecture (preserved)

- 6 commits ahead of where the session started. All pushed to `origin/frontend-ui`:
  ```
  e8c1f2e Add LiteRT ML integration plan (specs/plans/)
  d8ea081 Per-crossing resistance bursts + sand/rocks audio differentiation
  d06f2b9 Replace GLASS with PAPER as the 5th canonical material
  1041c95 Boost haptic amplitudes 25-50 percent across all materials
  38033ce Source real CC0 audio loops for wood, glass, rocks, fabric
  ef96618 Add FABRIC as the 5th canonical material
  f40b8cf Add HACKATHON.md as bootstrap context for hackathon submission
  ```
- The renderer architecture is **locked**. Per-crossing motion-gated model. `AudioRenderer.onContact(velocity)` for audio, `HapticRenderer.onSwipeMove(material, axes, velocity)` for haptic. Smooth materials → single primitive. High-friction materials → resistance burst.

## Artifacts

**Plan:**
- `specs/plans/2026-04-30-litert-ml-integration.md` — 1108-line implementation plan with 7 phases, automated + manual verification per phase.

**Bootstrap docs (read before any code):**
- `HACKATHON.md` (15 KB) — hackathon rules + judging.
- `ARCHITECTURE.md` (31 KB) — code architecture.

**ML team's deliverables (already on remote, not in our `frontend-ui` working tree yet):**
- `app/src/main/assets/u2net_optimized.tflite` — exists on `origin/main`. Pull it via `git checkout origin/main -- app/src/main/assets/u2net_optimized.tflite` in Phase 1.
- `exports.zip` (12.5 MB, on `origin/haptics_model`) — contains the ONNX models, the conversion notebook, and `map_primitives.py`.
- `modeloutput.md` (on `origin/haptics_model`) — model contract spec. Centroid table is in the plan.

**Local extracted files (in `/tmp`, will not survive a reboot):**
- `/tmp/exports.zip`, `/tmp/efficientnet_lite0.onnx`, `/tmp/linear_head.onnx`, `/tmp/colab_convert_encoder.ipynb`, `/tmp/map_primitives.py`.
- `/tmp/audio_attempts/` and `/tmp/audio_candidates/` — earlier audio experiment scratch dirs, can be deleted.

**Files to be created in Phase 1 (from plan §Phase 1):**
- `app/src/main/java/com/tactilelens/app/data/analysis/MaterialCentroids.kt` (new)
- `app/src/main/java/com/tactilelens/app/data/analysis/PrimitiveMapper.kt` (new — Kotlin port of `map_primitives.py`)

**Files to be modified in Phase 1:**
- `app/src/main/java/com/tactilelens/app/data/model/AnalysisResult.kt` — add `backendLabel`, `inferenceLatencyMs`, `primitiveWeights` fields.
- `app/src/main/java/com/tactilelens/app/data/model/Material.kt` — re-add `GLASS`.
- `app/src/main/java/com/tactilelens/app/data/analysis/MockAnalysisClient.kt` — populate new fields with mock data + add GLASS case.
- `app/src/main/java/com/tactilelens/app/ui/results/ResultsScreen.kt` — restore the `"NPU · 87 ms"` pill (was wiped during the package rename).
- `app/build.gradle.kts` — add LiteRT + QNN deps.

**Files to be created in Phase 2 (U2Net pilot):**
- `app/src/main/java/com/tactilelens/app/data/analysis/LiteRTSession.kt` (new — thin wrapper around `CompiledModel`)
- `app/src/main/java/com/tactilelens/app/data/analysis/U2NetSegmenter.kt` (new)

**Files to be created in Phase 4 (the headline swap):**
- `app/src/main/java/com/tactilelens/app/data/analysis/LiteRTAnalysisClient.kt` (new — replaces `MockAnalysisClient` in `AppContainer`)

## Action Items & Next Steps

In priority order:

### Immediate (resume opener)

1. **Read the three critical references** (HACKATHON.md, ARCHITECTURE.md, specs/plans/2026-04-30-litert-ml-integration.md). The decision log + plan have everything needed to proceed without re-deriving.
2. **Check git status:** confirm `frontend-ui` is at `e8c1f2e` and clean (except `local.properties`).
3. **Check for the missing TFLite files** before starting any work:
   ```bash
   git fetch --all --prune
   git ls-tree -r --name-only origin/haptics_model | grep tflite
   ```
   If `efficientnet_lite0.tflite` and `linear_head.tflite` exist on any branch (likely a new commit on `haptics_model`), pull them. If not, Phase 1 still proceeds without them.

### Phase 1 — Prep work (TFLite-independent, can start immediately)

Per plan §Phase 1, in order:
1. Add LiteRT + QNN dependencies to `app/build.gradle.kts`. Pin to `1.0.x` of `com.google.ai.edge.litert:litert`. Verify gradle sync.
2. Pull `u2net_optimized.tflite` from `origin/main` into our `assets/`.
3. Re-add `Material.GLASS` to the enum.
4. Extend `AnalysisResult` with `backendLabel`, `inferenceLatencyMs`, `primitiveWeights`.
5. Create `MaterialCentroids.kt` with the 6-material centroid table from `modeloutput.md`. Threshold = 0.30.
6. Create `PrimitiveMapper.kt` (Kotlin port of `map_primitives.py`).
7. Update `MockAnalysisClient` to populate new fields + GLASS case.
8. Restore the `"NPU · 87 ms"` pill in `ResultsScreen` (wired to `result.backendLabel` and `result.inferenceLatencyMs`).
9. Build + install + verify pill shows `"MOCK · 1500 ms"` and GLASS appears in dropdown. Commit.

### Phase 2 — U2Net LiteRT pilot

After Phase 1 verification. Per plan §Phase 2:
- Create `LiteRTSession.kt` and `U2NetSegmenter.kt`.
- Wire `U2NetSegmenter` into `AppContainer` lifecycle.
- Add temporary instrumentation in `MainActivity.kt:163` to run U2Net on the captured photo and log the result before falling through to `MockAnalysisClient.analyze()`.
- **Verify in logcat that QNN delegate binds to NPU** — falling back to GPU/CPU silently breaks the entire submission story. If it falls back, debug before Phase 3.

### Phase 3 — TFLite conversion (procedural, parallel-able)

Run `/tmp/colab_convert_encoder.ipynb` on Google Colab. Drop outputs into `app/src/main/assets/`. Commit. Push.

### Phase 4 — `LiteRTAnalysisClient` swap (the headline)

Per plan §Phase 4. Write the class, swap in `AppContainer`, remove Phase 2 instrumentation. Test all 6 canonicals + 1 open-vocab photo on the device.

### Phase 5, 6, 7

Per plan. Phase 5 wires `map_primitives()` into the null path. Phase 6 adds GLASS audio + haptic. Phase 7 is optional AOT polish.

### Hackathon submission concerns (out of scope for this session, but track)

- The "newly created" rule (HACKATHON.md §5) is unresolved. `frontend-ui` has commits before noon PT today. Lead must clarify with `support@devpost.com` or accept the looser interpretation.
- Eventually `frontend-ui` must merge to `main` for submission. Out of scope for this plan; do as separate operation after Phase 4 ships.

## Other Notes

- **The teammate is converting the TFLite files** (per the user's last message before /compact). Check `origin/haptics_model` and look for new commits on first resume — they may have pushed before this handoff lands in the next session.
- **Auto mode is on.** The user prefers action over planning, minimal interruptions. Don't re-grill the locked decisions; trust the plan.
- **The user gets frustrated easily on subjective haptic/audio quality issues.** When they say something feels wrong, take it at face value and revert/iterate fast. Don't over-explain or argue. The continuous-waveform misfire earlier in the session is a cautionary tale.
- **`local.properties` is dirty in working tree** because it points at the user's macOS Android SDK path; the upstream repo had a teammate's path. Never `git add local.properties`.
- **Renderer architecture (commit `d8ea081`) is the locked baseline.** Any changes to per-crossing model, motion-gating, or resistance bursts need explicit user approval. The plan does not touch these.
- **Vibration intensity in Settings → Sound and vibration** must be at MAX on the demo device. Verify with `adb shell dumpsys vibrator_manager | grep TOUCH` — should read `TOUCH = HIGH` not `MEDIUM`. If the user reports "haptics feel weak," check this BEFORE re-tuning amplitudes.
- **System reminders during the session** mentioned the latency UI was wiped during the package rename. The plan §Phase 1 step 8 restores it. Don't recreate from scratch — model after the prior commit (search git log for `2a9213a`).
- **5 remote branches**: `main` (ML team), `haptics_model` (ML team's models), `frontend-ui` (us), `haptic-audio-integration` (older us), `HEAD → main`. Other branches will likely appear when the team pushes more work.
