package com.example.tactilelens.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tactilelens.ui.theme.DeepSpace
import com.example.tactilelens.ui.theme.GlowCyan
import com.example.tactilelens.ui.theme.NebulaBlue
import com.example.tactilelens.ui.theme.VividBlue

data class HapticData(
    val name: String = "TEXTURED FABRIC",
    val latency: String = "42ms",
    val roughness: Float = 0.1f,
    val flatBumpy: Float = 0.2f,
    val friction: Float = 0.1f,
    val hardness: Float = 0.05f
)

@Composable
fun ResultsScreen(
    imageUri: Uri?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val mockData = HapticData()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        DeepSpace,
                        NebulaBlue.copy(alpha = 0.92f),
                        Color(0xFF040B12)
                    )
                )
            )
            .systemBarsPadding()
            .padding(bottom = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header with Back Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Text(
                    text = "ANALYSIS COMPLETE",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.titleMedium,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Top Section: Image and Stats
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 24.dp), // Add top padding to clear the floating latency label
                horizontalArrangement = Arrangement.spacedBy(24.dp)
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
                                cornerRadius = CornerRadius(32.dp.toPx())
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (imageUri != null) {
                        val imageBitmap by rememberLoadedBitmap(context, imageUri)
                        imageBitmap?.let {
                            Image(
                                bitmap = it,
                                contentDescription = "Captured texture",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    } else {
                        Text(
                            text = "NO IMAGE",
                            color = Color.White.copy(alpha = 0.3f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // Stats Section Container (Box 2)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(160.dp)
                ) {
                    // Floating Latency Indicator (STRICTLY OUTSIDE AND ABOVE)
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = (-24).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(GlowCyan, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "LATENCY: ${mockData.latency}",
                            color = GlowCyan.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    // Stats Panel Box (The Blue Boundary)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .dashedBorder(
                                color = VividBlue.copy(alpha = 0.6f),
                                width = 2.dp,
                                radius = 24.dp
                            )
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatRow("ROUGHNESS", mockData.roughness)
                        StatRow("FLATBUMPY", mockData.flatBumpy)
                        StatRow("FRICTION", mockData.friction)
                        StatRow("HARDNESS", mockData.hardness)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Name
            Text(
                text = "{${mockData.name}}",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Dotted Grid Touch Zone
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
                            cornerRadius = CornerRadius(32.dp.toPx())
                        )
                    }
            ) {
                InteractiveGridZone()

                Text(
                    text = "DOTTED GRID TOUCH ZONE",
                    color = Color.White.copy(alpha = 0.3f),
                    style = MaterialTheme.typography.titleMedium,
                    letterSpacing = 2.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: Any) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value.toString(),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun InteractiveGridZone(modifier: Modifier = Modifier) {
    var touchPosition by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pos = event.changes.firstOrNull()?.position
                        if (event.changes.any { it.pressed }) {
                            touchPosition = pos
                        } else {
                            touchPosition = null
                        }
                    }
                }
            }
    ) {
        val spacing = 60f
        val interactionRadius = 300f

        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            for (x in 0..width.toInt() step spacing.toInt()) {
                for (y in 0..height.toInt() step spacing.toInt()) {
                    val dotPos = Offset(x.toFloat(), y.toFloat())
                    var radius = 4f
                    var color = VividBlue.copy(alpha = 0.25f)

                    touchPosition?.let { touch ->
                        val distance = (dotPos - touch).getDistance()
                        if (distance < interactionRadius) {
                            val intensity = 1f - (distance / interactionRadius)
                            // Scale up dots closer to touch
                            radius = 4f + (12f * intensity)
                            // Interpolate color towards theme GlowCyan
                            color = lerp(
                                VividBlue.copy(alpha = 0.25f),
                                GlowCyan,
                                intensity
                            )
                        }
                    }

                    drawCircle(
                        color = color,
                        radius = radius,
                        center = dotPos
                    )
                }
            }
        }
    }
}

fun Modifier.dashedBorder(color: Color, width: Dp, radius: Dp) = drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(
            width = width.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 20f), 0f)
        ),
        cornerRadius = CornerRadius(radius.toPx())
    )
}

@Composable
private fun rememberLoadedBitmap(
    context: Context,
    imageUri: Uri?
) = produceState<ImageBitmap?>(
    initialValue = null,
    key1 = imageUri,
    key2 = context
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
