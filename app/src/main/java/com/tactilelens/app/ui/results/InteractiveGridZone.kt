package com.tactilelens.app.ui.results

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import com.tactilelens.app.data.model.Material
import com.tactilelens.app.data.model.TextureAxes
import com.tactilelens.app.ui.theme.GlowCyan
import com.tactilelens.app.ui.theme.VividBlue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The interactive tactile surface.
 *
 * Three jobs:
 *  - Visual: renders a dotted grid whose density encodes the material.
 *  - Detection: as the finger moves, detects each time it crosses into a new
 *    grid cell (the "dot crossing event").
 *  - Plumbing: each crossing fires [onDotCross] with a normalized [0, 1]
 *    velocity so the caller can drive renderers.
 *
 * Dot crossings are throttled at [CROSSING_THROTTLE_MS] (40 ms) to protect
 * against the S25 Ultra LOW_TICK self-cancellation pattern. Excess
 * crossings during a fast swipe are dropped.
 *
 * See ARCHITECTURE.md §7 for the design rationale.
 */
@Composable
fun InteractiveGridZone(
    material: Material?,
    axes: TextureAxes,
    modifier: Modifier = Modifier,
    onDotCross: (velocity: Float) -> Unit,
) {
    var touchPosition by remember { mutableStateOf<Offset?>(null) }

    // Tracks the dot the finger was last associated with, plus timestamps for
    // velocity estimation and throttle gating. Held outside Compose state on
    // purpose: these mutate per pointer event without needing recomposition.
    val state = remember { GridState() }

    val spacingPx = dotSpacingFor(material, axes)

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(material) {
                // Reset crossing state when material changes (different grid).
                state.reset()
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val pos = change.position
                        if (change.pressed) {
                            touchPosition = pos
                            handlePointer(pos, spacingPx, state, onDotCross)
                        } else {
                            touchPosition = null
                            state.reset()
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val interactionRadius = spacingPx * 4f

            val cols = (width / spacingPx).toInt() + 1
            val rows = (height / spacingPx).toInt() + 1

            for (col in 0..cols) {
                for (row in 0..rows) {
                    val dotPos = Offset(col * spacingPx, row * spacingPx)
                    var radius = 4f
                    var color = VividBlue.copy(alpha = 0.25f)

                    touchPosition?.let { touch ->
                        val distance = (dotPos - touch).getDistance()
                        if (distance < interactionRadius) {
                            val intensity = 1f - (distance / interactionRadius)
                            radius = 4f + (12f * intensity)
                            color = lerp(
                                VividBlue.copy(alpha = 0.25f),
                                GlowCyan,
                                intensity,
                            )
                        }
                    }

                    drawCircle(color = color, radius = radius, center = dotPos)
                }
            }
        }
    }
}

/**
 * Per-material dot spacing in pixels. Spacing encodes surface character:
 * dense for fine surfaces (sand), sparse for coarse surfaces (rocks). For
 * unknown materials, roughness drives density on a continuous scale.
 */
private fun dotSpacingFor(material: Material?, axes: TextureAxes): Float = when (material) {
    Material.SAND -> 32f      // Dense fine grit
    Material.GLASS -> 38f     // Densest after sand; very smooth + flat
    Material.PAPER -> 46f     // Medium-dense, smooth with subtle grain
    Material.FABRIC -> 50f    // Fine weave, between paper and wood
    Material.WOOD -> 64f      // Medium, warm contact
    Material.ROCKS -> 100f    // Sparse, big bumps
    null -> {
        // Procedural: rough → dense (32 px), smooth → sparse (96 px).
        val r = axes.roughness.coerceIn(0f, 1f)
        96f - r * 64f
    }
}

/**
 * Handles a pointer move: detects dot-cell transitions, estimates velocity
 * from finger speed, applies the 40 ms throttle, fires the callback.
 */
private fun handlePointer(
    pos: Offset,
    spacingPx: Float,
    state: GridState,
    onDotCross: (Float) -> Unit,
) {
    val now = SystemClock.uptimeMillis()
    val col = (pos.x / spacingPx).roundToInt()
    val row = (pos.y / spacingPx).roundToInt()
    val cellId = (row * 1000) + col

    // Velocity: distance per ms, normalized against ~2000 px/sec → 1.0 cap.
    val prev = state.lastPos
    val velocity = if (prev != null) {
        val dt = max(1L, now - state.lastSampleMs)
        val dist = hypot(abs(pos.x - prev.x), abs(pos.y - prev.y))
        val pxPerMs = dist / dt
        (pxPerMs / 2f).coerceIn(0f, 1f)
    } else {
        0.4f
    }
    state.lastPos = pos
    state.lastSampleMs = now

    if (state.lastCellId != null && state.lastCellId != cellId) {
        if (now - state.lastCrossMs >= CROSSING_THROTTLE_MS) {
            onDotCross(velocity)
            state.lastCrossMs = now
        }
    }
    state.lastCellId = cellId
}

/** Mutable state held across pointer events. */
private class GridState {
    var lastCellId: Int? = null
    var lastCrossMs: Long = 0L
    var lastPos: Offset? = null
    var lastSampleMs: Long = 0L

    fun reset() {
        lastCellId = null
        lastCrossMs = 0L
        lastPos = null
        lastSampleMs = 0L
    }
}

/**
 * 40 ms minimum between crossings. Protects against the S25 Ultra LOW_TICK
 * self-cancellation pattern. See ARCHITECTURE.md §11.
 */
private const val CROSSING_THROTTLE_MS = 40L
