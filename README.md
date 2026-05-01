# TactileLens: Vision-to-Haptics AI

![TactileLens](https://img.shields.io/badge/Status-Active-success) ![Platform](https://img.shields.io/badge/Platform-Android-green) ![AI](https://img.shields.io/badge/AI-LiteRT%20%7C%20Qualcomm%20Hexagon-blue)

**TactileLens** is an on-device AI application designed to translate visual textures into real-time haptic and audio feedback. By simply pointing a smartphone camera (optimized for the Samsung Galaxy S25 Ultra) at a surface, the app analyzes visual microtextures and maps physical attributes (roughness, hardness, friction, and density) into a rich, multi-sensory experience.

## 🎥 Demo

[Watch the 60-second demo on YouTube](https://www.youtube.com/watch?v=EnYHW1lqsRU)

## 🌟 Vision & Use Cases

We are enabling users to "feel" the world through their smartphones. 
- **Accessibility:** Low-vision users can perceive surface qualities and environmental hazards (e.g., gravel vs. smooth pavement, wet surfaces) safely through touch.
- **Global Telepresence:** Users can "feel" textures from across the world. From a shopper checking the quality of fabric in a remote boutique to a traveler "touching" a historical monument via a photograph.

## 🏗️ Architecture & Technology Stack

The project spans an advanced Machine Learning pipeline tightly integrated with native Android mobile APIs to achieve strict sub-20ms latency constraints.

### 1. Machine Learning Pipeline (On-Device NPU)
- **LiteRT (TensorFlow Lite):** We utilize LiteRT configured with the Qualcomm `QnnDelegate` to execute inference directly on the Snapdragon Hexagon NPU (HTP).
- **End-to-End Texture Model:** The app employs a custom, AI Hub-optimized texture classification model. It takes an image patch and outputs a continuous feature vector across four axes:
  - `Roughness`
  - `Hardness`
  - `Friction`
  - `Density`
- **U2Net Segmentation:** We use a lightweight U2Net model to isolate the subject from the background, ensuring texture analysis focuses on the relevant object.
- **Zero-Copy I/O:** Inference relies on pre-allocated `ByteBuffer` arrays matching native endianness, bypassing dimensionality mismatch issues and eliminating garbage collection pauses during the camera feed.

### 2. Sensory Engine (Android CPU)
By offloading heavy matrix math to the NPU, the CPU remains free to drive the sensory APIs smoothly without jitter:
- **Haptic Renderer:** Maps the 4 texture axes directly to Android's `VibrationEffect.Composition` primitives (e.g., `PRIMITIVE_TICK`, `PRIMITIVE_THUD`, `PRIMITIVE_CLICK`).
- **Audio Renderer:** Generates synchronized, low-latency synthetic audio via Media3 (ExoPlayer). High roughness yields gritty, high-frequency transients, while smoother surfaces produce continuous, low-frequency acoustic feedback.

## 🚀 Getting Started

### Prerequisites
- **Android Studio:** Ladybug or newer.
- **Device:** Samsung Galaxy S25 Ultra (or a Snapdragon 8 Gen-series device with Hexagon NPU).
- **Android SDK:** API 35.

### Building the Project
1. Clone the repository.
2. Open the project in Android Studio.
3. Ensure `local.properties` is configured with your correct `sdk.dir`.
4. Build and deploy the `debug` variant to your physical device. *(Note: Emulators do not support Hexagon NPU hardware acceleration).*

## 📊 Performance & Calibration
- **Latency:** The complete pipeline (Segmentation -> Feature Extraction -> Haptic/Audio synthesis) is engineered to run in **< 20ms** to maintain the psychological "illusion of touch".
- **Centroid Calibration:** The material classification (`MaterialCentroids.kt`) is empirically calibrated against the model's actual output space (mean of on-device captures for Glass, Paper, Wood, Rocks, Sand, Fabric) to ensure accurate fallback categorization.

## 🤝 Team

| Name | GitHub | Email |
|---|---|---|
| Chisom Ogugu | [@chisomogugu](https://github.com/chisomogugu) | chisomogugu@gmail.com |
| Oritsejolomisan Mebaghanje | [@mebaghanjejolomi](https://github.com/mebaghanjejolomi) | mebaghanjejolomi@gmail.com |
| Madeline Rippin | [@mrippin1](https://github.com/mrippin1) | mrippin1@umbc.edu |
| Edmond Ndanji | [@2bTwist](https://github.com/2bTwist) | ndanjiedmond@gmail.com |
| Chris Dollo | [@chrisdollo](https://github.com/chrisdollo) | cdollo1@umbc.edu |

This project showcases the integration of state-of-the-art edge AI with deeply integrated Android hardware APIs. It demonstrates our ability to communicate vision, structure high-performance code, and deliver a compelling, novel user experience.

## 📄 License

This project is released under the [MIT License](LICENSE).
