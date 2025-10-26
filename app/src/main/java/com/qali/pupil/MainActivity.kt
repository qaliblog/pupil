package com.qali.pupil

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.util.Size
import kotlin.math.abs
import android.view.View
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import org.json.JSONObject
import java.io.File
import java.io.FileWriter


class MainActivity : AppCompatActivity(), FaceLandmarkerHelper.LandmarkerListener {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceLandmarkerHelper: FaceLandmarkerHelper
    private lateinit var overlayView: OverlayView
    private lateinit var viewFinder: PreviewView
    private lateinit var calibrationButton: Button
    private lateinit var resetButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var formulaButton: Button
    private lateinit var calibrationStatus: TextView
    
    // AI Settings
    private var geminiApiKey = ""
    private var geminiModelName = "gemini-1.5-flash"
    private var aiOptimizationEnabled = false
    private var debugInfoEnabled = false
    private var errorThreshold = 20f
    private var minCalibrationPoints = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayView = findViewById(R.id.overlay_view)
        viewFinder = findViewById(R.id.viewFinder)
        calibrationButton = findViewById(R.id.calibrationButton)
        resetButton = findViewById(R.id.resetButton)
        settingsButton = findViewById(R.id.settingsButton)
        formulaButton = findViewById(R.id.formulaButton)
        calibrationStatus = findViewById(R.id.calibrationStatus)
        
        // Setup calibration button
        calibrationButton.setOnClickListener {
            if (overlayView.isCalibrationActive()) {
                overlayView.stopCalibration()
                calibrationButton.text = "Start Calibration"
            } else {
                overlayView.startCalibration()
                calibrationButton.text = "Stop Calibration"
            }
        }
        
        // Setup reset button
        resetButton.setOnClickListener {
            overlayView.resetCalibration()
            calibrationButton.text = "Start Calibration"
        }
        
        // Setup settings button
        settingsButton.setOnClickListener {
            showSettingsDialog()
        }
        
        // Setup formula button
        formulaButton.setOnClickListener {
            // Update system with latest formula before showing dialog
            overlayView.updateSystemFromJsonFormula()
            showFormulaDialog()
        }
        
        // Setup calibration status updates
        updateCalibrationStatus()


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

        // Optimize camera resolution for maximum FPS
        val targetResolution = Size(480, 360)  // Even lower resolution for maximum performance

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
        
        // Update FPS display
        faceLandmarkerHelper.getCurrentFPS()?.let { fps ->
            overlayView.updateFPS(fps)
        }
        
        // Update calibration status
        updateCalibrationStatus()
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
    private fun updateCalibrationStatus() {
        calibrationStatus.text = overlayView.getCalibrationStatus()
    }
    
    // Handle touch events for calibration
    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean {
        if (overlayView.isCalibrationActive() && event?.action == android.view.MotionEvent.ACTION_DOWN) {
            overlayView.handleCalibrationTap(event.x, event.y)
            return true
        }
        return super.onTouchEvent(event)
    }
    
    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        
        val apiKeyInput = dialogView.findViewById<EditText>(R.id.apiKeyInput)
        val modelNameInput = dialogView.findViewById<EditText>(R.id.modelNameInput)
        val aiOptimizationToggle = dialogView.findViewById<Switch>(R.id.aiOptimizationToggle)
        val debugInfoToggle = dialogView.findViewById<Switch>(R.id.debugInfoToggle)
        val errorThresholdSeekBar = dialogView.findViewById<SeekBar>(R.id.errorThresholdSeekBar)
        val errorThresholdText = dialogView.findViewById<TextView>(R.id.errorThresholdText)
        val minPointsSeekBar = dialogView.findViewById<SeekBar>(R.id.minPointsSeekBar)
        val minPointsText = dialogView.findViewById<TextView>(R.id.minPointsText)
        val saveButton = dialogView.findViewById<Button>(R.id.saveSettingsButton)
        val testButton = dialogView.findViewById<Button>(R.id.testConnectionButton)
        
        // Set current values
        apiKeyInput.setText(geminiApiKey)
        modelNameInput.setText(geminiModelName)
        aiOptimizationToggle.isChecked = aiOptimizationEnabled
        debugInfoToggle.isChecked = debugInfoEnabled
        errorThresholdSeekBar.progress = errorThreshold.toInt()
        minPointsSeekBar.progress = minCalibrationPoints
        
        // Update text displays
        errorThresholdText.text = "Threshold: ${errorThreshold.toInt()}px"
        minPointsText.text = "Minimum Points: $minCalibrationPoints"
        
        // SeekBar listeners
        errorThresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                errorThresholdText.text = "Threshold: ${progress}px"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        minPointsSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                minPointsText.text = "Minimum Points: $progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()
        
        saveButton.setOnClickListener {
            geminiApiKey = apiKeyInput.text.toString()
            geminiModelName = modelNameInput.text.toString()
            aiOptimizationEnabled = aiOptimizationToggle.isChecked
            debugInfoEnabled = debugInfoToggle.isChecked
            errorThreshold = errorThresholdSeekBar.progress.toFloat()
            minCalibrationPoints = minPointsSeekBar.progress
            
            overlayView.setDebugInfoEnabled(debugInfoEnabled)
            overlayView.setAiOptimizationEnabled(aiOptimizationEnabled, geminiApiKey, geminiModelName)
            
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }
        
        testButton.setOnClickListener {
            testGeminiConnection()
        }
        
        dialog.show()
    }
    
    private fun showFormulaDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_formula, null)
        
        val baseParametersText = dialogView.findViewById<TextView>(R.id.baseParametersText)
        val errorCorrectionsText = dialogView.findViewById<TextView>(R.id.errorCorrectionsText)
        val aiFormulaText = dialogView.findViewById<TextView>(R.id.aiFormulaText)
        val calibrationDataText = dialogView.findViewById<TextView>(R.id.calibrationDataText)
        val copyButton = dialogView.findViewById<Button>(R.id.copyFormulaButton)
        val exportButton = dialogView.findViewById<Button>(R.id.exportFormulaButton)
        val aiOptimizeButton = dialogView.findViewById<Button>(R.id.aiOptimizeButton)
        
        // Get formula data from overlay view
        val formulaData = overlayView.getFormulaData()
        
        // Show JSON format in the main text area
        baseParametersText.text = "JSON Formula:\n${formulaData.jsonFormula}"
        errorCorrectionsText.text = "Text Format:\n${formulaData.baseParameters}"
        aiFormulaText.text = formulaData.aiFormula
        calibrationDataText.text = formulaData.calibrationData
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("Formula Configuration")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()
        
        copyButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Formula JSON", formulaData.jsonFormula)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "JSON Formula copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        
        exportButton.setOnClickListener {
            exportFormulaToFile(formulaData.fullFormula)
        }
        
        aiOptimizeButton?.setOnClickListener {
            overlayView.triggerAiOptimization()
            Toast.makeText(this, "AI optimization triggered", Toast.LENGTH_SHORT).show()
        }
        
        dialog.show()
    }
    
    private fun testGeminiConnection() {
        if (geminiApiKey.isEmpty()) {
            Toast.makeText(this, "Please enter API key first", Toast.LENGTH_SHORT).show()
            return
        }
        
        Toast.makeText(this, "Testing connection...", Toast.LENGTH_SHORT).show()
        
        // Test connection in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val success = testGeminiApi(geminiApiKey, geminiModelName)
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(this@MainActivity, "Connection successful!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Connection failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private suspend fun testGeminiApi(apiKey: String, modelName: String): Boolean {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val jsonInput = JSONObject().apply {
                put("contents", JSONObject().apply {
                    put("parts", JSONObject().apply {
                        put("text", "Hello, this is a test message.")
                    })
                })
            }
            
            val outputStream = connection.outputStream
            val writer = OutputStreamWriter(outputStream)
            writer.write(jsonInput.toString())
            writer.flush()
            writer.close()
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            responseCode == 200
        } catch (e: Exception) {
            false
        }
    }
    
    private fun exportFormulaToFile(formula: String) {
        try {
            val file = File(getExternalFilesDir(null), "pupil_formula_${System.currentTimeMillis()}.txt")
            val writer = FileWriter(file)
            writer.write(formula)
            writer.close()
            Toast.makeText(this, "Formula exported to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}