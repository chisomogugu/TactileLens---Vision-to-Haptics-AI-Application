package com.tactilelens.app.ui.results

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tactilelens.app.AppContainer
import com.tactilelens.app.data.model.AnalysisResult
import com.tactilelens.app.data.model.Material
import com.tactilelens.app.data.model.TextureAxes
import com.tactilelens.app.ui.theme.Bone
import com.tactilelens.app.ui.theme.Carbon
import com.tactilelens.app.ui.theme.Iron
import com.tactilelens.app.ui.theme.LatencyGood
import com.tactilelens.app.ui.theme.LatencyWarn
import com.tactilelens.app.ui.theme.Mercury
import com.tactilelens.app.ui.theme.Pulse
import com.tactilelens.app.ui.theme.Slate
import kotlinx.coroutines.delay

/**
 * Results screen — instrument-grade redesign (Phase 1 of v2 UI).
 *
 * Three deliberate departures from the v1 layout:
 *  1. Numbers are gone from the user-facing axis rows. Each axis is shown as
 *     a polar-anchored scale (e.g. SMOOTH ──●─── COARSE) so the readout
 *     reads as a measurement instrument, not a developer HUD. Raw values
 *     still log to logcat for tuning.
 *  2. The previous dual-page swipable stats panel is collapsed to a single
 *     column of axis scales — page-1 was rows of numbers, page-2 was bars
 *     of the same numbers. Both were redundant once we had a single good
 *     visualization.
 *  3. The material chip is now a simple display (no curly braces) backed
 *     by a Pulse indicator dot. Clicking it still opens the dropdown for
 *     manual override.
 *
 * The Material override flow itself (state mutation → renderers re-driven)
 * is unchanged from v1.
 */
@Composable
fun ResultsScreen(
    imageUri: Uri?,
    segmentedBitmap: Bitmap?,
    analysisResult: AnalysisResult,
    container: AppContainer,
    onBack: () -> Unit,
    segmenterLatencyMs: Long? = null,
    segmenterBackendLabel: String? = null,
) {
    val context = LocalContext.current
    val axes = analysisResult.axes

    var currentMaterial by remember(analysisResult) {
        mutableStateOf(analysisResult.material)
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var dragCount by remember(analysisResult) { mutableStateOf(0) }

    LaunchedEffect(currentMaterial, axes) {
        container.audio.setMaterial(currentMaterial)
        container.audio.setAxes(axes)
        container.haptic.onGestureStart(
            material = currentMaterial,
            axes = axes,
            velocity = 0.6f,
            primitiveWeights = analysisResult.primitiveWeights,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            container.audio.setMaterial(null)
            container.haptic.onSwipeEnd()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Carbon)
            .systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Top app bar — back arrow + section label on the left, latency
            // pill on the right. All mono / label scale; deliberately quiet
            // so the chip + photo carry the page hierarchy.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Bone,
                    )
                }
                Text(
                    text = "RESULT",
                    color = Mercury,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                // Two-stage NPU pipeline: surface both latencies so the
                // "two models on Hexagon HTP" story lands. Segmenter runs
                // first (U2Net, ~8 ms), classifier second (EfficientNet
                // encoder + LinearHead, ~20-30 ms). Stacked vertically to
                // keep the top bar narrow on one line.
                Column(
                    modifier = Modifier.padding(end = 12.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (segmenterLatencyMs != null) {
                        BackendLatencyPill(
                            backendLabel = segmenterBackendLabel ?: "—",
                            latencyMs = segmenterLatencyMs,
                            prefix = "SEG",
                        )
                    }
                    BackendLatencyPill(
                        backendLabel = analysisResult.backendLabel,
                        latencyMs = analysisResult.inferenceLatencyMs,
                        prefix = "CLF",
                    )
                }
            }

            // 1px Iron divider — defines the top app bar without a heavy
            // shadow / background swap. Inset 24dp on each side per spacing.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(1.dp)
                    .background(Iron),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Captured photo block — kept compact so the grid below stays
            // the dominant element on the page. The segmentation visual
            // is supporting evidence, not the hero.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(160.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Slate)
                    .border(1.dp, Iron, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // Prefer the segmented (background-removed) bitmap so the
                // user sees what the model actually fed to the encoder —
                // that's the demo beat: "AI isolated this." Falls back to
                // the raw photo if segmentation isn't ready yet.
                val displayBitmap: ImageBitmap? = when {
                    segmentedBitmap != null -> remember(segmentedBitmap) { segmentedBitmap.asImageBitmap() }
                    imageUri != null -> rememberLoadedBitmap(context, imageUri).value
                    else -> null
                }
                if (displayBitmap != null) {
                    Image(
                        bitmap = displayBitmap,
                        contentDescription = "Captured + segmented texture",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = "NO IMAGE",
                        color = Mercury,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Material chip + confidence side by side.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MaterialChip(
                    material = currentMaterial,
                    fallbackLabel = analysisResult.label,
                    onClick = { menuExpanded = true },
                )
                Spacer(modifier = Modifier.weight(1f))
                ConfidenceReadout(value = analysisResult.confidence)

                MaterialDropdown(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    onPick = { picked ->
                        currentMaterial = picked
                        menuExpanded = false
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Four polar axis scales, compacted: no dedicated name text.
            // The pole-adjective pair (SMOOTH/COARSE etc.) uniquely
            // identifies each axis, so the title row was redundant. Tight
            // spacing here yields more vertical real estate to the haptic
            // grid below, which is the demo's hero interaction.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AxisScaleRow(leftPole = "SMOOTH", rightPole = "COARSE", value = axes.roughness, delayMs = 0)
                AxisScaleRow(leftPole = "SOFT", rightPole = "HARD", value = axes.hardness, delayMs = 80)
                AxisScaleRow(leftPole = "SLICK", rightPole = "STICKY", value = axes.friction, delayMs = 160)
                AxisScaleRow(leftPole = "SPARSE", rightPole = "DENSE", value = axes.density, delayMs = 240)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Touch-to-feel grid. The hero interaction. Wrapped in a Slate
            // panel with an Iron border so it reads as a distinct
            // instrument surface, not a continuation of the Carbon canvas.
            // Bone-on-Slate dots remain readable; Pulse on touch.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "TOUCH TO FEEL",
                    color = Bone,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = "DRAG · ${dragCount.toString().padStart(2, '0')}",
                    color = Mercury,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Iron)
                    .border(1.dp, Mercury, RoundedCornerShape(6.dp)),
            ) {
                InteractiveGridZone(
                    material = currentMaterial,
                    axes = axes,
                    onDotCross = { velocity ->
                        dragCount++
                        container.audio.onContact(velocity)
                        container.haptic.onSwipeMove(currentMaterial, axes, velocity)
                    },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

// ----------------------------------------------------------------------
// Sub-components
// ----------------------------------------------------------------------

/**
 * Polar-anchored axis readout, compact. The pole-adjective pair encodes
 * the axis identity (SMOOTH/COARSE = roughness, SOFT/HARD = hardness,
 * SLICK/STICKY = friction, SPARSE/DENSE = density), so no separate title
 * is needed. Single row: pole label, ruler with marker, opposite pole.
 *
 * On entry the marker sweeps from 0 to its target over 480 ms with an
 * 80 ms-per-row stagger driven by [delayMs]. Reads as a measurement
 * "settling" rather than a static dump.
 */
@Composable
private fun AxisScaleRow(
    leftPole: String,
    rightPole: String,
    value: Float,
    delayMs: Int = 0,
) {
    val target = value.coerceIn(0f, 1f)
    val animated = remember(target) { Animatable(0f) }
    LaunchedEffect(target) {
        if (delayMs > 0) delay(delayMs.toLong())
        animated.animateTo(
            targetValue = target,
            animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        )
    }
    val clamped = animated.value
    Row(
        modifier = Modifier.fillMaxWidth().height(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = leftPole,
            color = Mercury,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            modifier = Modifier.width(56.dp),
        )
        // 1dp Iron rule with a Bone fill leading to a 3dp Pulse marker.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Iron),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped.coerceAtLeast(0.01f))
                    .height(1.dp)
                    .background(Bone),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.fillMaxWidth(clamped.coerceAtMost(0.99f)).weight(1f, false))
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .background(Pulse),
                )
            }
        }
        Text(
            text = rightPole,
            color = Mercury,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            modifier = Modifier.width(56.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/**
 * Material chip — Pulse indicator dot, DETECTED label (Mercury), material
 * name (Bone Display). Tap opens [MaterialDropdown] for manual override.
 * No more curly braces. No more 4-character letter-spacing.
 */
@Composable
private fun MaterialChip(
    material: Material?,
    fallbackLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Slate)
            .border(1.dp, Iron, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Pulse),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = "DETECTED",
                color = Mercury,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
            )
            Text(
                text = displayName(material, fallbackLabel),
                color = Bone,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.ArrowDropDown,
            contentDescription = "Change material",
            tint = Mercury,
        )
    }
}

@Composable
private fun MaterialDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPick: (Material?) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(Slate),
    ) {
        Material.values().forEach { mat ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = mat.display.uppercase(),
                        color = Bone,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                onClick = { onPick(mat) },
            )
        }
        DropdownMenuItem(
            text = {
                Text(
                    text = "OPEN VOCABULARY",
                    color = Pulse,
                    style = MaterialTheme.typography.labelMedium,
                )
            },
            onClick = { onPick(null) },
        )
    }
}

/**
 * Confidence readout — large mono number + label beneath. The single
 * surviving raw number on the screen, kept because percentage genuinely
 * reads faster than a glyph here.
 */
@Composable
private fun ConfidenceReadout(value: Float) {
    val pct = (value * 100).coerceIn(0f, 100f).toInt()
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = "${pct}%",
            color = Bone,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "CONFIDENCE",
            color = Mercury,
            style = MaterialTheme.typography.labelMedium,
            fontSize = 9.sp,
        )
    }
}

/**
 * Backend + latency pill (locked decision Q8). Pulse dot when the fast
 * path bound (NPU); Mercury dot for any fallback (CPU/GPU/MOCK). Mono
 * text. Sits in the top app bar instead of overlaying the photo so the
 * photo block is visually unbroken.
 */
@Composable
private fun BackendLatencyPill(
    backendLabel: String,
    latencyMs: Long,
    modifier: Modifier = Modifier,
    prefix: String? = null,
) {
    val isNpu = backendLabel.contains("NPU")
    // Traffic-light on the ms number itself. Universal thresholds: both
    // models should be NPU-fast, so anything past 30 ms is "noticeable"
    // and past 80 ms is "user perceives the wait." A glance at the pill
    // colour tells you the budget without reading the number.
    val perfColor = when {
        latencyMs <= 30L -> LatencyGood
        latencyMs <= 80L -> LatencyWarn
        else -> Pulse
    }
    val labelText = if (prefix != null) "$prefix · $backendLabel · " else "$backendLabel · "
    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(color = Bone)) { append(labelText) }
        withStyle(SpanStyle(color = perfColor)) { append("${latencyMs}ms") }
    }
    Row(
        modifier = modifier
            .background(color = Slate, shape = RoundedCornerShape(2.dp))
            .border(1.dp, Iron, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (isNpu) Pulse else Mercury),
        )
        Text(
            text = annotated,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun displayName(material: Material?, fallback: String): String =
    material?.display?.uppercase() ?: fallback.uppercase()

@Composable
private fun rememberLoadedBitmap(
    context: Context,
    imageUri: Uri?,
) = produceState<ImageBitmap?>(
    initialValue = null,
    key1 = imageUri,
    key2 = context,
) {
    value = imageUri?.let { loadBitmapFromUri(context, it)?.asImageBitmap() }
}

private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = false
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    }.getOrNull()
}
