# TactileLens - ML to Android Integration Handshake

Based on the ML Team's `texture_haptics_project_summary_v4.txt`, here are the exact specifications the Android team needs to know to successfully load the MobileNet LiteRT model and map its outputs to the Android Haptics system.

## 1. Model Input (Vision)
* **Model Base**: MobileNet (compiled for Hexagon NPU via Qualcomm AI Hub).
* **Input**: The camera frames must be cropped to the texture region (Stage 0). The ML doc suggests using ML Kit Subject Segmentation on-device to isolate the object before feeding it into the LiteRT model.
* **Format**: Standard image tensor (e.g., `1 x 3 x 224 x 224`).

## 2. NPU Execution & Fallback (LiteRT Configuration)
* **NNAPI Delegate**: To ensure the `.tflite` model actually runs on the Samsung S25 Ultra's Hexagon NPU, the Android engineer MUST configure the LiteRT `Interpreter.Options` to use NNAPI. 
* **Fallback**: By enabling NNAPI, Android handles the hardware abstraction. If the NPU cannot handle a specific operation in the MobileNet model, NNAPI automatically falls back to the GPU or CPU.
* **Code Example**:
  ```kotlin
  val options = Interpreter.Options().apply {
      numThreads = 4
      useNNAPI = true // Crucial for NPU execution + automatic fallback
  }
  val interpreter = Interpreter(litertBuffer, options)
  ```

## 3. Model Output (Haptics)
* **Output Shape**: The model will output a 1D float array of **8 weights**. These weights sum to 1.0 and represent the blend of Android haptic primitives that simulate the texture.
* **Array Index Mapping**:
  The Android engineer must map these 8 output floats directly to the `VibrationEffect.Composition` primitives in `HapticsHelper.kt`:
  1. `[0]` -> `PRIMITIVE_TICK` (Short, sharp, high-frequency tap)
  2. `[1]` -> `PRIMITIVE_LOW_TICK` (Softer, lower-intensity tick)
  3. `[2]` -> `PRIMITIVE_CLICK` (Discrete, stiff contact feel)
  4. `[3]` -> `PRIMITIVE_THUD` (Heavy, low-frequency, compliant impact)
  5. `[4]` -> `PRIMITIVE_SLOW_RISE` (Gradual build-up)
  6. `[5]` -> `PRIMITIVE_QUICK_RISE` (Fast onset)
  7. `[6]` -> `PRIMITIVE_QUICK_FALL` (Slippery release)
  8. `[7]` -> `PRIMITIVE_SPIN` (Periodic/repeating texture - Note: fixed at 0.05 for v1)

## 4. Audio Integration
* The *Project Description* notes an immersive audio requirement ("high-frequency gritty audio transients" vs "low-frequency acoustic feedback"). 
* The Android team should use the **Media3 (ExoPlayer)** API to generate or playback synthetic audio that is synchronized with the Android `VibratorManager` playback sequence. The same 8 primitive weights will be used to mix/modulate the audio output.

## 5. Latency Requirement
* Both the audio playback and the haptic composition execution must complete in **< 20ms** from the camera frame capture. The LiteRT model will run asynchronously on the Hexagon NPU to keep the CPU free for the Android Vibration and Audio APIs.
