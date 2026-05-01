package com.tactilelens.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp

/**
 * Handles the loading and execution of the LiteRT EfficientNet + Linear Head model.
 * Optimized for Snapdragon Hexagon NPU using NNAPI.
 */
class TextureClassificationHelper(private val context: Context) {

    private var encoderInterpreter: Interpreter? = null
    private var headInterpreter: Interpreter? = null
    
    // Outputs
    private val featuresArray = Array(1) { FloatArray(1280) }
    private val dimsArray = Array(1) { FloatArray(4) }

    // Required ImageProcessor for EfficientNet-Lite0
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
        .add(NormalizeOp(127.5f, 127.5f))
        .build()

    init {
        setupLiteRT()
    }

    private fun setupLiteRT() {
        try {
            val options = Interpreter.Options().apply {
                useNNAPI = true
                numThreads = 4 
            }

            val encBuffer = FileUtil.loadMappedFile(context, "efficientnet_lite0.tflite")
            val headBuffer = FileUtil.loadMappedFile(context, "linear_head.tflite")

            encoderInterpreter = Interpreter(encBuffer, options)
            headInterpreter = Interpreter(headBuffer, options)
            
            Log.i("TextureHelper", "LiteRT Dual-Interpreters successfully initialized with NNAPI (NPU).")
        } catch (e: Exception) {
            Log.e("TextureHelper", "Failed to initialize LiteRT Interpreters: ${e.message}")
        }
    }

    /**
     * @return FloatArray containing the 4 texture axes: [rough, hard, friction, density]
     */
    fun classifyTexture(bitmap: Bitmap): FloatArray? {
        if (encoderInterpreter == null || headInterpreter == null) {
            return null
        }

        try {
            val tensorImage = imageProcessor.process(TensorImage.fromBitmap(bitmap))
            
            // 1. Feature Extraction (EfficientNet-Lite0)
            encoderInterpreter?.run(tensorImage.tensorBuffer.buffer, featuresArray)
            
            // 2. Projection Head (Linear Head)
            headInterpreter?.run(featuresArray, dimsArray)

            return dimsArray[0]
            
        } catch (e: Exception) {
            Log.e("TextureHelper", "Error running inference: ${e.message}")
            return null
        }
    }
    
    fun close() {
        encoderInterpreter?.close()
        headInterpreter?.close()
        encoderInterpreter = null
        headInterpreter = null
    }
}
