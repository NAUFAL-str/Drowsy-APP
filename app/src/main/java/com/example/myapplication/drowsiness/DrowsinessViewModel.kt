// drowsiness/DrowsinessViewModel.kt - MediaPipe FaceLandmarker Implementation with Immediate Predictions
package com.example.myapplication.drowsiness

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.sqrt

class DrowsinessViewModel : ViewModel() {

    private val _drowsinessState = MutableStateFlow(DrowsinessState())
    val drowsinessState: StateFlow<DrowsinessState> = _drowsinessState.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    // Core components
    private var tfliteInterpreter: Interpreter? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var faceLandmarker: FaceLandmarker? = null

    // MediaPipe landmark indices
    companion object {
        private const val TAG = "DrowsinessViewModel" // Consistent TAG

        // Left eye landmarks (6 points for EAR calculation)
        private val LEFT_EYE_IDXS = intArrayOf(33, 160, 158, 133, 153, 144)

        // Right eye landmarks (6 points for EAR calculation)
        private val RIGHT_EYE_IDXS = intArrayOf(362, 385, 387, 263, 373, 380)

        // Mouth landmarks for MAR calculation
        private const val TOP_LIP_IDX = 13
        private const val BOTTOM_LIP_IDX = 14
        private const val LEFT_MOUTH_IDX = 78
        private const val RIGHT_MOUTH_IDX = 308
    }

    // Feature sequence buffer - now with immediate prediction capability
    private val featureSequence = ArrayDeque<FloatArray>()
    private val maxSeqLen = 24  // Maximum context frames
    private val minSeqLen = 24   // Minimum frames before starting predictions

    // Statistics for logging
    private var frameCount = 0L // Use Long for potentially large counts
    private var facesDetectedCount = 0L
    private var predictionCount = 0L

    fun initializeDetection(context: Context) {
        Log.i(TAG, "✨ Initializing Drowsiness Detection...")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _drowsinessState.value = _drowsinessState.value.copy(status = "Initializing...")
                Log.d(TAG, "🚀 Starting MediaPipe FaceLandmarker initialization...")
                initializeMediaPipeFaceLandmarker(context)

                try {
                    Log.d(TAG, "🤖 Attempting to load TensorFlow Lite model...")
                    initializeTensorFlowLite(context)
                    _drowsinessState.value = _drowsinessState.value.copy(
                        status = "Ready - Model Loaded"
                    )
                    Log.i(TAG, "✅ TensorFlow Lite model loaded successfully!")
                } catch (e: Exception) {
                    _drowsinessState.value = _drowsinessState.value.copy(
                        status = "Ready - MediaPipe Only (Model Load Failed)"
                    )
                    Log.w(TAG, "⚠️ TensorFlow model not found or failed to load: ${e.message}", e)
                    Log.w(TAG, "👉 Expected TFLite model location: app/src/main/assets/models/drowsiness_sequence_gru.tflite")
                }

                Log.i(TAG, "✅ Drowsiness detection initialization complete. Current Status: ${_drowsinessState.value.status}")

            } catch (e: Exception) {
                Log.e(TAG, "💥 CRITICAL: Failed to initialize drowsiness detection", e)
                _drowsinessState.value = _drowsinessState.value.copy(
                    status = "Error: Initialization Failed - ${e.localizedMessage}"
                )
            }
        }
    }

    private fun initializeMediaPipeFaceLandmarker(context: Context) {
        Log.d(TAG, "🎯 Initializing MediaPipe FaceLandmarker (model: models/face_landmarker.task)")
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("models/face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE) // Using IMAGE mode for frame-by-frame analysis
                .setNumFaces(1) // Optimize for detecting a single face
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f) // Relevant for VIDEO or LIVE_STREAM modes
                .setOutputFaceBlendshapes(false) // Disabled as not used
                .setOutputFacialTransformationMatrixes(false) // Disabled as not used
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.i(TAG, "✅ MediaPipe FaceLandmarker initialized successfully!")

        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to initialize MediaPipe FaceLandmarker", e)
            throw IllegalStateException("MediaPipe FaceLandmarker initialization failed", e) // Re-throw to be caught by caller
        }
    }

    private fun initializeTensorFlowLite(context: Context) {
        val modelPath = "models/drowsiness_sequence_gru.tflite"
        Log.d(TAG, "📂 Loading TensorFlow Lite model from: $modelPath")

        val modelBuffer = loadModelFile(context, modelPath)
        tfliteInterpreter = Interpreter(modelBuffer)

        Log.i(TAG, "🧠 TensorFlow Lite model details:")
        Log.i(TAG, "   Input Tensor Shape: ${tfliteInterpreter?.getInputTensor(0)?.shape()?.contentToString()}")
        Log.i(TAG, "   Output Tensor Shape: ${tfliteInterpreter?.getOutputTensor(0)?.shape()?.contentToString()}")
    }

    private fun loadModelFile(context: Context, modelPath: String): ByteBuffer {
        Log.d(TAG, "📖 Reading model file asset: $modelPath")
        return try {
            context.assets.openFd(modelPath).use { fileDescriptor ->
                FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                    val fileChannel = inputStream.channel
                    val startOffset = fileDescriptor.startOffset
                    val declaredLength = fileDescriptor.declaredLength
                    Log.d(TAG, "📊 Model file size: $declaredLength bytes (Offset: $startOffset)")
                    fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error loading TFLite model file '$modelPath'", e)
            throw e // Re-throw to be caught by initializeDetection
        }
    }

    fun setupCamera(previewView: PreviewView) {
        Log.i(TAG, "📸 Setting up camera...")
        val context = previewView.context
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                Log.d(TAG, "CameraProvider obtained.")

                val targetResolution = Size(360, 640) // Define once
                Log.d(TAG, "Target resolution for Preview and Analysis: $targetResolution")

                val preview = Preview.Builder()
                    .setTargetResolution(targetResolution)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                Log.d(TAG, "Preview Usecase configured.")

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetResolution(targetResolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888) // Explicitly set for yuvToRgbBitmap
                    .build()
                    .also { analyzer ->
                        analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                            // Log frame arrival with rotation info
                            Log.v(TAG, "🖼️ New frame received. Rotation: ${imageProxy.imageInfo.rotationDegrees}°")
                            processImageWithMediaPipe(imageProxy)
                        }
                    }
                Log.d(TAG, "ImageAnalysis Usecase configured.")

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                Log.d(TAG, "CameraSelector set to DEFAULT_FRONT_CAMERA.")

                cameraProvider.unbindAll()
                Log.d(TAG, "Unbound all previous camera use cases.")

                cameraProvider.bindToLifecycle(
                    context as LifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
                Log.i(TAG, "✅ Camera Usecases bound to lifecycle successfully.")
            } catch (exc: Exception) {
                Log.e(TAG, "💥 Camera setup failed catastrophically.", exc)
                _drowsinessState.value = _drowsinessState.value.copy(status = "Error: Camera Setup Failed")
            }

        }, ContextCompat.getMainExecutor(context))
    }

    private fun processImageWithMediaPipe(imageProxy: ImageProxy) {
        frameCount++
        Log.v(TAG, "Processing frame #$frameCount")

        if (_isProcessing.value) {
            Log.w(TAG, "🐢 Processor busy, skipping frame #$frameCount.")
            imageProxy.close() // Ensure imageProxy is always closed
            return
        }

        _isProcessing.value = true

        try {
            Log.v(TAG, "Converting ImageProxy to Bitmap for frame #$frameCount...")
            val originalBitmap = imageProxyToBitmap(imageProxy) // This now handles rotation and potential device-specific flips
            Log.v(TAG, "Bitmap conversion successful. Dimensions: ${originalBitmap.width}x${originalBitmap.height}")

            val mpImage = BitmapImageBuilder(originalBitmap).build()
            Log.v(TAG, "MPImage created. Detecting face landmarks...")

            val startTime = System.currentTimeMillis()
            faceLandmarker?.detect(mpImage)?.let { result ->
                val detectionTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "⏱️ FaceLandmarker detection took ${detectionTime}ms for frame #$frameCount.")
                handleFaceLandmarkerResult(result, originalBitmap.width, originalBitmap.height)
            } ?: Log.w(TAG, "⚠️ FaceLandmarker.detect returned null for frame #$frameCount.")

        } catch (e: Exception) {
            Log.e(TAG, "💥 Error processing image with MediaPipe for frame #$frameCount", e)
        } finally {
            _isProcessing.value = false
            imageProxy.close() // Crucial to close the ImageProxy
            Log.v(TAG, "Finished processing frame #$frameCount.")
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val bitmap = when (imageProxy.format) {
            android.graphics.ImageFormat.YUV_420_888 -> {
                Log.v(TAG, "Image format: YUV_420_888. Converting to RGB Bitmap.")
                yuvToRgbBitmap(imageProxy)
            }
            android.graphics.ImageFormat.JPEG -> {
                Log.v(TAG, "Image format: JPEG. Decoding.")
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw RuntimeException("Failed to decode JPEG bitmap")
            }
            else -> {
                Log.e(TAG, "Unsupported image format: ${imageProxy.format}")
                throw RuntimeException("Unsupported image format: ${imageProxy.format}")
            }
        }
        Log.v(TAG, "Raw bitmap created. Applying transformations. Initial rotation: ${imageProxy.imageInfo.rotationDegrees}°")

        // --- Camera Flip Fix ---
        // Correct pivot point for scaling/flipping to be the center of the bitmap.
        val centerX = bitmap.width / 2f
        val centerY = bitmap.height / 2f

        val matrix = Matrix().apply {
            // Apply rotation provided by CameraX
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat(), centerX, centerY)
            Log.v(TAG, "Applied rotation: ${imageProxy.imageInfo.rotationDegrees}°")

            // Specific fix for Pixel 2 on Android 8.x (Oreo)
            // Android Oreo is API 26 (8.0) and 27 (8.1)
            val isPixel2 = Build.MODEL.contains("Pixel 2", ignoreCase = true)
            val isOreo = Build.VERSION.SDK_INT == Build.VERSION_CODES.O || Build.VERSION.SDK_INT == Build.VERSION_CODES.O_MR1

            if (isPixel2 && isOreo) {
                Log.w(TAG, "📸 Applying Pixel 2 Oreo specific fix: Horizontal and Vertical flip (postScale(-1f, -1f))")
                // If it's "upside down" after normal rotation and standard horizontal flip,
                // it means it needs a 180-degree effective rotation *on top* of the standard mirroring.
                // Standard front cam: postScale(-1f, 1f) for mirroring.
                // If it's upside down, then also flip vertically: postScale(-1f, -1f).
                postScale(-1f, -1f, centerX, centerY)
            } else {
                // Standard front camera horizontal flip (mirroring)
                Log.v(TAG, "Applying standard front camera horizontal flip (postScale(-1f, 1f))")
                postScale(-1f, 1f, centerX, centerY)
            }
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (bitmap != it) bitmap.recycle() } // Recycle original if a new one is created
    }


    private fun yuvToRgbBitmap(imageProxy: ImageProxy): Bitmap {
        Log.v(TAG, "Converting YUV_420_888 to RGB Bitmap. Width: ${imageProxy.width}, Height: ${imageProxy.height}")
        val yBuffer = imageProxy.planes[0].buffer // Y
        val uBuffer = imageProxy.planes[1].buffer // U
        val vBuffer = imageProxy.planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // NV21 format requires YYYY... UVUV...
        // ImageProxy planes are Y, U, V. For NV21, V plane comes before U plane.
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize) // V plane (comes after Y)
        uBuffer.get(nv21, ySize + vSize, uSize) // U plane (comes after V)


        val yuvImage = android.graphics.YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null // strides are null for NV21
        )

        val outputStream = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height),
            100, // Quality
            outputStream
        )
        val imageBytes = outputStream.toByteArray()
        Log.v(TAG, "YUV to JPEG conversion complete. JPEG size: ${imageBytes.size} bytes.")

        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw RuntimeException("Failed to convert YUV to RGB bitmap (decoded from JPEG)")
    }

    private fun handleFaceLandmarkerResult(
        result: FaceLandmarkerResult,
        imageWidth: Int,
        imageHeight: Int
    ) {
        Log.v(TAG, "Handling FaceLandmarker result...")
        viewModelScope.launch(Dispatchers.IO) { // Already on IO, but good for clarity
            try {
                if (result.faceLandmarks().isNotEmpty()) {
                    facesDetectedCount++
                    val landmarks = result.faceLandmarks()[0] // Assuming single face detection

                    Log.d(TAG, "👤 Face detected with ${landmarks.size} landmarks. Total faces detected: $facesDetectedCount")

                    val ear = computeEAR(landmarks, imageWidth, imageHeight)
                    val mar = computeMAR(landmarks, imageWidth, imageHeight)
                    Log.d(TAG, String.format(Locale.US,"🔢 Calculated Features - EAR: %.3f, MAR: %.3f", ear, mar))

                    // Add new features to sequence
                    featureSequence.offer(floatArrayOf(ear, mar))
                    if (featureSequence.size > maxSeqLen) {
                        featureSequence.poll() // Remove oldest if buffer exceeds maximum
                    }
                    Log.v(TAG, "Feature sequence size: ${featureSequence.size}/$maxSeqLen")

                    _drowsinessState.value = _drowsinessState.value.copy(
                        featureCount = featureSequence.size,
                        // Potentially update EAR/MAR for direct display if needed
                        // ear = ear,
                        // mar = mar
                    )

                    // Make prediction if we have minimum required frames
                    if (featureSequence.size >= minSeqLen) {
                        predictionCount++
                        Log.d(TAG, "🚀 Making prediction #$predictionCount with ${featureSequence.size} frames")
                        if (tfliteInterpreter != null) {
                            makeTensorFlowPrediction()
                        } else {
                            Log.d(TAG, "🧠 No TFLite model. Using MediaPipe rule-based prediction.")
                            makeMediaPipePrediction(ear)
                        }
                    } else {
                        Log.d(TAG, "⏳ Need ${minSeqLen - featureSequence.size} more frames before prediction (current: ${featureSequence.size})")
                    }

                    // Periodic detailed statistics
                    if (frameCount % 100 == 0L) { // Log every 100 frames
                        Log.i(TAG, "📊 Stats Update - Frames Processed: $frameCount, Faces Detected: $facesDetectedCount, Predictions Made: $predictionCount, Feature Seq: ${featureSequence.size}/$maxSeqLen")
                    }
                } else {
                    // Log less frequently if no face is detected to avoid spamming logs
                    if (frameCount % 300 == 0L) { // Log every 300 frames if no face
                        Log.d(TAG, "😶 No face detected in frame #$frameCount. Current feature count: ${featureSequence.size}")
                    }
                    // Consider clearing feature sequence or handling no-face scenario
                    // featureSequence.clear() // Optional: reset if no face for a while
                }
            } catch (e: Exception) {
                Log.e(TAG, "💥 Error handling face landmarks result", e)
            }
        }
    }

    private fun computeEAR(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): Float {
        // Inner function for DRY principle
        fun calculateEarForEye(eyeIndices: IntArray): Float {
            val points = eyeIndices.map { idx ->
                if (idx >= landmarks.size) {
                    Log.w(TAG, "⚠️ Landmark index $idx out of bounds (size: ${landmarks.size}). Using (0,0).")
                    return 0.0f // Or handle error more gracefully
                }
                val landmark = landmarks[idx]
                // Denormalize coordinates
                Pair(landmark.x() * imageWidth, landmark.y() * imageHeight)
            }

            // P1, P2, P3, P4, P5, P6
            // Vertical distances: |P2-P6|, |P3-P5|
            // Horizontal distance: |P1-P4|
            val p1 = points[0]; val p2 = points[1]; val p3 = points[2]
            val p4 = points[3]; val p5 = points[4]; val p6 = points[5]

            val verticalDist1 = distance(p2, p6)
            val verticalDist2 = distance(p3, p5)
            val horizontalDist = distance(p1, p4)

            // Add epsilon to prevent division by zero
            return (verticalDist1 + verticalDist2) / (2.0f * horizontalDist + 1e-8f)
        }

        val leftEAR = calculateEarForEye(LEFT_EYE_IDXS)
        val rightEAR = calculateEarForEye(RIGHT_EYE_IDXS)
        Log.v(TAG, String.format(Locale.US, "👂 Individual EARs - Left: %.3f, Right: %.3f", leftEAR, rightEAR))

        return (leftEAR + rightEAR) / 2.0f
    }

    private fun computeMAR(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): Float {
        // Ensure landmark indices are valid
        val indices = listOf(TOP_LIP_IDX, BOTTOM_LIP_IDX, LEFT_MOUTH_IDX, RIGHT_MOUTH_IDX)
        if (indices.any { it >= landmarks.size }) {
            Log.w(TAG, "⚠️ Mouth landmark index out of bounds (size: ${landmarks.size}). MAR calculation skipped.")
            return 0.0f
        }

        val topLip = landmarks[TOP_LIP_IDX]
        val bottomLip = landmarks[BOTTOM_LIP_IDX]
        val leftMouth = landmarks[LEFT_MOUTH_IDX]
        val rightMouth = landmarks[RIGHT_MOUTH_IDX]

        // Denormalize coordinates
        val topPoint = Pair(topLip.x() * imageWidth, topLip.y() * imageHeight)
        val bottomPoint = Pair(bottomLip.x() * imageWidth, bottomLip.y() * imageHeight)
        val leftPoint = Pair(leftMouth.x() * imageWidth, leftMouth.y() * imageHeight)
        val rightPoint = Pair(rightMouth.x() * imageWidth, rightMouth.y() * imageHeight)

        val verticalDistance = distance(topPoint, bottomPoint)
        val horizontalDistance = distance(leftPoint, rightPoint)
        Log.v(TAG, String.format(Locale.US,"👄 Mouth distances - Vertical: %.2f, Horizontal: %.2f", verticalDistance, horizontalDistance))

        // Add epsilon to prevent division by zero
        return verticalDistance / (horizontalDistance + 1e-8f)
    }

    private fun distance(point1: Pair<Float, Float>, point2: Pair<Float, Float>): Float {
        return sqrt((point1.first - point2.first).pow(2) + (point1.second - point2.second).pow(2))
    }

    private fun makeTensorFlowPrediction() {
        val currentSeqSize = featureSequence.size
        Log.d(TAG, "🤖 Making TensorFlow prediction with sequence of $currentSeqSize features (max: $maxSeqLen).")
        val interpreter: Interpreter = tfliteInterpreter ?: run {
            Log.e(TAG, "💥 TFLite Interpreter is null. Cannot make prediction.")
            return
        }

        try {
            // Prepare input: (1, maxSeqLen, num_features) which is (1, 60, 2)
            // Pad with zeros if we have fewer than maxSeqLen frames
            val inputArray = Array(1) { Array(maxSeqLen) { FloatArray(2) } }

            // Calculate padding needed
            val paddingNeeded = maxSeqLen - currentSeqSize

            // Fill with zeros first (padding at the beginning)
            for (i in 0 until paddingNeeded) {
                inputArray[0][i][0] = 0f // EAR
                inputArray[0][i][1] = 0f // MAR
            }

            // Fill with actual feature data
            featureSequence.forEachIndexed { index, features ->
                val targetIndex = paddingNeeded + index
                if (features.size == 2 && targetIndex < maxSeqLen) {
                    inputArray[0][targetIndex][0] = features[0] // EAR
                    inputArray[0][targetIndex][1] = features[1] // MAR
                } else {
                    Log.w(TAG, "⚠️ Feature at index $index has unexpected size: ${features.size} or target index out of bounds: $targetIndex")
                    // Handle error or fill with default, e.g., 0s
                    if (targetIndex < maxSeqLen) {
                        inputArray[0][targetIndex][0] = 0f
                        inputArray[0][targetIndex][1] = 0f
                    }
                }
            }

            Log.d(TAG, "📝 Input prepared: ${currentSeqSize} real frames + ${paddingNeeded} padded frames = ${maxSeqLen} total")

            // Prepare output: (1, 1) for drowsiness probability
            val outputArray = Array(1) { FloatArray(1) }

            val startTime = System.currentTimeMillis()
            interpreter.run(inputArray, outputArray)
            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "⏱️ TensorFlow inference took ${inferenceTime}ms.")

            val drowsinessProb: Float = outputArray[0][0]
            val isDrowsy = drowsinessProb > 0.7f // Threshold for drowsiness
            val status: String = if (isDrowsy) "Drowsy" else "Alert"

            Log.i(TAG, "🎯 TensorFlow Prediction: $status (Raw Probability: ${String.format(Locale.US, "%.4f", drowsinessProb)}, Frames: $currentSeqSize/$maxSeqLen)")

            viewModelScope.launch(Dispatchers.Main) { // Update UI on Main thread
                _drowsinessState.value = _drowsinessState.value.copy(
                    status = status,
                    probability = drowsinessProb,
//                    isDrowsy = isDrowsy
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "💥 TensorFlow prediction error", e)
            // Update UI with error state if necessary
            viewModelScope.launch(Dispatchers.Main) {
                _drowsinessState.value = _drowsinessState.value.copy(
                    status = "Error: TF Prediction Failed"
                )
            }
        }
    }

    private fun makeMediaPipePrediction(ear: Float) {
        Log.d(TAG, "🧠 Making MediaPipe rule-based prediction based on EAR: ${String.format(Locale.US, "%.3f", ear)}")
        // Simple rule-based prediction based on EAR
        val drowsinessProb = when {
            ear < 0.20f -> 0.9f // Very likely drowsy
            ear < 0.25f -> 0.7f // Likely drowsy
            ear < 0.30f -> 0.3f // Possibly drowsy (or eyes naturally narrower)
            else -> 0.1f      // Alert
        }
        val isDrowsy = drowsinessProb >= 0.7f // Using >= for consistency
        val status = if (isDrowsy) "Drowsy (Rule)" else "Alert (Rule)"

        Log.i(TAG, "🧠 MediaPipe Rule Prediction: $status (Estimated Probability: ${String.format(Locale.US, "%.2f", drowsinessProb)}, EAR: ${String.format(Locale.US, "%.3f", ear)})")

        viewModelScope.launch(Dispatchers.Main) { // Update UI on Main thread
            _drowsinessState.value = _drowsinessState.value.copy(
                status = status,
                probability = drowsinessProb,
//                isDrowsy = isDrowsy
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "🧹 ViewModel onCleared called. Shutting down resources.")
        Log.i(TAG, "🏁 Final Stats - Frames Processed: $frameCount, Faces Detected: $facesDetectedCount, Predictions Made: $predictionCount")

        cameraExecutor.shutdown()
        Log.d(TAG, "CameraExecutor shutdown initiated.")
        try {
            if (!cameraExecutor.isTerminated) {
                Log.d(TAG, "Waiting for CameraExecutor termination...")
                // cameraExecutor.awaitTermination(500, TimeUnit.MILLISECONDS) // Optional: wait for tasks to finish
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for CameraExecutor termination", e)
            Thread.currentThread().interrupt()
        }


        tfliteInterpreter?.close()
        tfliteInterpreter = null
        Log.d(TAG, "TensorFlow Lite interpreter closed.")

        faceLandmarker?.close()
        faceLandmarker = null
        Log.d(TAG, "MediaPipe FaceLandmarker closed.")

        Log.i(TAG, "✅ ViewModel cleared and resources released.")
    }
}