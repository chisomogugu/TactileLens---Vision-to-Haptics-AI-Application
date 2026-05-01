package com.tactilelens.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tactilelens.app.data.analysis.SegmentationResult
import com.tactilelens.app.data.model.AnalysisResult
import com.tactilelens.app.ui.results.ResultsScreen
import com.tactilelens.app.ui.theme.Bone
import com.tactilelens.app.ui.theme.Carbon
import com.tactilelens.app.ui.theme.Iron
import com.tactilelens.app.ui.theme.Mercury
import com.tactilelens.app.ui.theme.Pulse
import com.tactilelens.app.ui.theme.Slate
import com.tactilelens.app.ui.theme.TactileLensTheme
import java.io.File
import androidx.camera.core.Preview as CameraXPreview
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appContainer = AppContainer(applicationContext)
        setContent {
            TactileLensTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScannerApp(appContainer)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appContainer.start()
    }

    override fun onStop() {
        super.onStop()
        appContainer.stop()
    }
}

enum class AppScreen { Scanner, Results }

@Composable
fun ScannerApp(container: AppContainer) {
    val context = LocalContext.current
    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.Scanner) }
    var imageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var analysisResult by remember { mutableStateOf<AnalysisResult?>(null) }
    // Phase 2: U2Net result. Null until segmentation completes; the
    // scanner reveal animation falls back to mock thermal/depth assets
    // for the brief window before the model is done.
    var segmentation by remember { mutableStateOf<SegmentationResult?>(null) }
    // Normalized [0, 1] tap location on the camera preview at capture time.
    // Drives U2Net's tap-steering: the connected salient region containing
    // this point becomes the encoder crop, instead of "whatever U2Net thinks
    // is salient globally." Null when capture was triggered by the shutter
    // button (legacy behavior — global saliency still works).
    var normalizedTap by remember { mutableStateOf<Offset?>(null) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(imageUri) {
        val captured = imageUri
        if (captured != null && currentScreen == AppScreen.Scanner) {
            // Two-stage pipeline:
            //   1. Run U2Net first (NPU, ~8 ms) so the reveal animation
            //      mounts as soon as the saliency / mask / cropped bitmaps
            //      are available. While in flight the scanner shows the
            //      captured photo + basic ScannerOverlay sweep.
            //   2. Hand the segmentation off to the analysis client to
            //      avoid running U2Net a second time. The encoder + head
            //      run inside analyze().
            // The 2.6 s delay matches ScannerRevealEffect's 3 x 0.8 s
            // passes plus a small buffer; ML pipeline finishes well inside
            // it on the S25 Ultra.
            // Snapshot tap location into a local val so the closure below
            // sees a stable value even if state changes during capture.
            val tapAtCapture = normalizedTap
            coroutineScope {
                val seg = withContext(Dispatchers.Default) {
                    runCatching {
                        val bm = loadBitmapFromUri(context, captured)
                            ?: error("could not decode captured photo")
                        if (tapAtCapture != null) {
                            val tapPxX = (tapAtCapture.x * bm.width).toInt()
                                .coerceIn(0, bm.width - 1)
                            val tapPxY = (tapAtCapture.y * bm.height).toInt()
                                .coerceIn(0, bm.height - 1)
                            container.segmenter.segment(bm, tapPxX, tapPxY)
                        } else {
                            container.segmenter.segment(bm)
                        }
                    }.onFailure {
                        Log.e("TactileLensML", "U2Net segmentation failed", it)
                    }.getOrNull()
                }
                segmentation = seg
                val analysisDeferred = async(Dispatchers.Default) {
                    container.analysis.analyze(captured, seg)
                }
                delay(2600)
                analysisResult = analysisDeferred.await()
            }
            currentScreen = AppScreen.Results
        }
    }

    val imageCapture = remember { ImageCapture.Builder().build() }

    fun takePicture() {
        val photoFile = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    imageUri = Uri.fromFile(photoFile)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("ScannerApp", "Photo capture failed: ${exc.message}", exc)
                }
            }
        )
    }

    if (currentScreen == AppScreen.Scanner) {
        ScannerScreen(
            imageUri = imageUri,
            hasCameraPermission = hasCameraPermission,
            imageCapture = imageCapture,
            segmentation = segmentation,
            onCapture = {
                normalizedTap = null
                takePicture()
            },
            onTapCapture = { nx, ny ->
                normalizedTap = Offset(nx, ny)
                takePicture()
            },
            onClearImage = {
                imageUri = null
                segmentation = null
                normalizedTap = null
            },
        )
    } else {
        val result = analysisResult
        if (result == null) {
            // Defensive: if we somehow land on Results without a result,
            // treat back as a hard reset to scanner.
            currentScreen = AppScreen.Scanner
            imageUri = null
            segmentation = null
        } else {
            ResultsScreen(
                imageUri = imageUri,
                segmentedBitmap = segmentation?.cropped,
                analysisResult = result,
                container = container,
                onBack = {
                    currentScreen = AppScreen.Scanner
                    imageUri = null
                    analysisResult = null
                    segmentation = null
                },
                segmenterLatencyMs = segmentation?.inferenceMs,
                segmenterBackendLabel = container.segmenter.backendLabel,
            )
        }
    }
}

@Composable
private fun ScannerScreen(
    imageUri: Uri?,
    hasCameraPermission: Boolean,
    imageCapture: ImageCapture,
    segmentation: SegmentationResult?,
    onCapture: () -> Unit,
    onTapCapture: (normalizedX: Float, normalizedY: Float) -> Unit,
    onClearImage: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Carbon,
                        Slate,
                        Carbon
                    )
                )
            )
    ) {
        AnimatedRadarBackdrop()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .systemBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "TEXTURE SENSE",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Camera View Area
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .shadow(
                        elevation = 32.dp,
                        shape = RoundedCornerShape(28.dp),
                        spotColor = Pulse.copy(alpha = 0.4f)
                    ),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.03f)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(28.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Slate),
                    contentAlignment = Alignment.Center
                ) {
                    if (imageUri != null) {
                        val context = LocalContext.current
                        val imageBitmap by rememberLoadedBitmap(context, imageUri)

                        // U2Net output drives the reveal directly. While
                        // segmentation is in flight (or if it failed) we
                        // show only the captured photo + a basic sweep —
                        // never any mock placeholder bitmaps.
                        val saliencyImg by produceState<ImageBitmap?>(initialValue = null, key1 = segmentation) {
                            value = segmentation?.saliency?.asImageBitmap()
                        }
                        val maskImg by produceState<ImageBitmap?>(initialValue = null, key1 = segmentation) {
                            value = segmentation?.maskWithBbox?.asImageBitmap()
                        }
                        val croppedImg by produceState<ImageBitmap?>(initialValue = null, key1 = segmentation) {
                            value = segmentation?.cropped?.asImageBitmap()
                        }

                        if (imageBitmap != null) {
                            val sal = saliencyImg
                            val mask = maskImg
                            val crop = croppedImg
                            if (sal != null && mask != null && crop != null) {
                                ScannerRevealEffect(
                                    originalBitmap = imageBitmap!!,
                                    saliencyBitmap = sal,
                                    maskBitmap = mask,
                                    croppedBitmap = crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Image(
                                    bitmap = imageBitmap!!,
                                    contentDescription = "Captured image",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                                ScannerOverlay(active = true)
                            }
                        }

                        // Red X Button
                        IconButton(
                            onClick = onClearImage,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Retake",
                                tint = Color.Red.copy(alpha = 0.9f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        if (hasCameraPermission) {
                            // Live preview is itself the capture surface: tapping
                            // anywhere fires onTapCapture(normalizedX, normalizedY).
                            // The shutter button below still works for "no specific
                            // target" capture (legacy global-saliency path).
                            var previewSize by remember { mutableStateOf(IntSize.Zero) }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onSizeChanged { previewSize = it }
                                    .pointerInput(Unit) {
                                        detectTapGestures { tap ->
                                            val w = previewSize.width
                                            val h = previewSize.height
                                            if (w > 0 && h > 0) {
                                                val nx = (tap.x / w).coerceIn(0f, 1f)
                                                val ny = (tap.y / h).coerceIn(0f, 1f)
                                                onTapCapture(nx, ny)
                                            }
                                        }
                                    },
                            ) {
                                CameraPreview(
                                    imageCapture = imageCapture,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Text(
                                    text = "TAP A SURFACE",
                                    color = Color.White.copy(alpha = 0.65f),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 4.sp,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 12.dp),
                                )
                            }
                        } else {
                            Text(
                                text = "CAMERA PERMISSION REQUIRED",
                                color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.titleMedium,
                                letterSpacing = 2.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Shutter Button
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .border(4.dp, Color.White, CircleShape)
                    .clickable { 
                        if (imageUri == null && hasCameraPermission) {
                            onCapture() 
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(if (imageUri == null && hasCameraPermission) Color.White else Color.White.copy(alpha = 0.5f))
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun CameraPreview(
    imageCapture: ImageCapture,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = CameraXPreview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                } catch (exc: Exception) {
                    Log.e("CameraPreview", "Use case binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    )
}

@Composable
fun ScannerRevealEffect(
    originalBitmap: ImageBitmap,
    saliencyBitmap: ImageBitmap,
    maskBitmap: ImageBitmap,
    croppedBitmap: ImageBitmap,
    modifier: Modifier = Modifier,
) {
    // 3-stage reveal mirroring the U2Net pipeline, scan line ping-pongs
    // top->bottom, bottom->top, top->bottom.
    //   Stage 0: Original     -> Saliency
    //   Stage 1: Saliency     -> Mask + Bbox
    //   Stage 2: Mask + Bbox  -> Cropped foreground
    //   Stage 3: hold on Cropped
    var stage by remember { mutableStateOf(0) }
    var rawProgress by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        // Each stage: 0.8 s. Total ~2.4 s. NPU inference is ~8 ms so the
        // capture-to-Results time is set entirely by this animation.
        val steps = 50
        val stageMs = 800L
        val stepDuration = stageMs / steps
        // Stage 0: scan down
        for (i in 0..steps) {
            rawProgress = i / steps.toFloat()
            kotlinx.coroutines.delay(stepDuration)
        }
        stage = 1
        // Stage 1: scan up
        for (i in steps downTo 0) {
            rawProgress = i / steps.toFloat()
            kotlinx.coroutines.delay(stepDuration)
        }
        stage = 2
        // Stage 2: scan down again
        for (i in 0..steps) {
            rawProgress = i / steps.toFloat()
            kotlinx.coroutines.delay(stepDuration)
        }
        stage = 3
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val dstSize = IntSize(width.toInt(), height.toInt())

        // Center-crop semantics matching Image(ContentScale.Crop). Without
        // this the transition from the loading-state Image to this Canvas
        // visibly squeezes the captured photo because Canvas.drawImage's
        // default behavior stretches src to dstSize.
        fun cropFitSrc(bitmap: ImageBitmap): Pair<IntOffset, IntSize> {
            val bAspect = bitmap.width.toFloat() / bitmap.height
            val cAspect = width / height
            return if (bAspect > cAspect) {
                val srcW = (bitmap.height * cAspect).toInt()
                IntOffset((bitmap.width - srcW) / 2, 0) to IntSize(srcW, bitmap.height)
            } else {
                val srcH = (bitmap.width / cAspect).toInt()
                IntOffset(0, (bitmap.height - srcH) / 2) to IntSize(bitmap.width, srcH)
            }
        }

        fun drawCropFit(bitmap: ImageBitmap) {
            val (so, ss) = cropFitSrc(bitmap)
            drawImage(bitmap, srcOffset = so, srcSize = ss, dstSize = dstSize)
        }

        fun drawScanLine(lineY: Float) {
            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Pulse, Color.Transparent),
                    startY = lineY - 25f, endY = lineY + 25f,
                ),
                start = Offset(0f, lineY), end = Offset(width, lineY), strokeWidth = 6f,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.8f),
                start = Offset(0f, lineY), end = Offset(width, lineY), strokeWidth = 2f,
            )
        }

        when (stage) {
            0 -> {
                drawCropFit(originalBitmap)
                val lineY = height * rawProgress
                clipRect(top = 0f, bottom = lineY) { drawCropFit(saliencyBitmap) }
                drawScanLine(lineY)
            }
            1 -> {
                drawCropFit(saliencyBitmap)
                val lineY = height * rawProgress
                clipRect(top = lineY, bottom = height) { drawCropFit(maskBitmap) }
                drawScanLine(lineY)
            }
            2 -> {
                drawCropFit(maskBitmap)
                val lineY = height * rawProgress
                clipRect(top = 0f, bottom = lineY) { drawCropFit(croppedBitmap) }
                drawScanLine(lineY)
            }
            else -> {
                drawCropFit(croppedBitmap)
            }
        }
    }
}

@Composable
private fun ScannerOverlay(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "scanner")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scannerSweep"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val frameColor = Pulse.copy(alpha = 0.9f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(16f, 12f), 0f)

            drawRoundRect(
                color = frameColor.copy(alpha = 0.3f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 3f,
                    pathEffect = dash
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(40f, 40f)
            )

            drawLine(
                color = Pulse.copy(alpha = if (active) 0.85f else 0.35f),
                start = Offset(0f, height * sweep),
                end = Offset(width, height * sweep),
                strokeWidth = 6f,
                cap = StrokeCap.Round
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (active) Pulse else Color.White.copy(alpha = 0.45f), CircleShape)
            )
        }
    }
}

@Composable
private fun AnimatedRadarBackdrop() {
    val transition = rememberInfiniteTransition(label = "background")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift"
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .alpha(0.65f)
    ) {
        val width = size.width
        val height = size.height

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Pulse.copy(alpha = 0.22f),
                    Color.Transparent
                ),
                center = Offset(width * 0.2f, height * (0.15f + 0.1f * drift)),
                radius = width * 0.6f
            ),
            radius = width * 0.6f,
            center = Offset(width * 0.2f, height * (0.15f + 0.1f * drift))
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Pulse.copy(alpha = 0.12f),
                    Color.Transparent
                ),
                center = Offset(width * 0.85f, height * 0.74f),
                radius = width * 0.5f
            ),
            radius = width * 0.5f,
            center = Offset(width * 0.85f, height * 0.74f)
        )
    }
}

@Composable
private fun rememberLoadedBitmap(
    context: Context,
    imageUri: Uri?
) = produceState<androidx.compose.ui.graphics.ImageBitmap?>(
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

@Preview(showBackground = true)
@Composable
private fun ScannerPreview() {
    TactileLensTheme {
        ScannerScreen(
            imageUri = null,
            hasCameraPermission = true,
            imageCapture = ImageCapture.Builder().build(),
            segmentation = null,
            onCapture = {},
            onTapCapture = { _, _ -> },
            onClearImage = {},
        )
    }
}
