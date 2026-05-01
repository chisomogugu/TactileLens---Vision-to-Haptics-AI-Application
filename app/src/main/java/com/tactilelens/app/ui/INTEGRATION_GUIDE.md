# TactileLens — Architecture & Integration Guide

## Overview

TactileLens is a **single-Activity Android app** built entirely with Jetpack Compose. There is no complex navigation library — screen switching is handled by a simple Kotlin `enum` and a state variable.

---

## Project Structure

```
app/src/main/java/com/example/tactilelens/
├── MainActivity.kt          ← Entry point, all scanner logic, navigation
└── ui/
    ├── ResultsScreen.kt     ← Results display, graph, interactive grid
    └── theme/
        ├── Color.kt         ← Brand color palette
        ├── Theme.kt         ← Material3 theme setup
        └── Type.kt          ← Typography

app/src/main/assets/
├── thermal_map.png          ← Mock analysis image #1 (Thermal)
└── depth_wireframe.png      ← Mock analysis image #2 (Depth/Wireframe)
```

---

## App Flow (End to End)

1. **App Opens**: Checks for Camera Permission.
2. **Live Camera Preview**: Displayed via CameraX once permission is granted.
3. **Capture**: User presses shutter → Photo saved to cache → `imageUri` is set.
4. **Scanning Sequence**: Triggered by `imageUri != null`.
   - **Stage 0 (0–3.5s)**: Scan DOWN → Reveals Thermal Map over the original photo.
   - **Stage 1 (3.5–7s)**: Scan UP → Reveals Depth Wireframe over the Thermal Map.
5. **Results Screen**: After 7 seconds, `currentScreen` switches to `AppScreen.Results`.
   - Displays: Original Photo + Analysis Stats + Swipable Graph + Interactive Dotted Grid.

---

## How to Connect Real Data

### 1. Hooking the Inference Call
In `MainActivity.kt`, look for the `LaunchedEffect(imageUri)` block around line 141. Replace the mock delay with your API call:

```kotlin
// 🔌 REAL DATA INTEGRATION:
LaunchedEffect(imageUri) {
    if (imageUri != null && currentScreen == AppScreen.Scanner) {
        // 1. Call your model here:
        // val response = myApi.analyzeTexture(imageUri)
        
        // 2. Wait for processing (or keep the 7s delay for UX)
        kotlinx.coroutines.delay(7000) 
        
        // 3. Store result in state and switch screen
        // hapticData = response.data
        currentScreen = AppScreen.Results
    }
}
```

### 2. Replacing Mock Images
The scan reveal uses two images from `app/src/main/assets/`. To use real results from your model, update the `ScannerRevealEffect` call in `MainActivity.kt`:

```kotlin
// 🔌 REAL DATA INTEGRATION:
// Replace loadBitmapFromAssets with a loader that fetches from your API response
val thermalBitmap by produceState(...) { value = decodeFromApi(response.thermalUrl) }
```

### 3. Populating Results
The `ResultsScreen` currently uses a mock `HapticData()` object. Update the `ResultsScreen` composable to accept the real data object:

```kotlin
// In ResultsScreen.kt:
@Composable
fun ResultsScreen(
    imageUri: Uri?,
    hapticData: HapticData, // Add this parameter
    onBack: () -> Unit
) { ... }
```

---

## Technical Features

### Interactive Dotted Grid
- **File**: `ResultsScreen.kt`
- **Logic**: Uses a `Canvas` that tracks pointer input. Each dot calculates its distance to your finger and dynamically scales/glows in `GlowCyan`.

### Swipable Stats Graph
- **File**: `ResultsScreen.kt`
- **Logic**: A `HorizontalPager` with 2 pages. 
  - **Page 1**: Textual stats (`StatRow`).
  - **Page 2**: Visual bars (`StatGraphView`) with a `VividBlue` to `GlowCyan` gradient.

### Scan Reveal Effect
- **File**: `MainActivity.kt`
- **Logic**: A coroutine-driven `Canvas` that uses `clipRect` to "wipe" one image over another in sync with a moving scanner line.

---

## Color Palette Reference
- **VividBlue** (`#3253DC`): Main accents, borders, and graph bars.
- **GlowCyan** (`#61E7FF`): Interactive highlights, dots, and latency indicator.
- **DeepSpace** (`#04111B`): Background base.
