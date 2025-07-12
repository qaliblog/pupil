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
            .setMinFaceDetectionConfidence(0.5f)  // Lower this if needed
            .setMinFacePresenceConfidence(0.5f)   // Lower this if needed
            .setMinTrackingConfidence(0.5f)       // Lower this if needed
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

    fun detectLiveStream(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean
    ) {
        cameraExecutor.execute {
            // Update FPS calculation
            val currentTimestamp = SystemClock.elapsedRealtime()
            frameProcessedInOneSecond++
            if (currentTimestamp - lastFpsTimestamp >= 1000) {
                framesPerSecond = frameProcessedInOneSecond
                frameProcessedInOneSecond = 0
                lastFpsTimestamp = currentTimestamp
            }

            val frameTime = SystemClock.uptimeMillis()

            // More efficient bitmap creation
            val bitmap = imageProxy.toBitmap()
            
            if (bitmap == null) {
                imageProxy.close()
                return@execute
            }

            // Reuse matrix object
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f, bitmap.width.toFloat(), bitmap.height.toFloat())
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                false  // Changed to false for better performance
            )

            val mpImage = BitmapImageBuilder(rotatedBitmap).build()

            // Process frame
            detectAsync(mpImage, frameTime, imageProxy)
            
            // Clean up bitmaps
            if (bitmap != rotatedBitmap) {
                bitmap.recycle()
            }
            rotatedBitmap.recycle()
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