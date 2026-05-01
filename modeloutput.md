# Texture Haptics — Model Exports

## Files

| File | Size | Purpose |
|------|------|---------|
| `efficientnet_lite0.onnx` | ~13.5 MB | Image encoder — convert to TFLite in Colab |
| `linear_head.onnx` | ~0.02 MB | Regression head — convert to TFLite in Colab |
| `colab_convert_encoder.ipynb` | — | Colab notebook that converts both ONNX → TFLite |
| `map_primitives.py` | — | Rule-based formula: 4 dimensions → 8 haptic weights |

After running the Colab notebook you will also have:
- `efficientnet_lite0.tflite` — drop into Android `assets/`
- `linear_head.tflite` — drop into Android `assets/`

---

## Pipeline

```
Camera image
    │
    ▼  resize 224×224, normalize to [-1, 1]
efficientnet_lite0.tflite
    │
    ▼  output: float32[1, 1280]  (feature vector)
linear_head.tflite
    │
    ▼  output: float32[1, 4]  (haptic dimensions)
map_primitives()
    │
    ▼  output: dict of 8 haptic primitive weights
```

---

## Model outputs

### 1. `efficientnet_lite0.tflite`
- **Input:** `float32[1, 224, 224, 3]` — RGB image, NHWC layout, pixel values normalized to `[-1.0, 1.0]`
  - Normalization: `(pixel / 255.0 - 0.5) / 0.5`
- **Output:** `float32[1, 1280]` — texture feature vector (not human-readable, passed directly to the head)

### 2. `linear_head.tflite`
- **Input:** `float32[1, 1280]` — raw output from the encoder (feature normalization is baked in)
- **Output:** `float32[1, 4]` — four haptic dimension scores, each in `[0.0, 1.0]`

| Index | Dimension | Low (→ 0.0) | High (→ 1.0) |
|-------|-----------|-------------|--------------|
| 0 | **rough** | smooth (glass, paper) | rough (rock, sand) |
| 1 | **hard** | soft (cloth, sand) | hard (glass, rock) |
| 2 | **friction** | slippery (glass, tile) | grippy (cloth, rubber) |
| 3 | **density** | sparse features (wood grain) | dense features (sand, fabric weave) |

**Example outputs for trained materials:**

| Material | rough | hard | friction | density |
|----------|-------|------|----------|---------|
| Rock | 0.85 | 0.94 | 0.70 | 0.33 |
| Glass | 0.05 | 0.96 | 0.08 | 0.05 |
| Sand | 0.57 | 0.23 | 0.63 | 0.89 |
| Cloth | 0.35 | 0.10 | 0.73 | 0.61 |
| Wood | 0.31 | 0.78 | 0.43 | 0.27 |
| Paper | 0.12 | 0.26 | 0.28 | 0.74 |

### 3. `map_primitives()` (rule-based, no model)
- **Input:** `[rough, hard, friction, density]` — the 4 floats from the head
- **Output:** weights for 8 Android haptic primitives, summing to 1.0

| Primitive | Triggered by |
|-----------|-------------|
| TICK | rough + hard + dense (cobblestone, gravel) |
| LOW_TICK | rough + soft + dense (sand, coarse fabric) |
| CLICK | hard + dense + smooth (tile, polished metal) |
| THUD | soft + sparse (foam, thick rubber) |
| SLOW_RISE | smooth + soft + grippy (velvet, neoprene) |
| QUICK_RISE | hard + smooth (glass, polished wood) |
| QUICK_FALL | slippery + smooth (glass, ice, paper) |
| SPIN | fixed at 0.05 (cannot be inferred from image) |

**Example output for glass:** `[rough=0.05, hard=0.96, friction=0.08, density=0.05]`
```
QUICK_FALL = 0.390
QUICK_RISE = 0.354
THUD       = 0.101
...
SPIN       = 0.050
```

---

## Training

- **Encoder:** EfficientNet-Lite0 pretrained on ImageNet (1.28M images, 1000 classes) — frozen, not fine-tuned
- **Head:** `Linear(1280 → 4) + Sigmoid`, trained on 6 materials × 20 simulated human ratings
- **Materials trained on:** rock, glass, sand, cloth, wood, paper
- **Training MAE:** 0.078 (target < 0.15)

---

## Android integration

```groovy
// build.gradle
implementation 'com.google.ai.edge.litert:litert:1.0.1'
```

```kotlin
// Preprocessing
// (pixel / 255.0f - 0.5f) / 0.5f  →  float32[1, 224, 224, 3] NHWC

// Inference
// 1. Run efficientnet_lite0.tflite  →  float32[1, 1280]
// 2. Run linear_head.tflite         →  float32[1, 4]
// 3. Call map_primitives() logic    →  8 haptic weights
// No manual feature normalization needed — baked into linear_head.tflite
```
