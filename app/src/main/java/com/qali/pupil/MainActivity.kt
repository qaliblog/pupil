package com.qali.pupil

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.util.Size
import kotlin.math.abs


class MainActivity : AppCompatActivity(), FaceLandmarkerHelper.LandmarkerListener {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private lateinit var overlayView: OverlayView
    private lateinit var viewFinder: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayView = findViewById(R.id.overlay_view)
        viewFinder = findViewById(R.id.viewFinder)


        cameraExecutor = Executors.newSingleThreadExecutor()
        // Initialize Face Landmarker
        faceLandmarkerHelper = FaceLandmarkerHelper(
            context = this,
            faceLandmarkerHelperListener = this,
            cameraExecutor = cameraExecutor
        )

        // Check for camera permission
        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    startCamera()
                } else {
                    Log.e(TAG, "Camera permission denied")
                }
            }
        requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    private fun startCamera() {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

    cameraProviderFuture.addListener({
        val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        // Optimize camera resolution
        val targetResolution = Size(640, 480)  // Lower resolution for better performance

        val preview = Preview.Builder()
            .setTargetResolution(targetResolution)
            .build()
            .also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

        val imageAnalyzer = ImageAnalysis.Builder()
            .setTargetResolution(targetResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) // Faster conversion to bitmap
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { image ->
                    faceLandmarkerHelper.detectLiveStream(
                        imageProxy = image,
                        isFrontCamera = true
                    )
                }
            }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }, ContextCompat.getMainExecutor(this))
}


override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
    if (resultBundle.result.faceLandmarks().isNotEmpty()) {
        val landmarks = resultBundle.result.faceLandmarks()[0]
        val overlayWidth = overlayView.width.toFloat()
        val overlayHeight = overlayView.height.toFloat()
        val inputImageWidth = resultBundle.inputImageWidth.toFloat()
        val inputImageHeight = resultBundle.inputImageHeight.toFloat()

        // Calculate scale factors with width correction
        val scaleX = overlayWidth / inputImageWidth * 2f
        val scaleY = overlayHeight / inputImageHeight

        // Calculate the center offset (overlay width - scaled input width) / 2
        val scaledWidth = inputImageWidth * scaleX
        val offsetX = (scaledWidth - overlayWidth) / 2f

        // Adjust landmarks
        val adjustedLandmarks = landmarks.map { landmark ->
            val x = (landmark.x() * inputImageWidth * scaleX) - offsetX
            val y = landmark.y() * inputImageHeight * scaleY
            Pair(x, y)
        }

        // Update the overlay view with the adjusted landmarks
        overlayView.setLandmarks(adjustedLandmarks)
    } else {
        overlayView.clear()
    }
}



    override fun onError(error: String) {
        Log.e(TAG, "Face landmarker error: $error")
    }

    override fun onError(error: String, errorCode: Int) {
        Log.e(TAG, "Face landmarker error: $error, error code: $errorCode")
    }

    override fun onEmpty() {
        overlayView.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
    private fun getProperResolution(
        sizes: List<Size>,
    ): Size {
        val targetRatio = 4f/3f
        var width = 0
        var height = 0
        var minDiff = Float.MAX_VALUE
        for (size in sizes){
            val aspectRatio = size.width.toFloat()/ size.height.toFloat()
            val diff = abs(aspectRatio - targetRatio)
            if(diff < minDiff){
                minDiff = diff
                width = size.width
                height = size.height
            }
        }
        if (width > 1280 || height > 1280){
            width = 1280
            height = (1280/targetRatio).toInt()
        }
        return Size(width,height)
    }
    companion object {
        private const val TAG = "MainActivity"
    }
}