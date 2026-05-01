package com.tactilelens.app.ui.results

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tactilelens.app.AppContainer
import com.tactilelens.app.data.model.AnalysisResult
import com.tactilelens.app.data.model.Material
import com.tactilelens.app.data.model.TextureAxes
import com.tactilelens.app.ui.theme.DeepSpace
import com.tactilelens.app.ui.theme.GlowCyan
import com.tactilelens.app.ui.theme.NebulaBlue
import com.tactilelens.app.ui.theme.VividBlue

/**
 * Analysis Complete screen.
 *
 * Driven by an [AnalysisResult] from the analysis client and an [AppContainer]
 * for renderer access. The Material name area is a dropdown trigger that
 * lets the team A/B feel by overriding the ML's material guess at runtime.
 *
 * Material override flow: dropdown click → state mutation → LaunchedEffect
 * pushes new material to renderers → InteractiveGridZone re-renders with
 * the new per-material dot density.
 */
@Composable
fun ResultsScreen(
    imageUri: Uri?,
    analysisResult: AnalysisResult,
    container: AppContainer,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val axes = analysisResult.axes

    var currentMaterial by remember(analysisResult) {
        mutableStateOf(analysisResult.material)
    }
    var menuExpanded by remember { mutableStateOf(false) }

    // Push state to renderers whenever the user picks a different material.
    LaunchedEffect(currentMaterial, axes) {
        container.audio.setMaterial(currentMaterial)
        container.audio.setAxes(axes)
        container.haptic.onGestureStart(currentMaterial, axes, velocity = 0.6f)
    }

    // Clear renderer material on exit so the audio stream goes silent if
    // we navigate back to scanner.
    DisposableEffect(Unit) {
        onDispose {
            container.audio.setMaterial(null)
            container.haptic.onSwipeEnd()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        DeepSpace,
                        NebulaBlue.copy(alpha = 0.92f),
                        Color(0xFF040B12),
                    ),
                ),
            )
            .systemBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header with Back Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Text(
                    text = "ANALYSIS COMPLETE",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.titleMedium,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Top Section: Image and Stats
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Captured Image (Box 1)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(160.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .drawBehind {
                            drawRoundRect(
                                color = VividBlue.copy(alpha = 0.3f),
                                style = Stroke(width = 1.dp.toPx()),
                                cornerRadius = CornerRadius(32.dp.toPx()),
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (imageUri != null) {
                        val imageBitmap by rememberLoadedBitmap(context, imageUri)
                        imageBitmap?.let {
                            Image(
                                bitmap = it,
                                contentDescription = "Captured texture",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    } else {
                        Text(
                            text = "NO IMAGE",
                            color = Color.White.copy(alpha = 0.3f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    BackendLatencyPill(
                        backendLabel = analysisResult.backendLabel,
                        latencyMs = analysisResult.inferenceLatencyMs,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                    )
                }

                // Stats Section Container (Box 2)
                val pagerState = rememberPagerState(pageCount = { 2 })

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(160.dp),
                ) {
                    // Floating Confidence Indicator
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = (-24).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(GlowCyan, CircleShape),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "CONF: ${"%.0f".format(analysisResult.confidence * 100)}%",
                            color = GlowCyan.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                    }

                    // Swipable Content Box (The Blue Boundary)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .dashedBorder(
                                color = VividBlue.copy(alpha = 0.6f),
                                width = 2.dp,
                                radius = 24.dp,
                            )
                            .padding(12.dp),
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            if (page == 0) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 4.dp),
                                    verticalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    StatRow("ROUGHNESS", "%.2f".format(axes.roughness))
                                    StatRow("FLATBUMPY", "%.2f".format(axes.flatBumpy))
                                    StatRow("FRICTION", "%.2f".format(axes.friction))
                                    StatRow("HARDNESS", "%.2f".format(axes.hardness))
                                }
                            } else {
                                StatGraphView(axes)
                            }
                        }

                        // Page Indicators (Dots)
                        Row(
                            Modifier
                                .height(8.dp)
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            repeat(2) { iteration ->
                                val color = if (pagerState.currentPage == iteration) GlowCyan else Color.White.copy(alpha = 0.2f)
                                Box(
                                    modifier = Modifier
                                        .padding(2.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .size(6.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Material name (clickable dropdown trigger).
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { menuExpanded = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "{${displayName(currentMaterial, analysisResult.label)}}",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = "Change material",
                        tint = GlowCyan.copy(alpha = 0.8f),
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(DeepSpace),
                ) {
                    Material.values().forEach { mat ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = mat.display.uppercase(),
                                    color = Color.White,
                                    letterSpacing = 2.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            onClick = {
                                currentMaterial = mat
                                menuExpanded = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "PROCEDURAL / OTHER",
                                color = GlowCyan,
                                letterSpacing = 2.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        onClick = {
                            currentMaterial = null
                            menuExpanded = false
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Interactive grid touch zone (rebuilt for per-material density,
            // dot-crossing detection, and renderer plumbing).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color.White.copy(alpha = 0.02f))
                    .drawBehind {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.1f),
                            style = Stroke(width = 1.dp.toPx()),
                            cornerRadius = CornerRadius(32.dp.toPx()),
                        )
                    },
            ) {
                InteractiveGridZone(
                    material = currentMaterial,
                    axes = axes,
                    onDotCross = { velocity ->
                        container.audio.onContact(velocity)
                        container.haptic.onSwipeMove(currentMaterial, axes, velocity)
                    },
                )
            }
        }
    }
}

private fun displayName(material: Material?, fallback: String): String =
    material?.display?.uppercase() ?: fallback.uppercase()

@Composable
private fun StatGraphView(axes: TextureAxes) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GraphBar("ROUGHNESS", axes.roughness)
        GraphBar("FLATBUMPY", axes.flatBumpy)
        GraphBar("FRICTION", axes.friction)
        GraphBar("HARDNESS", axes.hardness)
    }
}

@Composable
private fun GraphBar(label: String, value: Float) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.05f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(value.coerceIn(0.01f, 1f))
                    .fillMaxHeight()
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(VividBlue, GlowCyan),
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "$label:",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Persistent backend + latency pill (locked decision Q8). Reads the
 * delegate that ran inference and the wall-clock latency from the
 * `AnalysisResult`. Green dot = NPU (the path we want demoed); amber
 * for any fallback (GPU, CPU, MOCK).
 */
@Composable
private fun BackendLatencyPill(
    backendLabel: String,
    latencyMs: Long,
    modifier: Modifier = Modifier,
) {
    val isNpu = backendLabel == "NPU"
    Row(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    color = if (isNpu) GlowCyan else Color(0xFFE6A23C),
                    shape = CircleShape,
                ),
        )
        Text(
            text = "$backendLabel · ${latencyMs} ms",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun Modifier.dashedBorder(color: Color, width: Dp, radius: Dp) = drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(
            width = width.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 20f), 0f),
        ),
        cornerRadius = CornerRadius(radius.toPx()),
    )
}

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
