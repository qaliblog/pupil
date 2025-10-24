package com.qali.pupil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceLandmarkerHelper(
    val context: Context,
    val faceLandmarkerHelperListener: LandmarkerListener? = null,
    private val cameraExecutor: ExecutorService
) {
    private var faceLandmarker: FaceLandmarker? = null
    private val resultExecutor = Executors.newSingleThreadExecutor()
    
    // Reuse these objects to reduce allocations
    private var lastFpsTimestamp = SystemClock.elapsedRealtime()
    private var frameProcessedInOneSecond = 0
    private var framesPerSecond = 0
    private var lastProcessTime = 0L
    private var targetFrameInterval = 33L // ~30 FPS target (33ms between frames)
    private var adaptiveFrameSkip = 0
    private var frameSkipCounter = 0

    init {
        setupFaceLandmarker()
    }

    private fun setupFaceLandmarker() {
    try {
        val baseOptions = BaseOptions.builder()
            .setDelegate(Delegate.GPU)
            .setModelAssetPath("face_landmarker.task")
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.3f)  // Lower for better detection
            .setMinFacePresenceConfidence(0.3f)   // Lower for better detection
            .setMinTrackingConfidence(0.3f)       // Lower for better tracking
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::returnLivestreamResult)
            .setErrorListener(this::returnLivestreamError)
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
    } catch (e: Exception) {
        faceLandmarkerHelperListener?.onError(
            "Face Landmarker failed to initialize: ${e.message}"
        )
    }
}

    fun getCurrentFPS(): Int? {
        return if (framesPerSecond > 0) framesPerSecond else null
    }
    
    fun detectLiveStream(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean
    ) {
        cameraExecutor.execute {
            val currentTime = SystemClock.uptimeMillis()
            
            // Adaptive frame skipping for better FPS
            frameSkipCounter++
            if (frameSkipCounter <= adaptiveFrameSkip) {
                imageProxy.close()
                return@execute
            }
            frameSkipCounter = 0
            lastProcessTime = currentTime
            
            // Update FPS calculation and adaptive frame skipping
            val currentTimestamp = SystemClock.elapsedRealtime()
            frameProcessedInOneSecond++
            if (currentTimestamp - lastFpsTimestamp >= 1000) {
                framesPerSecond = frameProcessedInOneSecond
                frameProcessedInOneSecond = 0
                lastFpsTimestamp = currentTimestamp
                
                // Adaptive frame skipping based on performance
                when {
                    framesPerSecond < 20 -> adaptiveFrameSkip = 0  // Process all frames
                    framesPerSecond < 30 -> adaptiveFrameSkip = 1  // Skip every other frame
                    else -> adaptiveFrameSkip = 2  // Skip 2 out of 3 frames
                }
            }

            val frameTime = currentTime

            // More efficient bitmap creation
            val bitmap = imageProxy.toBitmap()
            
            if (bitmap == null) {
                imageProxy.close()
                return@execute
            }

            // Skip processing if bitmap is too small or invalid
            if (bitmap.width < 100 || bitmap.height < 100) {
                bitmap.recycle()
                imageProxy.close()
                return@execute
            }

            // Reuse matrix object - only create if needed
            val rotation = imageProxy.imageInfo.rotationDegrees
            val needsRotation = rotation != 0 || isFrontCamera
            
            val processedBitmap = if (needsRotation) {
                val matrix = Matrix().apply {
                    if (rotation != 0) postRotate(rotation.toFloat())
                    if (isFrontCamera) {
                        postScale(-1f, 1f, bitmap.width.toFloat(), bitmap.height.toFloat())
                    }
                }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            } else {
                bitmap
            }

            val mpImage = BitmapImageBuilder(processedBitmap).build()

            // Process frame
            detectAsync(mpImage, frameTime, imageProxy)
            
            // Clean up bitmaps
            if (processedBitmap != bitmap) {
                processedBitmap.recycle()
            }
            bitmap.recycle()
        }
    }

    private fun detectAsync(mpImage: MPImage, frameTime: Long, imageProxy: ImageProxy) {
        try {
            faceLandmarker?.detectAsync(mpImage, frameTime)
        } finally {
            imageProxy.close()
        }
    }

    

    private fun returnLivestreamResult(result: FaceLandmarkerResult, input: MPImage) {
        resultExecutor.execute{
            try {
                val landmarks = result.faceLandmarks()
                if (landmarks.isNullOrEmpty()) {
                    faceLandmarkerHelperListener?.onEmpty()
                    return@execute
                }

                val finishTimeMs = SystemClock.uptimeMillis()
                val inferenceTime = finishTimeMs - result.timestampMs()

                faceLandmarkerHelperListener?.onResults(
                    ResultBundle(result, inferenceTime, input.height, input.width)
                )
            } catch (e: Exception) {
                faceLandmarkerHelperListener?.onError("Error in returnLivestreamResult: ${e.message}")
            }
        }
    }


    private fun returnLivestreamError(error: RuntimeException) {
        resultExecutor.execute {
            faceLandmarkerHelperListener?.onError(
                error.message ?: "An unknown error has occurred"
            )
        }
    }


    data class ResultBundle(
        val result: FaceLandmarkerResult,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
    )

    interface LandmarkerListener {
        fun onError(error: String)
        fun onError(error: String, errorCode: Int)
        fun onResults(resultBundle: ResultBundle)
        fun onEmpty() {}
    }
}