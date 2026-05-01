# TactileLens: Project Overview & Vision

## 1. Executive Summary
**TactileLens** is an on-device AI application that translates visual textures into real-time haptic and audio feedback. It allows users to intuitively "feel" the physical world through their smartphones. By pointing the device camera at a surface, the AI analyzes visual microtextures (e.g., the grit of gravel or the smoothness of silk) and translates them into a complex, synchronized sensory signature.

## 2. Core Use Cases
- **Accessibility:** Equipping low-vision or blind individuals with the ability to perceive surface qualities, material properties, and environmental hazards (such as slick floors or rough terrain) safely through touch.
- **E-Commerce & Telepresence:** Bridging the digital divide by allowing users to "feel" digital content. A shopper can assess the texture of a garment online, or an explorer can physically interact with remote environments.

## 3. Technical Strategy & Constraints
To make the "illusion of touch" convincing, the system must react instantly to camera movements. Our engineering strategy revolves around extreme low-latency and hardware-specific optimization.

### Strict Sub-20ms Latency
For haptic feedback to feel directly connected to a visual stimulus, the entire loop—from frame capture to vibration motor actuation—must complete in under 20 milliseconds.

### Hardware Acceleration (Hexagon NPU)
We rely on the Qualcomm Snapdragon Hexagon NPU (specifically targeting the Galaxy S25 Ultra). We use **LiteRT (formerly TFLite)** coupled with the **Qualcomm QNN Delegate**.
- By offloading the dense mathematical operations of the ML pipeline (U2Net Segmentation + Texture Feature Extraction) to the NPU, we achieve single-digit millisecond inference times.
- This architectural decision leaves the smartphone's CPU entirely unburdened, allowing it to schedule Android's `VibratorManager` and Media3 audio threads without jitter or GC-induced frame drops.

## 4. The Sensory Translation Logic
The Machine Learning pipeline outputs a continuous float vector representing four physical axes:
1. **Roughness**
2. **Hardness**
3. **Friction**
4. **Density**

Instead of merely classifying a material (e.g., "This is Wood"), we map these continuous axes directly to sensory primitives:
- **Haptics:** Higher *roughness* maps to rapid, irregular `PRIMITIVE_TICK` pulses. Higher *hardness* maps to stiff `PRIMITIVE_CLICK` actuations.
- **Audio:** The same axes modulate a procedural audio engine. A rough surface generates high-frequency "gritty" acoustic transients, reinforcing the haptic sensation through the device's speakers.

## 5. Engineering Quality & Documentation
This project emphasizes robust, production-ready mobile architecture:
- **Zero-Copy Memory Management:** We use direct, pre-allocated `ByteBuffer` I/O for all ML model interactions to eliminate memory churn and garbage collection pauses.
- **Empirical Calibration:** Material centroids are actively calibrated against the model's measured on-device output space, ensuring that the theoretical ML models perform accurately in real-world lighting and camera conditions.
- **Clear Separation of Concerns:** The pipeline is strictly divided into `Vision` (CameraX), `Analysis` (LiteRT/NPU), and `Renderers` (Haptics/Audio), ensuring maintainable and readable code.
