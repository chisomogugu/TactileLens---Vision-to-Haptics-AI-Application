---
date: 2026-05-01T08:12:23Z
git_commit: 172a73a
branch: frontend-ui
repository: TactileLens
topic: "Phase 2 done, all models on Hexagon NPU; next: write LiteRTAnalysisClient (Phase 4)"
tags: [handoff, litert, qnn, npu, hexagon, ai-hub, phase-4, hackathon]
status: in-progress
last_updated: 2026-05-01
type: handoff
---

# Handoff: Phase 2 NPU dispatch verified, ready for Phase 4

## Task(s)

**Done:**
- **Phase 1** of `specs/plans/2026-04-30-litert-ml-integration.md` — committed as `6709f57`. LiteRT + QNN deps, `Material.GLASS`, `MaterialCentroids`, `PrimitiveMapper`, mock client populates new fields, `BackendLatencyPill` UI, `noCompress += "tflite"`.
- **Phase 2** — committed across `0fafefe`, `7ee2f60`, `7ba8ec9`, `172a73a`. **All three TFLite models bind to NPU on the S25 Ultra's Hexagon HTP.** U2Net inference: 425 ms (CPU/XNNPack) → **8 ms (NPU)**. Reveal animation now consumes real saliency + mask+bbox + cropped foreground bitmaps from U2Net.

**In progress:**
- **Phase 4** — `LiteRTAnalysisClient`. Encoder + (head's math) need to be wired into the analysis pipeline so `AnalysisResult` carries real texture axes, real material, real total inference latency. Currently still using `MockAnalysisClient`; the latency pill on Results shows U2Net's real backend + ms only.

**Skipped/deferred per direction:**
- **Phase 3 (TFLite acquisition via Colab)** — superseded. We compiled both `efficientnet_lite0.tflite` (3.96 MB INT8 + 13 MB fp32) and `linear_head.tflite` (14 KB INT8) via AI Hub Workbench, but the **head TFLite was dropped from the app per direction** ("we are only using two — U2Net + EfficientNet"). The user is OK with re-implementing the linear head's math in Kotlin from `linear_head.onnx` weights.

## Critical References

1. **`specs/plans/2026-04-30-litert-ml-integration.md`** — original plan (locked decisions Q1–Q8). NOTE: Q3 said "CompiledModel API" but that class doesn't ship in `litert:1.0.1` — see `LiteRTSession.kt:11-32` for the canonical `Interpreter + QnnDelegate(HTP_BACKEND)` pattern we landed on (lifted from quic/ai-hub-apps's `image_classification_android` sample).
2. **`/tmp/ai-hub-apps/apps/_shared/android/tflite_helpers/TFLiteHelpers.java`** — Qualcomm's reference `CreateQNN_NPUDelegate` flow. We mirror it.
3. **`/tmp/ai-hub-apps/apps/image_classification_android/src/main/AndroidManifest.xml`** — pattern for `<uses-native-library>` declarations.

## Recent changes

**Live commits on `frontend-ui` (since session start):**
```
172a73a Phase 2 UI polish + asset cleanup
7ba8ec9 NPU dispatch confirmed: all three models on Hexagon HTP
7ee2f60 Phase 2 device fixes: robust CPU fallback, hardware bitmap, no mock flicker
0fafefe LiteRT integration Phase 2: U2Net pilot on QNN delegate
4a7d0a6 Switch U2Net asset to u2net_small.tflite
6709f57 LiteRT integration Phase 1: scaffolding for ML swap
```

**Files touched (current state at HEAD `172a73a`):**
- `app/src/main/java/com/tactilelens/app/data/analysis/LiteRTSession.kt` — canonical Qualcomm pattern. `checkCapability` + `setSkelLibraryDir` + `setCacheDir` + `setModelToken` + `Interpreter.Options.setRuntime(FROM_APPLICATION_ONLY)`. CPU fallback works robustly when QNN can't apply.
- `app/src/main/java/com/tactilelens/app/data/analysis/U2NetSegmenter.kt` — produces `SegmentationResult(saliency, maskWithBbox, cropped, bbox, inferenceMs)`. `cropped` is full-original-size with DeepSpace background fill (not transparent). `maskWithBbox` is full-original-size with cyan rectangle stroke.
- `app/src/main/java/com/tactilelens/app/AppContainer.kt` — owns `U2NetSegmenter`, eagerly init'd on `start()`. Smoke-test for encoder/head removed.
- `app/src/main/java/com/tactilelens/app/MainActivity.kt:171-198` — `LaunchedEffect(imageUri)` runs U2Net `withContext(Dispatchers.Default)`, awaits result before the 2.6 s reveal budget. Pill metadata override at `:188-196`.
- `app/src/main/java/com/tactilelens/app/MainActivity.kt:477-585` — `ScannerRevealEffect` 3-stage with center-crop semantics (`srcOffset/srcSize`, no stretching).
- `app/src/main/AndroidManifest.xml` — `<uses-native-library>` for `libcdsprpc.so` and `libOpenCL.so`.
- `app/build.gradle.kts` — `noCompress += "tflite"` AND `packaging { jniLibs { useLegacyPackaging = true } }` (the latter is critical so QNN libs get extracted to disk for `dlopen`).
- `app/src/main/assets/u2net_small.tflite` (4.4 MB) — onnx2tf-converted from `u2netp.onnx`.
- `app/src/main/assets/efficientnet_lite0.tflite` (13 MB) — AI Hub Workbench-compiled fp32 from `efficientnet_lite0.onnx`. Runs on NPU via FP16 path.

**Files removed from `app/src/main/assets/`:**
- `linear_head.tflite` — per direction; will inline math in Kotlin (Phase 4).
- `thermal_map.png`, `depth_wireframe.png` — old scanner-reveal mock assets.

## Learnings

### NPU dispatch — what actually works on the S25 Ultra

The plan said "CompiledModel API." That doesn't exist in `com.google.ai.edge.litert:litert:1.0.1`. The 1.0.x AAR ships `org.tensorflow.lite.Interpreter` (the standard API). Qualcomm's hackathon path is `Interpreter + QnnDelegate(BackendType.HTP_BACKEND)`. The "NOT Interpreter+NNAPI" warning in the locked decisions referred to the **NNAPI delegate**, not the Interpreter class.

The full unlock sequence for NPU dispatch was:

1. **`<uses-native-library android:name="libcdsprpc.so" required="false"/>`** in AndroidManifest.xml. Without this, `libQnnHtpV79Stub.so` can't dlopen Samsung's CDSP RPC bridge in Android 13+'s isolated namespace. Error: `dlopen failed: library "libcdsprpc.so" not found in namespace clns-8`. Surfaces only at `setLogLevel(LOG_LEVEL_VERBOSE)`.
2. **`packaging { jniLibs { useLegacyPackaging = true } }`** in `app/build.gradle.kts`. Modern Android default keeps libs mmap'd inside the APK with no on-disk path; QnnDelegate's `dlopen` of `libQnnHtp.so` fails. Forcing legacy packaging extracts them under `nativeLibraryDir`.
3. **`QnnDelegate.checkCapability(HTP_RUNTIME_FP16)`** before constructing — the device tells us which precision its Hexagon revision supports. Don't brute-force.
4. **`setSkelLibraryDir(context.applicationInfo.nativeLibraryDir)`** only. Do NOT call `setLibraryPath` — that wants a specific `.so` file, not a dir; passing the dir was a wrong turn that wasted 30 minutes.
5. **`setCacheDir(context.cacheDir.absolutePath) + setModelToken(assetName)`** — persists the QNN-compiled graph so cold-start latency is constant after first compile.
6. **`Interpreter.Options.setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_APPLICATION_ONLY)`** + `setUseNNAPI(false) + setUseXNNPACK(true) + setAllowBufferHandleOutput(true)`.

Reference: `app/src/main/java/com/tactilelens/app/data/analysis/LiteRTSession.kt:97-148` for the working setup, mirroring `/tmp/ai-hub-apps/apps/_shared/android/tflite_helpers/TFLiteHelpers.java:273-333`.

### AI Hub Workbench is required for HTP-friendly TFLites

Plain `onnx2tf` output (`u2net_small.tflite`) is fp32 and **HTP refuses to apply the delegate** even with all the right options. AI Hub's `submit_compile_job(target_runtime=tflite)` produces an HTP-compatible fp32 graph. Profile job confirms all layers map to NPU. We did NOT need quantization for proof-of-NPU; the FP16 HTP path takes fp32 graphs.

For U2Net specifically: the team's `u2net_small.tflite` got HTP-rejected (XNNPack took 258/259 nodes on CPU). However the AI Hub-compiled encoder/head DID bind to NPU. **U2Net stays on CPU at ~425 ms unless we re-prepare it via AI Hub** — but with `setHtpPrecision(FP16)` and the fp32 AI Hub output, it currently DID land on NPU per the latest logcat (`segment: ... inference=8ms (NPU)`). Verify on next test.

Wait — re-check: the latest logcat at session end showed `Loaded u2net_small.tflite on NPU` AND `segment: ... inference=8ms (NPU)`. So u2net_small.tflite DID bind once we had `setHtpPrecision(FP16)` + `setSkelLibraryDir` + `<uses-native-library>` + `useLegacyPackaging`. The earlier "u2net_small rejected" was without those four. Good.

### AI Hub setup (already configured)

- `qai-hub` is installed in `/tmp/qai_hub_venv/`.
- `~/.qai_hub/client.ini` has the user's API token.
- Compile script: `/tmp/aihub_compile.py`. Quantize script: `/tmp/aihub_quantize.py`.
- Outputs at `/tmp/tflite_outputs/` — fp32 versions in use, INT8 versions on hand if needed.
- Profile job results reachable via `qai-hub` Python API. Encoder fp32 = 343 µs / 61 layers all on NPU. Head fp32 = 43 µs / 3 layers all on NPU.
- `/tmp/` may not survive a reboot. ONNX sources still recoverable from `exports.zip` on `origin/haptics_model`.

### UI gotchas

- **Hardware bitmaps from `ImageDecoder`** can't `getPixels()`. Convert to `Bitmap.Config.ARGB_8888` software at U2NetSegmenter entry. See `U2NetSegmenter.kt:53-60`.
- **Center-crop in Compose Canvas** requires `srcOffset/srcSize`, not `dstSize`. Otherwise transition from `Image(ContentScale.Crop)` to Canvas visibly squeezes the photo. Helper at `MainActivity.kt:521-538`.
- **`AnalysisResult` data class with `FloatArray?`** field uses array reference equality for `equals/hashCode`. Fine for our usage (one-shot value flow); flag if Phase 4 starts comparing results.

### Mock asset ghosting

`thermal_map.png` and `depth_wireframe.png` were original scanner-reveal placeholders. They flickered visibly mid-animation when U2Net inference completed mid-reveal. Removed the mock fallback entirely; reveal mounts only after segmentation is ready (parent awaits `segmentation = withContext(Default) { segmenter.segment(bm) }` before starting `delay(2600)`). Loading state shows captured photo + basic ScannerOverlay sweep.

## Artifacts

**ML pipeline (working, NPU-dispatched):**
- `app/src/main/assets/u2net_small.tflite` — U2Net foreground segmentation
- `app/src/main/assets/efficientnet_lite0.tflite` — encoder, fp32, AI Hub-compiled

**Inputs/utilities for Phase 4:**
- `/tmp/linear_head.onnx` — 30 KB, single linear projection 1280 → 4. Phase 4 will extract weights here and inline as Kotlin matmul.
- `/tmp/efficientnet_lite0.onnx` — encoder ONNX (still useful as a reference for input/output shapes).
- `/tmp/colab_convert_encoder.ipynb` — original ONNX → TFLite conversion notebook (informational; we used AI Hub Workbench instead).
- `/tmp/map_primitives.py` — Python rule already ported to Kotlin at `app/src/main/java/com/tactilelens/app/data/analysis/PrimitiveMapper.kt`.

**Existing analysis-package files (Phase 4 will glue these together):**
- `data/analysis/AnalysisClient.kt` — interface, single method `suspend analyze(uri: Uri): AnalysisResult`.
- `data/analysis/MockAnalysisClient.kt` — currently wired in `AppContainer`.
- `data/analysis/LiteRTSession.kt` — single-model wrapper. Use for encoder.
- `data/analysis/U2NetSegmenter.kt` — owns its own `LiteRTSession` for U2Net; exposes `backendLabel` and `inferenceMs`.
- `data/analysis/MaterialCentroids.kt` — `classify(axes: TextureAxes): Pair<Material?, Float>`. Threshold 0.30.
- `data/analysis/PrimitiveMapper.kt` — `map(axes: TextureAxes): FloatArray` (8 weights summing to 1.0).
- `data/model/AnalysisResult.kt` — has the `backendLabel`, `inferenceLatencyMs`, `primitiveWeights` fields ready.

**On-device verification:**
- `adb logcat | grep -E "(TactileLens|tflite)"` will show `Loaded ... on NPU; input=[...], output=[...]` plus `segment: ... inference=Xms (NPU)`.
- Smoke-test code is removed; the only NPU log lines come from the U2Net path now.
- Next session can verify by adb-connecting and re-installing.

## Action Items & Next Steps

In priority order:

### 1. Phase 4 Step 1: Extract `linear_head` weights from ONNX into a Kotlin constants file

`linear_head.onnx` is 30 KB and conceptually a single linear projection from 1280-dim features to 4 axes (rough, hard, friction, density), with feature normalization baked in.

Action:
- Write a small Python helper (use `/tmp/qai_hub_venv` with `onnx` already installed) to dump the `linear_head.onnx` weights as a Kotlin file (or a binary `.fbs` asset). Likely pattern: `out = (features - mean) / std` then `axes = W @ out + b`.
- Inspect via `python3 -c "import onnx; m = onnx.load('/tmp/linear_head.onnx'); [print(i.name, [d.dim_value for d in i.type.tensor_type.shape.dim]) for i in m.graph.input]; print(onnx.helper.printable_graph(m.graph))"` to see the actual op order.
- Output: `app/src/main/java/com/tactilelens/app/data/analysis/LinearHead.kt` with `fun project(features: FloatArray): FloatArray` returning `[rough, hard, friction, density]`.

### 2. Phase 4 Step 2: Write `LiteRTAnalysisClient`

`app/src/main/java/com/tactilelens/app/data/analysis/LiteRTAnalysisClient.kt`. Implements `AnalysisClient`. Pipeline:

1. Decode bitmap from URI; convert hardware → ARGB_8888 software.
2. `segmenter.segment(bitmap) → SegmentationResult`. Get `cropped` bitmap (full-size with DeepSpace background) and `bbox`.
3. Crop the original photo to `bbox` (NOT the masked one, since the encoder wants the photo content). Resize to 224×224.
4. Preprocess: NCHW float32, `(px/255 - 0.5) / 0.5` normalization (per `colab_convert_encoder.ipynb` cell 8).
5. `encoder.run(input, features1280)` via a `LiteRTSession("efficientnet_lite0.tflite")` owned by the client.
6. `LinearHead.project(features1280) → [rough, hard, friction, density]` in Kotlin.
7. Build `TextureAxes(roughness, flatBumpy=density, friction, hardness=hard)` (per locked Q1-D mapping).
8. `MaterialCentroids.classify(axes) → (Material?, distance)`. Compute confidence as `1f - (distance / 0.30).coerceAtMost(1f)`.
9. `PrimitiveMapper.map(axes) → FloatArray(8)`.
10. Total `backendLabel`: `"NPU"` if both U2Net AND encoder bound to NPU, else mixed. `inferenceLatencyMs`: sum of U2Net + encoder + head ms.
11. Return `AnalysisResult(axes, material, confidence, label, backendLabel, totalMs, primitiveWeights)`.

### 3. Phase 4 Step 3: Swap `MockAnalysisClient` → `LiteRTAnalysisClient` in `AppContainer`

`app/src/main/java/com/tactilelens/app/AppContainer.kt:24` — change `val analysis: AnalysisClient = MockAnalysisClient(context)` to `LiteRTAnalysisClient(context)`. Add `(analysis as? Closeable)?.close()` in `stop()`.

Drop the U2Net override in `MainActivity.kt:188-196` since the analysis client now owns the full pipeline metadata. Pill will read `"NPU · <total ms>"` from real data.

### 4. Phase 5–7

Per the plan, after Phase 4: wire `primitiveWeights` into the haptic null-path (Phase 5), GLASS audio + recipe (Phase 6, optional), AOT compile via AI Hub (Phase 7, optional polish). All three are deferrable.

### 5. Hackathon submission concerns (out of scope for next session unless asked)

`HACKATHON.md` notes the "newly created" rule risk. `frontend-ui` has many commits before noon PT today. Lead should clarify with `support@devpost.com` before submission. Also: eventual `frontend-ui` → `main` merge is a separate operation.

## Other Notes

- **The user is on auto mode with strong opinions.** They prefer action over questions, but they push back hard when the work goes off-track ("DON'T LOOK AT THAT BRANCH PLEASE", "STOP DOING X"). Take corrections at face value. Don't re-ligitate locked decisions.
- **The user has a parallel teammate** working on `feature/full-haptics-integration` who they explicitly told me NOT to look at. **Do not pull from or reference that branch.** Their teammate may also be running the Colab encoder-conversion in parallel; we already produced our own AI Hub TFLites so this is moot, but flag it if anything new appears on `origin/haptics_model`.
- **`local.properties` is dirty in working tree** (Android SDK path is user-specific, was teammate's path upstream). Never `git add local.properties`. Already excluded from every commit so far.
- **`<uses-native-library>` for `libOpenCL.so`** was added to the manifest as a copy of Qualcomm's sample. Not strictly required since we use HTP not GPU, but harmless and forward-compatible if Phase 7 adds a GPU delegate fallback.
- **Vibration intensity** must be at MAX on the demo device (Settings → Sound and vibration → Vibration intensity → max). Verify with `adb shell dumpsys vibrator_manager | grep TOUCH` — should read `TOUCH = HIGH`. If user reports weak haptics, check this BEFORE re-tuning amplitudes.
- **Renderer architecture (commit `d8ea081`) is the locked baseline.** Per-crossing motion-gated model, smooth materials → single primitive per crossing, high-friction → resistance burst. Phase 4 changes axes/material upstream of renderers; do not touch the renderers themselves unless explicitly asked.
- **Two AI Hub jobs were submitted with INT8 quantization** (encoder `jpr13mqeg`, head `jp23yq6mg`) using random calibration data. Outputs at `/tmp/tflite_outputs/efficientnet_lite0_int8.tflite` (3.96 MB) and `linear_head_int8.tflite` (14 KB). Not used in v1 since fp32 + FP16 HTP works. Available if Phase 4 Step 2 hits accuracy issues — though for proof-of-NPU the fp32 is fine.
- **The `mask` field on `SegmentationResult` was renamed to `maskWithBbox`.** Anything still calling `.mask` will break. Grep before touching.
- **APK is now ~155 MB** due to all the QNN HTP skel libs (V68/V69/V73/V75/V79 × Skel + Stub). Fine for hackathon; production would do ABI splits.
