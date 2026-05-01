# TactileLens Setup Understanding

## 1. Project Overview
**TactileLens** is an on-device AI application designed to translate visual textures into real-time haptic and audio feedback. By pointing a smartphone (e.g., Samsung Galaxy S25 Ultra) at a surface, the app analyzes visual microtextures and maps physical attributes (roughness, granularity, hardness, friction) to haptic vibrations and audio signatures. 

### Key Constraints:
- **Sub-20ms Latency:** Critical to maintain the "illusion of touch".
- **On-Device Processing:** Execution is completely on-device, leveraging the Snapdragon Hexagon NPU.
- **CPU Availability:** By offloading feature-vector math to the NPU, the CPU remains free to manage the Android Haptics and Audio APIs smoothly.

## 2. Architecture & Pipeline
The project spans from Machine Learning to Android mobile development:

1. **Custom PyTorch Model:** A texture classification and feature-extraction model developed in PyTorch.
2. **AI Hub Compilation:** The model is submitted to the **Qualcomm AI Hub** to be traced, compiled, and optimized for the Snapdragon Hexagon NPU.
3. **LiteRT Target:** The compilation output is a LiteRT (`.tflite`) file format, which is optimized for mobile deployment.
4. **Android Integration:** The `.tflite` model is directly downloaded into the Android project's `app/src/main/assets` directory. The Android app will use **LiteRT** for inference, **CameraX** for capturing surface visuals, and **Haptics/Audio APIs** for generating sensory feedback.

## 3. The AI Workbench (`ai_workbench/workbench.py`)
The AI workbench automates the bridge between the ML workflow and the Android project. It performs the following steps:
- **Authentication:** Loads the `QAI_HUB_API_TOKEN` from the `.env` file.
- **Tracing & Compilation:** Takes the custom PyTorch model and traces it with a dummy input tensor, then submits a compilation job targeting the Snapdragon NPU (using LiteRT runtime).
- **Profiling:** Submits a profiling job to a target device (e.g., Galaxy S24/S25 Ultra) to ensure the execution time remains strictly under the **20ms** threshold.
- **Exporting:** Downloads the compiled `texture_model.tflite` directly into the `app/src/main/assets` folder of the Android App, enabling a seamless handoff for the Android developers.

## 4. Next Steps
Based on this understanding, the immediate next step is to create the workbench (as instructed, I have deferred starting this). This will involve finalizing the setup so that ML engineers can seamlessly insert their custom vision-to-haptics model into the pipeline, and the Android build can pick it up without manual intervention.
