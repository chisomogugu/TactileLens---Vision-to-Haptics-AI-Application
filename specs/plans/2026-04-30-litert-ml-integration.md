# LiteRT ML Integration Plan

> **Created:** 2026-04-30 (hackathon Day 1, mid-afternoon)
> **Author/owner:** Lead (Eddy / 2bTwist)
> **Status:** Approved — all architectural decisions locked through grill-me session before writing.

## Overview

Replace `MockAnalysisClient` with a real `LiteRTAnalysisClient` that runs two on-device ML models (U2Net for background segmentation + EfficientNet-Lite0 + Linear Head pipeline for 4-axis texture regression) on the Snapdragon Hexagon NPU via the LiteRT CompiledModel API + QNN delegate. The result populates `AnalysisResult` with predicted texture axes, classified canonical material, backend label, inference latency, and the 8-primitive weight vector for the open-vocabulary haptic path.

The renderer architecture (audio + haptic) does not change. The only swap is inside `AppContainer.analysis`.

## Current State Analysis

**Repository state (frontend-ui branch as of `d8ea081`):**

- `AppContainer.kt` constructs `MockAnalysisClient(context)` and wires it as `val analysis: AnalysisClient`.
- `MockAnalysisClient.analyze(uri)` cycles 5 canonical materials with hardcoded axes and a 1.5 s suspend delay.
- `AnalysisResult` data class has 4 fields: `axes`, `material`, `confidence`, `label`. **No** backend/latency/primitive-weights fields yet.
- `Material` enum has 5 values: `WOOD, PAPER, ROCKS, SAND, FABRIC`. **`GLASS` was removed** earlier and must be re-added.
- `MainActivity.kt:163` has `LaunchedEffect(imageUri)` that calls `container.analysis.analyze(captured)` in parallel with a 7 s scan-reveal animation, then waits for both before navigating to Results.
- Renderers (`SamplePackAudioRenderer`, `CompositionHapticRenderer`) consume `AnalysisResult` via `audio.setMaterial`, `audio.setAxes`, `haptic.onGestureStart`, `haptic.onSwipeMove`, `haptic.onSwipeEnd`. They are unchanged by this work.
- The latency UI badge from a previous commit was wiped during the package rename. Currently zero references to `latency` or `backend` in `app/src/main/java/com/tactilelens/app/`.

**ML team deliverables (across `origin/main` and `origin/haptics_model`):**

- `U2NetModel/u2net_small.tflite` and `app/src/main/assets/u2net_optimized.tflite` (`origin/main`) — U2Net segmentation model, NPU-optimized via Qualcomm AI Hub. **TFLite ready, can be used directly.**
- `exports.zip` (`origin/haptics_model`) contains:
  - `efficientnet_lite0.onnx` (13.5 MB) — image encoder
  - `linear_head.onnx` (30 KB) — 4-axis regression head
  - `colab_convert_encoder.ipynb` — ONNX → TFLite conversion notebook (uses `onnx2tf`, no AI Hub)
  - `map_primitives.py` — Python rule mapping `[rough, hard, friction, density]` → 8 weighted Composition primitives
- `modeloutput.md` (`origin/haptics_model`) — full contract spec with input/output shapes, axis definitions, and centroid table for 6 trained materials (Rock, Glass, Sand, Cloth, Wood, Paper).
- ML team's helper code on `origin/main` (`SegmentationHelper.kt`, `TextureClassificationHelper.kt`, `AudioHelper.kt`, `HapticsHelper.kt`, `CameraScreen.kt`) lives in package `com.example.tactilelens` and uses the **legacy `Interpreter` API + NNAPI delegate**. **Not adopted** — this plan replaces them with our own LiteRT CompiledModel implementation in `com.tactilelens.app.data.analysis`.

**Practical blocker:** The two encoder + head TFLite files do not exist on any branch yet. Someone must run `colab_convert_encoder.ipynb` once on Google Colab (or local Python) to produce them. Phase 1 of this plan does prep work that is independent of the missing TFLite files; Phase 3 unblocks once those files are committed.

## Desired End State

After this plan completes, the app on the S25 Ultra:

1. Loads two on-device ML models (U2Net + EfficientNet pipeline) at app start via LiteRT `CompiledModel.create()` with the QNN delegate explicitly bound to the Hexagon NPU.
2. On each photo capture, runs U2Net (segmentation foreground extraction) → EfficientNet encoder → Linear Head, all on the NPU, returns 4 texture axes and 8 primitive weights in <500 ms total inference time.
3. Centroid-distance-classifies axes against ML team's 6-material centroid table; returns `Material.WOOD/PAPER/ROCKS/SAND/FABRIC/GLASS` if min distance ≤ 0.30, otherwise `Material? = null` (open-vocabulary path).
4. Displays a persistent `"NPU · 87 ms"` pill on the Results screen showing the actual delegate that ran inference and the wall-clock latency.
5. For null-material gestures, fires the `map_primitives()`-derived 8-primitive Composition as the touch-down signature instead of our hand-tuned procedural recipe (open-vocab handoff to ML team's design).
6. All 6 canonical materials retain their existing hand-tuned haptic + audio recipes. Drag stream remains motion-gated and unchanged.

**Verification (end-to-end manual smoke test, post-Phase 4):**
- Launch app, scan a wood surface, confirm Results screen reads `"NPU · <ms>"` and Material badge shows "Wood."
- Scan a leaf or rug or arbitrary surface (not in the canonical set), confirm Material reads "Other" (or empty), axes look reasonable, swipe produces axis-driven feedback rather than wood/paper/etc. recipe.
- Verify in `adb logcat` that QNN delegate binds successfully (look for `Hexagon` or `QnnHtp` tags during `CompiledModel.create()`).

### Key Discoveries

- `MainActivity.kt:171` already runs analysis in parallel with the 7 s scan animation via `coroutineScope { async { ... } }`. We do not need to change this control flow — `LiteRTAnalysisClient.analyze()` simply replaces `MockAnalysisClient.analyze()` behind the same `AnalysisClient` interface (defined at `com.tactilelens.app.data.analysis.AnalysisClient`).
- `AppContainer.start()` is already called in `MainActivity.onStart`; we will hook ML model loading into the same lifecycle (load on `start`, free on `stop`).
- `dumpsys vibrator_manager` confirmed earlier in the day that the device has full NPU + amplitude control. No HAL-level surprises expected.
- ML team's 6 trained materials match our 6 canonicals exactly after Glass is re-added (decision Q6-B). One-to-one centroid mapping.
- ML team's `density` axis is **not** the same as our `flatBumpy` axis (sparse-features vs flat). We force the mapping per locked decision Q1-D and accept the imperfect demo behavior; the hand-tuned haptic recipes for 5 of the 6 materials make this invisible to the user, and the procedural fallback handles edge cases.

## What We're NOT Doing

- **Not porting the ML team's existing helpers** (`SegmentationHelper.kt`, `TextureClassificationHelper.kt`, `AudioHelper.kt`, `HapticsHelper.kt`, `CameraScreen.kt`). They use legacy `Interpreter + NNAPI`, package `com.example.tactilelens`, and conflict with our motion-gated renderer. We rewrite the inference layer cleanly using LiteRT CompiledModel API + QNN delegate in our package `com.tactilelens.app.data.analysis`.
- **Not retraining the model.** We accept the ML team's existing axis contract `[rough, hard, friction, density]` as-is per locked decision Q1-D, with `density → flatBumpy` mapping in our adapter.
- **Not running the model on every camera frame.** Single-shot inference per photo capture, exactly as `MockAnalysisClient` does today. The renderer is event-driven from per-dot crossings, not from continuous ML output.
- **Not adopting AOT compilation in v1.** We ship plain TFLite and let LiteRT JIT-compile to QNN at first run (locked decision Q4-B). AOT via Qualcomm AI Hub is a Phase 7 (optional) polish if there is time before submission.
- **Not changing the renderer interfaces or implementations.** `AudioRenderer` and `HapticRenderer` keep their current contracts. The only consumer-side change is that `AnalysisResult` grows three new optional metadata fields.
- **Not touching the ML team's main branch directly.** Integration lands on `frontend-ui` per locked decision Q5-A; we deal with the eventual main merge as a separate dedicated PR after the integration is proven.

## Implementation Approach

Five phases, two of which can start in parallel (Phase 1 prep is independent of the missing TFLite files). Each phase ends with both automated and manual verification before the next begins. The final wiring step (Phase 4) is the only "user-visible" change — everything before it is pure scaffolding so the swap is one line.

The plan respects the locked architectural decisions:

| # | Decision | Outcome |
|---|---|---|
| Q1 | Contract | Accept ML team's `[rough, hard, friction, density]`, force `density → flatBumpy` in adapter |
| Q2 | Scope | 2 logical models on NPU (U2Net + EfficientNet pipeline); 3 inference passes total |
| Q3 | API | LiteRT CompiledModel API + explicit QNN delegate (NOT Interpreter+NNAPI) |
| Q4 | Compile | JIT (ship `.tflite`, on-device compile at first run); AOT polish reserved for Phase 7 |
| Q5 | Branch | Land on `frontend-ui`, defer main merge |
| Q6 | Glass | Re-add `Material.GLASS`; 6 canonicals total |
| Q7 | `map_primitives()` | Use **only** for null/open-vocab gesture-start signature; canonicals keep hand-tuned recipes; drag stream unchanged |
| Q8 | Latency UI | Persistent `"NPU · 87 ms"` pill on Results screen, always on |

---

## Phase 1: Prep Work (TFLite-independent)

### Overview

All scaffolding that does not require the missing encoder + head TFLite files. Can start immediately. After this phase, the codebase is shaped to receive the LiteRT inference call as a one-line swap in Phase 4.

### Changes Required

#### 1. Build dependencies

**File**: `app/build.gradle.kts`

Add LiteRT + Qualcomm QNN delegate dependencies. Existing dependencies block stays unchanged.

```kotlin
dependencies {
    // ... existing deps unchanged

    // LiteRT runtime + Qualcomm Hexagon NPU delegate (decision Q3-A)
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    implementation("com.google.ai.edge.litert:litert-support:1.0.1")
    implementation("com.qualcomm.qti:qnn-runtime:2.34.0")
    implementation("com.qualcomm.qti:qnn-litert-delegate:2.34.0")
}
```

If exact version `1.0.1` is unavailable at sync time, pin to the latest stable in the `1.0.x` line. Document the resolved version in the commit message.

#### 2. U2Net model file

**File**: `app/src/main/assets/u2net_optimized.tflite`

Pull from `origin/main` into our `frontend-ui` branch. This is the AI Hub-optimized U2Net (NPU-friendly) the ML team already produced.

```bash
git checkout origin/main -- app/src/main/assets/u2net_optimized.tflite
git add app/src/main/assets/u2net_optimized.tflite
```

#### 3. Re-add `Material.GLASS`

**File**: `app/src/main/java/com/tactilelens/app/data/model/Material.kt`

Re-add the GLASS enum value. Do not commit GLASS audio yet (deferred to Phase 6).

```kotlin
enum class Material(val display: String) {
    WOOD("Wood"),
    PAPER("Paper"),
    ROCKS("Rocks"),
    SAND("Sand"),
    FABRIC("Fabric"),
    GLASS("Glass"),
}
```

#### 4. Extend `AnalysisResult`

**File**: `app/src/main/java/com/tactilelens/app/data/model/AnalysisResult.kt`

Add three new optional fields. Defaulting them to sensible values means existing code continues to compile.

```kotlin
data class AnalysisResult(
    val axes: TextureAxes,
    val material: Material?,
    val confidence: Float,
    val label: String,
    /** Which delegate executed inference: "NPU", "GPU", "CPU", or "MOCK". */
    val backendLabel: String = "MOCK",
    /** Wall-clock inference time in milliseconds. */
    val inferenceLatencyMs: Long = 0L,
    /**
     * 8-element weighted-primitive vector from `map_primitives()`,
     * summing to 1.0. Populated by `LiteRTAnalysisClient`. Used by the
     * haptic renderer ONLY for null-material (open-vocab) gesture-start
     * signature per locked decision Q7-B. Null when not available.
     */
    val primitiveWeights: FloatArray? = null,
)
```

#### 5. ML team's centroid table

**File** (new): `app/src/main/java/com/tactilelens/app/data/analysis/MaterialCentroids.kt`

Hardcoded 6-material centroid table from `modeloutput.md`. Used by the centroid-distance classifier in Phase 4.

```kotlin
package com.tactilelens.app.data.analysis

import com.tactilelens.app.data.model.Material
import com.tactilelens.app.data.model.TextureAxes
import kotlin.math.sqrt

/**
 * 6-material centroid table from the ML team's training set
 * (modeloutput.md). Axes are stored after density → flatBumpy
 * mapping (Q1-D) so they line up with our [TextureAxes] type.
 *
 * roughness ↔ rough, hardness ↔ hard, friction ↔ friction,
 * flatBumpy ↔ density.
 */
object MaterialCentroids {

    private val centroids: Map<Material, TextureAxes> = mapOf(
        Material.ROCKS  to TextureAxes(roughness = 0.85f, flatBumpy = 0.33f, friction = 0.70f, hardness = 0.94f),
        Material.GLASS  to TextureAxes(roughness = 0.05f, flatBumpy = 0.05f, friction = 0.08f, hardness = 0.96f),
        Material.SAND   to TextureAxes(roughness = 0.57f, flatBumpy = 0.89f, friction = 0.63f, hardness = 0.23f),
        Material.FABRIC to TextureAxes(roughness = 0.35f, flatBumpy = 0.61f, friction = 0.73f, hardness = 0.10f),
        Material.WOOD   to TextureAxes(roughness = 0.31f, flatBumpy = 0.27f, friction = 0.43f, hardness = 0.78f),
        Material.PAPER  to TextureAxes(roughness = 0.12f, flatBumpy = 0.74f, friction = 0.28f, hardness = 0.26f),
    )

    /** Euclidean distance threshold below which axes are classified as canonical. */
    const val CLASSIFICATION_THRESHOLD: Float = 0.30f

    /**
     * Returns nearest canonical material if min distance ≤ threshold,
     * otherwise null (open-vocabulary path).
     */
    fun classify(axes: TextureAxes): Pair<Material?, Float> {
        val (best, dist) = centroids.minByOrNull { (_, c) -> distance(axes, c) }!!
            .let { it.key to distance(axes, it.value) }
        return if (dist <= CLASSIFICATION_THRESHOLD) best to dist else null to dist
    }

    private fun distance(a: TextureAxes, b: TextureAxes): Float = sqrt(
        (a.roughness - b.roughness) * (a.roughness - b.roughness) +
        (a.flatBumpy - b.flatBumpy) * (a.flatBumpy - b.flatBumpy) +
        (a.friction - b.friction)   * (a.friction - b.friction) +
        (a.hardness - b.hardness)   * (a.hardness - b.hardness)
    )
}
```

#### 6. Port `map_primitives.py` → Kotlin

**File** (new): `app/src/main/java/com/tactilelens/app/data/analysis/PrimitiveMapper.kt`

Pure-math port of the ML team's Python rule. Outputs an 8-element float array summing to 1.0, in the order `[TICK, LOW_TICK, CLICK, THUD, SLOW_RISE, QUICK_RISE, QUICK_FALL, SPIN]`.

```kotlin
package com.tactilelens.app.data.analysis

import com.tactilelens.app.data.model.TextureAxes

/**
 * Port of the ML team's `map_primitives.py` rule. Converts 4 axes
 * into 8 normalized weights for Android Composition primitives.
 *
 * Order matches `VibrationEffect.Composition.PRIMITIVE_*` constants:
 *   [0] TICK         rough + hard + dense
 *   [1] LOW_TICK     rough + soft + dense
 *   [2] CLICK        hard + dense + smooth
 *   [3] THUD         soft + sparse
 *   [4] SLOW_RISE    smooth + soft + grippy
 *   [5] QUICK_RISE   hard + smooth
 *   [6] QUICK_FALL   slippery + smooth
 *   [7] SPIN         fixed at 0.05 (cannot be inferred from image)
 *
 * Used ONLY for the null-material gesture-start signature path per
 * locked decision Q7-B. Canonical materials use hand-tuned recipes.
 */
object PrimitiveMapper {

    fun map(axes: TextureAxes): FloatArray {
        // Translate to ML team's variable names for fidelity to map_primitives.py.
        val rough = axes.roughness
        val hard = axes.hardness
        val friction = axes.friction
        val density = axes.flatBumpy  // Q1-D: density was mapped here

        // Rules from map_primitives.py
        val tick      = rough * hard * density
        val lowTick   = rough * (1f - hard) * density
        val click     = hard * density * (1f - rough)
        val thud      = (1f - hard) * (1f - density)
        val slowRise  = (1f - rough) * (1f - hard) * friction
        val quickRise = hard * (1f - rough)
        val quickFall = (1f - friction) * (1f - rough)
        val spin      = 0.05f

        // Normalize to sum to 1.0 (the ML team's formula post-normalizes).
        val raw = floatArrayOf(tick, lowTick, click, thud, slowRise, quickRise, quickFall, spin)
        val sum = raw.sum()
        return if (sum > 0f) FloatArray(raw.size) { raw[it] / sum } else raw
    }
}
```

**Reference**: `/tmp/map_primitives.py` (extracted from `exports.zip` on `origin/haptics_model`). The exact rule from the file:

```
TICK         = rough * hard * density
LOW_TICK     = rough * (1 - hard) * density
CLICK        = hard * density * (1 - rough)
THUD         = (1 - hard) * (1 - density)
SLOW_RISE    = (1 - rough) * (1 - hard) * friction
QUICK_RISE   = hard * (1 - rough)
QUICK_FALL   = (1 - friction) * (1 - rough)
SPIN         = 0.05
```

#### 7. Update `MockAnalysisClient` to populate the new fields

**File**: `app/src/main/java/com/tactilelens/app/data/analysis/MockAnalysisClient.kt`

So we can build + visually test the latency badge before real ML lands.

```kotlin
override suspend fun analyze(uri: Uri): AnalysisResult {
    delay(1500)
    val material = MATERIAL_CYCLE[counter % MATERIAL_CYCLE.size]
    counter++
    return resultFor(material).copy(
        backendLabel = "MOCK",
        inferenceLatencyMs = 1500L,
        primitiveWeights = PrimitiveMapper.map(resultFor(material).axes),
    )
}
```

Add `Material.GLASS` to `resultFor` and `MATERIAL_CYCLE` with the centroid axes:
```kotlin
Material.GLASS -> AnalysisResult(
    axes = TextureAxes(roughness = 0.05f, flatBumpy = 0.05f, friction = 0.08f, hardness = 0.96f),
    material = Material.GLASS,
    confidence = 0.92f,
    label = "glass",
)
```

#### 8. Restore the latency badge UI

**File**: `app/src/main/java/com/tactilelens/app/ui/results/ResultsScreen.kt`

Re-add the small pill component that was wiped during the package rename. Position it top-right of the captured-image card, similar to how `2a9213a` had it but reading from `result.backendLabel` and `result.inferenceLatencyMs` instead of a hardcoded `"42ms"`.

```kotlin
// Inside ResultsScreen, where the image card lives:
@Composable
private fun BackendLatencyPill(backendLabel: String, latencyMs: Long) {
    Row(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(50)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Small dot indicator (green when NPU, amber otherwise)
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    color = if (backendLabel == "NPU") GlowCyan else Color(0xFFE6A23C),
                    shape = CircleShape
                )
        )
        Text(
            text = "$backendLabel · ${latencyMs} ms",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
```

Wire it into the existing image-card Box with `.align(Alignment.TopEnd).offset(y = (-12).dp, x = 12.dp)` to float just outside the card.

### Success Criteria

#### Automated Verification:
- [ ] Gradle sync completes after dependency update: `./gradlew :app:dependencies | grep -E "litert|qnn"` returns the new deps.
- [ ] App compiles: `./gradlew :app:assembleDebug` returns BUILD SUCCESSFUL.
- [ ] No new compile warnings about unused imports in modified files.
- [ ] APK packages the U2Net model: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep u2net_optimized.tflite` shows the file.

#### Manual Verification:
- [ ] Install on S25 Ultra. App launches without crash.
- [ ] Capture a photo. Results screen loads.
- [ ] Latency pill is visible top-right of the image card. Reads `"MOCK · 1500 ms"` (mock client populates these).
- [ ] Material picker dropdown shows 6 entries including "Glass."
- [ ] Selecting "Glass" plays the existing wood/paper/etc. fallback (Glass audio is intentionally not yet shipped).
- [ ] No regressions in the haptic / audio behavior of the other 5 materials.

**Implementation Note**: After Phase 1 passes both automated and manual verification, **pause for confirmation** before starting Phase 2.

---

## Phase 2: U2Net LiteRT Pilot Integration

### Overview

Validate the LiteRT CompiledModel API + QNN delegate path on the only model file we currently have: `u2net_optimized.tflite`. This is the smallest possible slice that proves the API plumbing works end-to-end. The U2Net result is not yet used by the rest of the pipeline — it produces a segmented bitmap that gets logged but not consumed (yet). When Phase 3 lands the encoder + head TFLite, that segmented bitmap becomes the input to the encoder.

### Changes Required

#### 1. New file: `LiteRTSession.kt`

**File** (new): `app/src/main/java/com/tactilelens/app/data/analysis/LiteRTSession.kt`

Thin wrapper around `CompiledModel` for one model. Owns its own input/output tensor buffers and reports the resolved backend.

```kotlin
package com.tactilelens.app.data.analysis

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable

/**
 * One-model wrapper. Loads a TFLite asset via LiteRT CompiledModel API
 * with QNN delegate priority (Hexagon NPU). Falls back to GPU then CPU
 * automatically if the QNN delegate fails to bind.
 */
class LiteRTSession(
    private val context: Context,
    private val assetName: String,
) : Closeable {

    private val model: CompiledModel
    val backendLabel: String
    private val inputBuffers: List<TensorBuffer>
    private val outputBuffers: List<TensorBuffer>

    init {
        val options = CompiledModel.Options.Builder()
            .setAccelerator(CompiledModel.Accelerator.NPU)  // QNN/Hexagon priority
            .setFallbackAccelerator(CompiledModel.Accelerator.GPU)
            .setFallbackAccelerator(CompiledModel.Accelerator.CPU)
            .build()

        model = CompiledModel.create(context, "file:///android_asset/$assetName", options)
        backendLabel = when (model.activeAccelerator) {
            CompiledModel.Accelerator.NPU -> "NPU"
            CompiledModel.Accelerator.GPU -> "GPU"
            else -> "CPU"
        }
        inputBuffers = model.createInputBuffers()
        outputBuffers = model.createOutputBuffers()

        Log.i(TAG, "LiteRTSession loaded $assetName on $backendLabel; in shapes=${inputBuffers.map { it.shape }}, out shapes=${outputBuffers.map { it.shape }}")
    }

    fun runFloat(inputs: List<FloatArray>): List<FloatArray> {
        require(inputs.size == inputBuffers.size) { "Expected ${inputBuffers.size} input tensors, got ${inputs.size}" }
        for (i in inputs.indices) inputBuffers[i].writeFloat(inputs[i])
        model.run(inputBuffers, outputBuffers)
        return outputBuffers.map { it.readFloat() }
    }

    override fun close() {
        inputBuffers.forEach { it.close() }
        outputBuffers.forEach { it.close() }
        model.close()
    }

    private companion object { private const val TAG = "TactileLensLiteRT" }
}
```

**Note**: The exact `CompiledModel` API surface (e.g. `Accelerator.NPU` enum name, `setFallbackAccelerator` signature) may differ across LiteRT versions; verify against the `1.0.x` release we resolve. Adjust the wrapper to match. The shape of the wrapper (load-once + run-many) does not change.

#### 2. New file: `U2NetSegmenter.kt`

**File** (new): `app/src/main/java/com/tactilelens/app/data/analysis/U2NetSegmenter.kt`

Owns one `LiteRTSession` for U2Net. Takes a 224×224 RGB bitmap, returns a foreground-masked bitmap.

```kotlin
package com.tactilelens.app.data.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.Closeable

class U2NetSegmenter(context: Context) : Closeable {

    private val session = LiteRTSession(context, "u2net_optimized.tflite")
    val backendLabel: String get() = session.backendLabel

    /**
     * Resize bitmap to 320×320, normalize to [0,1], run U2Net, apply
     * resulting alpha mask back to the original-resolution bitmap.
     */
    fun segment(input: Bitmap): Bitmap {
        // Match U2Net's 320×320 expected input (per ML team's SegmentationHelper.kt).
        val resized = Bitmap.createScaledBitmap(input, INPUT_SIZE, INPUT_SIZE, true)
        val rgbInput = bitmapToFloatChw(resized)

        val outputs = session.runFloat(listOf(rgbInput))
        val mask = outputs[0]  // 320×320 alpha values in [0,1]

        // Reapply mask at original resolution
        return applyMask(input, mask, INPUT_SIZE, INPUT_SIZE)
    }

    private fun bitmapToFloatChw(bm: Bitmap): FloatArray {
        // U2Net expects NCHW float32 [0,1] (per modeloutput.md normalization)
        val out = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bm.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        var rIdx = 0
        var gIdx = INPUT_SIZE * INPUT_SIZE
        var bIdx = 2 * INPUT_SIZE * INPUT_SIZE
        for (p in pixels) {
            out[rIdx++] = ((p shr 16) and 0xff) / 255f
            out[gIdx++] = ((p shr 8) and 0xff) / 255f
            out[bIdx++] = (p and 0xff) / 255f
        }
        return out
    }

    private fun applyMask(orig: Bitmap, mask: FloatArray, mw: Int, mh: Int): Bitmap {
        val output = Bitmap.createBitmap(orig.width, orig.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(orig, 0f, 0f, null)
        // For v1: simple thresholded crop. Refine in Phase 7 polish if needed.
        val paint = Paint().apply { color = Color.argb(255, 0, 0, 0) }
        // ... mask blit logic — keep it simple for now
        return output
    }

    override fun close() = session.close()

    private companion object { const val INPUT_SIZE = 320 }
}
```

#### 3. Wire U2Net into `AppContainer` (load on start, free on stop)

**File**: `app/src/main/java/com/tactilelens/app/AppContainer.kt`

Add a lazily-initialized `U2NetSegmenter` field. Do not yet replace `MockAnalysisClient` — that swap is Phase 4.

```kotlin
class AppContainer(context: Context) {
    val audio: AudioRenderer = SamplePackAudioRenderer(context)
    val haptic: HapticRenderer = CompositionHapticRenderer(context)
    val analysis: AnalysisClient = MockAnalysisClient(context)

    /** Lazy so we don't load the ML model unless the user actually scans. */
    private var _segmenter: U2NetSegmenter? = null
    val segmenter: U2NetSegmenter
        get() = _segmenter ?: U2NetSegmenter(context).also { _segmenter = it }

    fun start() {
        audio.start()
        haptic.setEnabled(true)
        // Eagerly load U2Net so first-photo latency is just inference, not model-load.
        segmenter.let { /* trigger lazy init */ }
    }

    fun stop() {
        audio.stop()
        haptic.stop()
        _segmenter?.close()
        _segmenter = null
    }
}
```

#### 4. Smoke-test U2Net on a captured photo

**File**: `app/src/main/java/com/tactilelens/app/MainActivity.kt`

After photo capture, before the existing `container.analysis.analyze(captured)` call, run U2Net once and log the result. This is temporary instrumentation we remove in Phase 4 once U2Net feeds the encoder.

```kotlin
LaunchedEffect(imageUri) {
    val captured = imageUri
    if (captured != null && currentScreen == AppScreen.Scanner) {
        coroutineScope {
            val analysisDeferred = async {
                // PHASE 2 INSTRUMENTATION — remove in Phase 4
                runCatching {
                    val bm = loadBitmapFromUri(context, captured) ?: error("could not load bitmap")
                    val t0 = System.currentTimeMillis()
                    val masked = container.segmenter.segment(bm)
                    val ms = System.currentTimeMillis() - t0
                    Log.i("TactileLensML", "U2Net ran on ${container.segmenter.backendLabel} in ${ms}ms; output ${masked.width}×${masked.height}")
                }.onFailure { Log.e("TactileLensML", "U2Net failed", it) }

                container.analysis.analyze(captured)
            }
            delay(7000)
            analysisResult = analysisDeferred.await()
        }
        currentScreen = AppScreen.Results
    }
}
```

### Success Criteria

#### Automated Verification:
- [ ] App compiles: `./gradlew :app:assembleDebug`
- [ ] APK installs: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- [ ] No native library load errors in `adb logcat | grep -E "(libQnn|libnnapi|UnsatisfiedLink)"`

#### Manual Verification:
- [ ] On S25 Ultra, capture a photo. Results screen still loads (mock axes still drive the renderer).
- [ ] `adb logcat | grep TactileLensML` shows a successful U2Net inference log line: `"U2Net ran on NPU in <ms>ms"` — **NPU specifically**, not GPU or CPU. If it falls back to GPU/CPU we have a delegate-binding problem to fix.
- [ ] Inference latency in the log is < 200 ms (first-run JIT-compile may push first inference to ~1 s; subsequent inferences should be fast).
- [ ] No regressions: dropdown picker, swipe interaction, audio/haptic per-material behavior all still work.

**Implementation Note**: Pause after Phase 2 for confirmation. **If U2Net does not bind to NPU, fix that before Phase 3.** Falling back to GPU/CPU silently invalidates the entire NPU-utilization story we're selling to judges.

---

## Phase 3: TFLite-File Acquisition

### Overview

Unblocking step. Run `colab_convert_encoder.ipynb` once on Google Colab to produce `efficientnet_lite0.tflite` and `linear_head.tflite`. Drop them into `app/src/main/assets/`. Commit.

This phase is procedural, not code-writing. It runs in parallel with Phase 1 and Phase 2 — the moment a teammate has the TFLite files, this phase ends.

### Changes Required

#### 1. Convert ONNX → TFLite

**Procedure** (one-time, by a human):

1. Extract `colab_convert_encoder.ipynb` and the two ONNX files from `exports.zip` on `origin/haptics_model`. The files are already extracted to `/tmp/` on the dev machine (see `/tmp/efficientnet_lite0.onnx`, `/tmp/linear_head.onnx`, `/tmp/colab_convert_encoder.ipynb`).
2. Open `colab_convert_encoder.ipynb` in Google Colab (or VS Code's Colab extension, which connects to Google's Colab cloud).
3. Run cells in order:
   - Cell 1: install `onnx`, `onnxsim`, `onnx2tf`, `ai-edge-litert`. Runtime restarts automatically.
   - Cell 2: upload both `efficientnet_lite0.onnx` and `linear_head.onnx`.
   - Cells 3–6: simplify and convert. Outputs `efficientnet_lite0.tflite` (~5 MB) and `linear_head.tflite` (~30 KB) at the Colab workspace root.
   - Cells 7–8: verify both models load and produce expected shapes (`[1,1280]` then `[1,4]`).
4. Download both TFLite files from the Colab file browser.

#### 2. Commit the TFLite files

**Files** (new):
- `app/src/main/assets/efficientnet_lite0.tflite`
- `app/src/main/assets/linear_head.tflite`

```bash
cp ~/Downloads/efficientnet_lite0.tflite app/src/main/assets/
cp ~/Downloads/linear_head.tflite app/src/main/assets/
git add app/src/main/assets/efficientnet_lite0.tflite app/src/main/assets/linear_head.tflite
git commit -m "Add EfficientNet-Lite0 + Linear Head TFLite (converted from ONNX via colab_convert_encoder.ipynb)"
```

### Success Criteria

#### Automated Verification:
- [ ] Files exist and are valid: `ffprobe`-equivalent doesn't apply here, but `file` returns "data" and size is non-zero:
  ```bash
  file app/src/main/assets/efficientnet_lite0.tflite app/src/main/assets/linear_head.tflite
  ```
- [ ] APK rebuilds and packages the new assets: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "(efficientnet_lite0|linear_head).tflite"`.

#### Manual Verification:
- [ ] Encoder TFLite signature verified in Colab Cell 7: input `[1,224,224,3] float32`, output `[1,1280] float32`.
- [ ] Head TFLite signature verified in Colab Cell 7: input `[1,1280] float32`, output `[1,4] float32`.
- [ ] Cell 8 smoke-test passes (random input → 4 axes in [0,1]).

**Implementation Note**: Pause after Phase 3 for confirmation that the files are committed and pass verification before Phase 4. **Do not proceed to Phase 4 until Phase 3 commits are pushed to `origin/frontend-ui`.**

---

## Phase 4: `LiteRTAnalysisClient` — The Swap

### Overview

Write the real ML pipeline class and replace `MockAnalysisClient` with it in `AppContainer`. This is the moment the app starts running on real ML. Single-line swap at the call site; all the work is in the new class.

### Changes Required

#### 1. New file: `LiteRTAnalysisClient.kt`

**File** (new): `app/src/main/java/com/tactilelens/app/data/analysis/LiteRTAnalysisClient.kt`

Owns three `LiteRTSession`s (U2Net, encoder, head) plus the segmenter from Phase 2. Implements `AnalysisClient.analyze(uri)`.

```kotlin
package com.tactilelens.app.data.analysis

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.tactilelens.app.data.model.AnalysisResult
import com.tactilelens.app.data.model.Material
import com.tactilelens.app.data.model.TextureAxes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

class LiteRTAnalysisClient(private val context: Context) : AnalysisClient, Closeable {

    private val segmenter: U2NetSegmenter by lazy { U2NetSegmenter(context) }
    private val encoder: LiteRTSession by lazy { LiteRTSession(context, "efficientnet_lite0.tflite") }
    private val head: LiteRTSession by lazy { LiteRTSession(context, "linear_head.tflite") }

    override suspend fun analyze(uri: Uri): AnalysisResult = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()

        // Stage 1: load + decode
        val bitmap = loadAndOrientBitmap(uri)

        // Stage 2: U2Net segmentation (Q2-B: 3 inferences total)
        val foreground = segmenter.segment(bitmap)

        // Stage 3: preprocess for encoder (224×224 NHWC, [-1, 1] normalization per modeloutput.md)
        val encoderInput = preprocessForEncoder(foreground)

        // Stage 4: encoder
        val features = encoder.runFloat(listOf(encoderInput))[0]
        check(features.size == 1280) { "Expected encoder output [1,1280], got ${features.size}" }

        // Stage 5: head
        val axesRaw = head.runFloat(listOf(features))[0]
        check(axesRaw.size == 4) { "Expected head output [1,4], got ${axesRaw.size}" }

        // Stage 6: build TextureAxes from ML output (Q1-D: density → flatBumpy)
        val axes = TextureAxes(
            roughness = axesRaw[0].coerceIn(0f, 1f),  // rough
            hardness  = axesRaw[1].coerceIn(0f, 1f),  // hard
            friction  = axesRaw[2].coerceIn(0f, 1f),  // friction
            flatBumpy = axesRaw[3].coerceIn(0f, 1f),  // density → flatBumpy
        )

        // Stage 7: classify against canonical centroids
        val (material, distance) = MaterialCentroids.classify(axes)
        val confidence = 1f - (distance / MaterialCentroids.CLASSIFICATION_THRESHOLD).coerceAtMost(1f)

        // Stage 8: precompute primitive weights (Q7-B uses null-path only, but compute always)
        val weights = PrimitiveMapper.map(axes)

        val ms = System.currentTimeMillis() - t0

        // Backend label is the encoder's resolved accelerator (head and U2Net should match; pick encoder as canonical).
        val backend = encoder.backendLabel

        Log.i(TAG, "Inference: $backend in ${ms}ms; axes=$axes; mat=$material (d=$distance)")

        AnalysisResult(
            axes = axes,
            material = material,
            confidence = confidence,
            label = material?.display?.lowercase() ?: "other",
            backendLabel = backend,
            inferenceLatencyMs = ms,
            primitiveWeights = weights,
        )
    }

    private fun loadAndOrientBitmap(uri: Uri): Bitmap { /* read from URI, respect EXIF rotation */ TODO() }

    private fun preprocessForEncoder(bm: Bitmap): FloatArray {
        // 224×224 NHWC, [-1, 1] per modeloutput.md: (px/255 - 0.5) / 0.5
        val resized = Bitmap.createScaledBitmap(bm, 224, 224, true)
        val out = FloatArray(224 * 224 * 3)
        val pixels = IntArray(224 * 224)
        resized.getPixels(pixels, 0, 224, 0, 0, 224, 224)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xff) / 255f
            val g = ((p shr 8) and 0xff) / 255f
            val b = (p and 0xff) / 255f
            out[i * 3 + 0] = (r - 0.5f) / 0.5f
            out[i * 3 + 1] = (g - 0.5f) / 0.5f
            out[i * 3 + 2] = (b - 0.5f) / 0.5f
        }
        return out
    }

    override fun close() {
        segmenter.close()
        encoder.close()
        head.close()
    }

    private companion object { private const val TAG = "TactileLensAnalysis" }
}
```

#### 2. Swap the wiring in `AppContainer`

**File**: `app/src/main/java/com/tactilelens/app/AppContainer.kt`

```kotlin
class AppContainer(context: Context) {
    val audio: AudioRenderer = SamplePackAudioRenderer(context)
    val haptic: HapticRenderer = CompositionHapticRenderer(context)

    // Phase 4 swap: real ML instead of mock.
    val analysis: AnalysisClient = LiteRTAnalysisClient(context)

    fun start() {
        audio.start()
        haptic.setEnabled(true)
        // No need for the lazy segmenter from Phase 2; LiteRTAnalysisClient owns it.
    }

    fun stop() {
        audio.stop()
        haptic.stop()
        (analysis as? Closeable)?.close()
    }
}
```

#### 3. Remove the Phase 2 instrumentation from `MainActivity`

**File**: `app/src/main/java/com/tactilelens/app/MainActivity.kt`

Delete the temporary U2Net log block in `LaunchedEffect(imageUri)`. Restore the simpler shape:

```kotlin
LaunchedEffect(imageUri) {
    val captured = imageUri
    if (captured != null && currentScreen == AppScreen.Scanner) {
        coroutineScope {
            val analysisDeferred = async { container.analysis.analyze(captured) }
            delay(7000)
            analysisResult = analysisDeferred.await()
        }
        currentScreen = AppScreen.Results
    }
}
```

### Success Criteria

#### Automated Verification:
- [ ] App compiles: `./gradlew :app:assembleDebug`
- [ ] APK packages all three TFLite assets:
  ```bash
  unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "(u2net_optimized|efficientnet_lite0|linear_head).tflite"
  ```
- [ ] No `UnsatisfiedLinkError` or `RuntimeException` at app start in `adb logcat`.

#### Manual Verification:
- [ ] Capture a photo of **wood** (a real piece). Results screen loads. Latency pill shows `"NPU · <ms>"` with ms in [50, 500] range. Material label reads "Wood." Audio + haptic on swipe match the wood recipe.
- [ ] Repeat for **paper, rocks, sand, fabric, glass**. Each correctly classifies as the matching canonical.
- [ ] Capture a photo of an **arbitrary surface** (e.g. a leaf, a brick, a sweater, a rug). Material label is empty or "other"; latency pill still says NPU; swipe produces axis-driven feedback (not one of the canonical recipes).
- [ ] First-launch latency is higher than subsequent (LiteRT JIT compile cost). Confirm subsequent launches are fast.
- [ ] No regressions: Material picker dropdown, audio loops, haptic resistance bursts all still work.
- [ ] `adb logcat | grep TactileLensAnalysis` shows axis values matching expectations for each material.

**Implementation Note**: This is the headline phase. **Confirm all 6 canonicals classify correctly + at least one open-vocab photo flows through cleanly** before moving on.

---

## Phase 5: `map_primitives()` Wiring for Null Path

### Overview

Wire the precomputed `primitiveWeights` from `AnalysisResult` into the haptic renderer's null-material gesture-start path per locked decision Q7-B. Canonical materials are unchanged. Drag stream is unchanged.

### Changes Required

#### 1. Extend `HapticRenderer` interface to accept primitiveWeights on gesture start

**File**: `app/src/main/java/com/tactilelens/app/data/haptic/HapticRenderer.kt`

```kotlin
interface HapticRenderer {
    fun setEnabled(enabled: Boolean)
    fun onGestureStart(material: Material?, axes: TextureAxes, velocity: Float, primitiveWeights: FloatArray? = null)
    fun onSwipeMove(material: Material?, axes: TextureAxes, velocity: Float)
    fun onSwipeEnd()
    fun stop()
    fun play(recipe: HapticRecipe)
}
```

(Default-arg keeps backward compatibility with any existing callers.)

#### 2. Use `primitiveWeights` only for null path in `CompositionHapticRenderer`

**File**: `app/src/main/java/com/tactilelens/app/data/haptic/CompositionHapticRenderer.kt`

```kotlin
override fun onGestureStart(material: Material?, axes: TextureAxes, velocity: Float, primitiveWeights: FloatArray?) {
    if (!enabled || vibrator == null) return
    val velocityScale = (0.5f + velocity.coerceIn(0f, 1f) * 0.5f)
    val effect = when (material) {
        Material.WOOD   -> woodRecipe(velocityScale)
        Material.PAPER  -> paperRecipe(velocityScale)
        Material.ROCKS  -> rocksRecipe(velocityScale)
        Material.SAND   -> sandRecipe(velocityScale)
        Material.FABRIC -> fabricRecipe(velocityScale)
        Material.GLASS  -> glassRecipe(velocityScale)  // added in Phase 6
        null -> primitiveWeights?.let { mlPrimitiveRecipe(it, velocityScale) } ?: proceduralRecipe(axes, velocityScale)
    }
    vibrator.vibrate(effect)
}

/**
 * Null-material gesture-start signature derived from ML team's
 * `map_primitives()` 8-weight vector. Per locked decision Q7-B,
 * fires the 8-primitive Composition (their HapticsHelper design)
 * exactly once on touch-down for open-vocabulary surfaces.
 *
 * Drag stream stays motion-gated (existing per-crossing path).
 */
private fun mlPrimitiveRecipe(weights: FloatArray, scale: Float): VibrationEffect {
    require(weights.size == 8) { "primitiveWeights must have 8 entries" }
    val comp = VibrationEffect.startComposition()
    val primitives = listOf(
        PRIMITIVE_TICK, PRIMITIVE_LOW_TICK, PRIMITIVE_CLICK, PRIMITIVE_THUD,
        PRIMITIVE_SLOW_RISE, PRIMITIVE_QUICK_RISE, PRIMITIVE_QUICK_FALL, PRIMITIVE_SPIN,
    )
    for (i in primitives.indices) {
        val amp = (weights[i] * scale).coerceIn(0.01f, 1.0f)
        if (amp > 0.05f) comp.addPrimitive(primitives[i], amp, 0)
    }
    return comp.compose()
}
```

#### 3. Update the call site in `ResultsViewModel` / `MainActivity`

Wherever `haptic.onGestureStart(...)` is called when navigating to Results, pass `result.primitiveWeights`:

```kotlin
container.haptic.onGestureStart(
    material = result.material,
    axes = result.axes,
    velocity = 0.7f,
    primitiveWeights = result.primitiveWeights,
)
```

### Success Criteria

#### Automated Verification:
- [ ] App compiles: `./gradlew :app:assembleDebug`
- [ ] No new compile warnings about unused parameters.

#### Manual Verification:
- [ ] Capture a canonical material photo (e.g. wood). On entering Results, the existing wood signature chord plays (NOT the 8-primitive blend). Confirm by feel.
- [ ] Capture an arbitrary surface (open-vocab). On entering Results, a multi-primitive chord plays, distinct from any of the 6 canonical signatures. The chord shape varies between different non-canonical photos (because axes differ).
- [ ] Drag stream behavior is identical for all materials (per-crossing primitives or resistance bursts), unchanged from Phase 4.

**Implementation Note**: Pause after Phase 5 for confirmation. Phase 6 is independently scopeable polish.

---

## Phase 6: GLASS Audio + Haptic Recipe (deferred polish)

### Overview

Re-enable `Material.GLASS` end-to-end: source CC0 audio for the glass loop, define a `HapticTuning.Glass` data class with a CLICK-heavy recipe, set its grid spacing.

This is a polish phase. Without it, photos that classify as Glass produce silent audio (renderer logs a missing-asset warning) and use the procedural haptic recipe. Functional but not demo-quality.

### Changes Required

#### 1. Source GLASS audio

**Procedure**:
- Search Pixabay or Freesound for CC0 finger-on-glass / glass-rub clip (similar workflow to the Pixabay paper sourcing in commit `d06f2b9`).
- ffmpeg-process to 3 s mono 44.1 kHz 16-bit PCM via the project recipe (see `app/src/main/assets/audio/SOURCES.md`).
- Drop at `app/src/main/assets/audio/glass/loop.wav`.
- Update `SOURCES.md` with provenance.

#### 2. Define `HapticTuning.Glass` recipe

**File**: `app/src/main/java/com/tactilelens/app/data/model/HapticTuning.kt`

```kotlin
/** Glass: bright CLICK with brief TICK tail (sharp ringing snap). */
data class Glass(
    val clickScale: Float = 0.95f,
    val gapMs: Int = 50,
    val tickScale: Float = 0.40f,
)
```

Plus add to outer `HapticTuning` data class.

#### 3. Add `glassRecipe()` and swipeShape entry in `CompositionHapticRenderer`

```kotlin
private fun glassRecipe(scale: Float): VibrationEffect {
    val cfg = tuning.glass
    return VibrationEffect.startComposition()
        .addPrimitive(PRIMITIVE_CLICK, (cfg.clickScale * scale).coerceIn(0f, 1f), 0)
        .addPrimitive(PRIMITIVE_TICK,  (cfg.tickScale  * scale).coerceIn(0f, 1f), cfg.gapMs)
        .compose()
}

// In swipeShape():
Material.GLASS -> PRIMITIVE_QUICK_FALL to 0.85f  // bright + slick = QUICK_FALL fits per modeloutput.md
```

#### 4. Add GLASS grid spacing

**File**: `app/src/main/java/com/tactilelens/app/ui/results/InteractiveGridZone.kt`

```kotlin
Material.GLASS -> 38f  // densest after sand; very smooth + flat
```

### Success Criteria

#### Automated Verification:
- [ ] App compiles.
- [ ] APK contains `assets/audio/glass/loop.wav`: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep glass/loop.wav`
- [ ] Loop is exactly 3 s 44.1 kHz mono: `ffprobe assets/audio/glass/loop.wav` shows the right format.

#### Manual Verification:
- [ ] Select GLASS in the dropdown. Audio plays a real glass-character loop.
- [ ] Tap the grid: gesture-start signature feels sharp + bright (CLICK-heavy).
- [ ] Drag the grid: each crossing fires QUICK_FALL — a slick, falling-edge feel matching glass character.

**Implementation Note**: Polish phase. Skip if running short on time before submission; the procedural fallback handles GLASS acceptably.

---

## Phase 7 (Optional): AOT Compilation via Qualcomm AI Hub

### Overview

Reserved as a polish move per locked decision Q4-B. AOT-compile the encoder + head TFLites via Qualcomm AI Hub to produce NPU-optimized binaries that load instantly without the JIT compile cost on first launch. Improves cold-start latency for the demo.

Skip if v1 is performing well by submission time.

### Changes Required

#### 1. Run AI Hub compile

**Procedure** (one-time, by the ML team or Lead):

- Use the team's existing `ai_workbench/workbench.py` pattern (from `origin/main`) but for the encoder + head:
  - Upload `efficientnet_lite0.tflite` and `linear_head.tflite` to AI Hub.
  - Compile for `Samsung Galaxy S25 Ultra` target.
  - Download `efficientnet_lite0_optimized.tflite` and `linear_head_optimized.tflite` (or the produced QNN binaries if AI Hub outputs `.bin`).

#### 2. Replace TFLites in assets

**Files**:
- `app/src/main/assets/efficientnet_lite0.tflite` (overwrite with optimized version)
- `app/src/main/assets/linear_head.tflite` (overwrite with optimized version)

If AI Hub produces QNN context binaries instead of optimized TFLite, switch `LiteRTSession` to load via the QNN context binary path.

### Success Criteria

#### Automated Verification:
- [ ] App still compiles.
- [ ] APK still packages the (now optimized) TFLites.

#### Manual Verification:
- [ ] **Cold-start first inference latency drops noticeably** vs Phase 4 baseline. Concrete target: first inference < 300 ms (down from ~1 s JIT cost).
- [ ] Subsequent inferences are at parity with Phase 4 or better.
- [ ] All 6 canonicals still classify correctly.

**Implementation Note**: Skip if the v1 demo feels snappy enough.

---

## Testing Strategy

### Unit Tests

- `MaterialCentroids.classify(axes)` returns correct material for each centroid (within rounding); returns null for axes equidistant between two centroids.
- `PrimitiveMapper.map(axes)` returns 8 weights summing to 1.0 (within float epsilon) for various axes; returns sensible weights for the 6 known centroids.
- `LiteRTSession` lifecycle: load → run → close without leaks (manual instrumentation; full leak-check unit testing is out of scope for this hackathon).

### Integration Tests

Out of scope for the hackathon timeframe. Smoke testing happens via the manual phases.

### Manual Testing Steps

1. Install the Phase 4 build on the S25 Ultra.
2. Set device vibration intensity to maximum (Settings → Sound and vibration → Vibration intensity → max).
3. Test each canonical material with a real-world surface:
   - Wood: piece of furniture or wood block.
   - Paper: a sheet of paper.
   - Rocks: gravel or a stone.
   - Sand: a small dish of sand or sandpaper.
   - Fabric: a knit scarf or cloth.
   - Glass: a glass cup or polished phone screen.
4. For each: capture photo, verify Material label matches, verify NPU latency pill, swipe and verify audio + haptic recipe matches.
5. Test 3 open-vocabulary surfaces: a leaf, a brick, a sweater. Verify the Material label is empty/other, axes look reasonable, haptic produces the ML-derived 8-primitive blend on touch-down (Phase 5).

## Performance Considerations

- **First-launch JIT compile cost** is the dominant latency. LiteRT will compile the TFLite to QNN context on first inference; subsequent inferences are fast. Mitigated by Phase 7 (AOT) if needed.
- **3-stage inference** (U2Net → encoder → head) fires sequentially. If U2Net latency is high, consider running it in parallel with photo decode (cosmetic optimization, low priority).
- **Memory**: 3 LiteRT models loaded simultaneously. U2Net (~5 MB) + encoder (~5 MB optimized) + head (~30 KB) = ~10 MB plus runtime tensor buffers. Fits comfortably on the S25 Ultra's 12+ GB RAM.
- **Battery**: NPU is the most power-efficient path per Qualcomm. Inference cost is ~60–80% lower than CPU equivalent. Not a concern at demo cadence.

## Migration Notes

- The `frontend-ui` branch grows by ~10 MB (TFLite assets) plus ~600 lines of Kotlin. APK size grows from ~18 MB to ~28 MB.
- Eventually `frontend-ui` will be merged to `main` for hackathon submission. That merge is **out of scope for this plan** — it's a separate operation, planned for after Phase 4 verification, that resolves the `com.example.tactilelens` vs `com.tactilelens.app` package conflict by deleting the ML team's old Kotlin helpers (per locked decision Q3-A).

## References

- `HACKATHON.md` (repo root) — hackathon constraints, judging criteria, decision log.
- `ARCHITECTURE.md` (repo root) — current architecture source of truth.
- `modeloutput.md` (`origin/haptics_model:modeloutput.md`) — ML team's model contract spec.
- `app/src/main/assets/audio/SOURCES.md` — audio source provenance.
- ML team's `colab_convert_encoder.ipynb` (`exports.zip` on `origin/haptics_model`) — ONNX → TFLite conversion notebook.
- ML team's `map_primitives.py` (`exports.zip`) — Python rule for axes → 8 primitive weights, ported to Kotlin in Phase 1.
- Locked decisions log: see top of this file (Q1–Q8).
- LiteRT CompiledModel API docs: https://ai.google.dev/edge/litert/next/qualcomm
- Qualcomm QNN delegate setup: https://ai.google.dev/edge/litert/android/npu/qualcomm
- Existing per-crossing resistance burst design in `CompositionHapticRenderer.kt` (commit `d8ea081`) — preserved unchanged through this plan.
