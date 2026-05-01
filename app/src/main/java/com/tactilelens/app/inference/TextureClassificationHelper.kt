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
 * Handles the loading and execution of the LiteRT MobileNet model for texture classification.
 * Optimized for Snapdragon Hexagon NPU using NNAPI.
 */
class TextureClassificationHelper(private val context: Context) {

    private var interpreter: Interpreter? = null
    
    // The MobileNet output is an array of 8 weights corresponding to the haptic primitives.
    private val outputArray = Array(1) { FloatArray(8) }

    // Required ImageProcessor to format Android Bitmaps into MobileNet tensors
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR)) // MobileNet expects 224x224
        .add(NormalizeOp(127.5f, 127.5f)) // Normalizes [0, 255] pixels to [-1.0, 1.0] float range
        .build()

    init {
        setupLiteRT()
    }

    private fun setupLiteRT() {
        try {
            // 1. Load the mapped model from the assets directory.
            val modelBuffer = FileUtil.loadMappedFile(context, "texture_model.tflite")

            // 2. Configure the NPU Execution & Fallback strategy.
            val options = Interpreter.Options().apply {
                useNNAPI = true // Route execution to Hexagon NPU with fallback protection
                numThreads = 4 
            }

            // 3. Initialize the LiteRT Interpreter
            interpreter = Interpreter(modelBuffer, options)
            Log.i("TextureHelper", "LiteRT Interpreter successfully initialized with NNAPI (NPU).")

        } catch (e: Exception) {
            Log.e("TextureHelper", "Failed to initialize LiteRT Interpreter: ${e.message}")
        }
    }

    /**
     * Runs inference on the segmented camera frame.
     * @param bitmap A cropped foreground bitmap from SegmentationHelper.
     * @return FloatArray containing the 8 haptic primitive weights.
     */
    fun classifyTexture(bitmap: Bitmap): FloatArray? {
        if (interpreter == null) {
            Log.e("TextureHelper", "Interpreter is not initialized.")
            return null
        }

        try {
            // Process the Bitmap into a TensorImage normalized for the CNN
            val tensorImage = imageProcessor.process(TensorImage.fromBitmap(bitmap))

            // Run the model
            interpreter?.run(tensorImage.tensorBuffer.buffer, outputArray)

            // Return the array of 8 primitive weights
            return outputArray[0]
            
        } catch (e: Exception) {
            Log.e("TextureHelper", "Error running inference: ${e.message}")
            return null
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
