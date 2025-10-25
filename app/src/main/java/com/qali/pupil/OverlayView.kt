package com.qali.pupil

import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.util.Log
import android.view.View
import java.util.LinkedList
import java.util.*
import java.io.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import java.io.*
import java.io.Serializable
import org.json.JSONObject
import org.json.JSONArray

class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs), SensorEventListener {

    // --- Control and Tuning Variables ---

    var pointerGazeSensitivityX = 2.5f  // Reduced from 19.031168f
    var pointerGazeSensitivityY = 8.0f  // Increased from 0.011528987f
    var pointerHeadSensitivity = 0.6f
    var headTiltYBaseSensitivity = 2.5f
    var pointerGyroSensitivity = 200.0f
    var pointerDampingFactor = 0.9f

    // Eye Position and Distance Scaling
    var leftEyeX = 0f
    var leftEyeY = -17f
    var leftEyeZ = 0f
    var rightEyeX = 0f
    var rightEyeY = -17f
    var rightEyeZ = 0f
    private var distanceScalingFactor = 0.8f  // Further reduced for better control
    private val neutralFaceDepth = 300.0f
    
    // Enhanced cursor control variables
    private var lastGazeTipX = 0f
    private var lastGazeTipY = 0f
    private var gazeTipVelocity = 0f
    private var gazeTipChangeThreshold = 5f
    private var minSphereSize = 15f  // Adjusted for better range
    private var maxSphereSize = 45f  // Adjusted for better range
    private var adaptiveSensitivity = 1.0f
    private var lastEyeYPosition = 0f
    private var eyeYVelocity = 0f
    
    // Eye position normalization constants
    private var typicalEyeMinYRatio = 0.4f  // Bottom 60% starts here
    private var typicalEyeMaxYRatio = 0.9f  // Leave margin at bottom
    
    // Dynamic Calibration System
    private var isCalibrating = false
    private var calibrationPoints = mutableListOf<CalibrationPoint>()
    private var tapVisualizations = mutableListOf<TapVisualization>()
    private var calibrationData = CalibrationData()
    private var calibrationClickCount = 0
    private val maxCalibrationPoints = 1000 // Increased capacity
    
    // AI Integration
    private var aiOptimizationEnabled = false
    private var geminiApiKey = ""
    private var geminiModelName = "gemini-1.5-flash"
    private var debugInfoEnabled = false
    private var errorThreshold = 20f
    private var minCalibrationPoints = 5
    private var aiOptimizedFormula = ""
    private var lastAiOptimizationTime = 0L
    private var isAiPrompting = false
    private var aiPromptingStartTime = 0L
    
    // Formula History
    private var formulaHistory = mutableListOf<FormulaSnapshot>()
    private var currentFormulaVersion = 0
    private val maxHistorySize = 50
    
    // JSON Formula System
    private var currentFormulaJson: JSONObject? = null
    private var formulaUpdateTime = 0L
    
    // JSON Formula Data Classes
    data class FormulaJson(
        val version: String,
        val timestamp: Long,
        val baseParameters: BaseParametersJson,
        val errorCorrections: ErrorCorrectionsJson,
        val calibrationData: CalibrationDataJson,
        val aiOptimization: AiOptimizationJson,
        val formulaVariations: List<FormulaVariationJson>
    )
    
    data class BaseParametersJson(
        val gazeSensitivityX: Float,
        val gazeSensitivityY: Float,
        val headSensitivity: Float,
        val headTiltYBase: Float,
        val gyroSensitivity: Float,
        val dampingFactor: Float,
        val distanceScaling: Float
    )
    
    data class ErrorCorrectionsJson(
        val xCorrectionFactor: Float,
        val yCorrectionFactor: Float,
        val xOffset: Float,
        val yOffset: Float,
        val averageError: Float,
        val clickCount: Int
    )
    
    data class CalibrationDataJson(
        val pointsCollected: Int,
        val eyeYRange: Pair<Float, Float>,
        val sphereSizeRange: Pair<Float, Float>,
        val yPositionInfluence: Float,
        val distanceRange: Float,
        val gazeSensitivity: Float
    )
    
    data class AiOptimizationJson(
        val completedAt: Long,
        val optimizedPoints: Int,
        val errorReduction: Float,
        val status: String
    )
    
    data class FormulaVariationJson(
        val name: String,
        val description: String,
        val xFormula: String,
        val yFormula: String
    )
    
    // Calibration data classes
    private data class CalibrationPoint(
        val tapX: Float,
        val tapY: Float,
        val cursorX: Float,
        val cursorY: Float,
        val averageEyeY: Float,
        val averageSphereSize: Float,
        val gazeVelocity: Float,
        val yPositionInfluence: Float,
        val distanceRange: Float,
        val adaptiveSensitivity: Float,
        val errorX: Float,
        val errorY: Float,
        val errorMagnitude: Float,
        val sphereSize: Float = 0f,
        val eyeXPosition: Float = 0f,
        val eyeYPosition: Float = 0f,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private data class TapVisualization(
        val x: Float,
        val y: Float,
        val startTime: Long,
        val duration: Long = 1000L // 1 second display
    )
    
    private data class CalibrationData(
        var avgYPositionInfluence: Float = -0.10929684f,
        var avgDistanceRange: Float = 1.8989421f,
        var avgGazeSensitivity: Float = 1.4839412f,
        var avgEyeYMin: Float = 1155.8164f,
        var avgEyeYMax: Float = 1665.7925f,
        var avgSphereSizeMin: Float = 65.951935f,
        var avgSphereSizeMax: Float = 119.09108f,
        var isCalibrated: Boolean = true,
        var clickCount: Int = 420,
        // Error correction factors
        var xErrorCorrection: Float = 1.0662434f,
        var yErrorCorrection: Float = 0.94424844f,
        var xOffsetCorrection: Float = 0f,  // Fixed: was causing cursor jumping
        var yOffsetCorrection: Float = 0f,  // Fixed: was causing cursor to always stay down
        var avgErrorMagnitude: Float = 873.8477f
    ) : Serializable

    // Static offsets for the pointer
    var staticX = 0f
    var staticY = 0f

    // Head Direction
    var averageFaceWeightYOffset = 70f
    
    // Sphere Stretch Adjustment
    var sphereStretchFactor = 0.3f
    var sphereBottomStretchMultiplier = 1.0f
    var sphereOuterStretchMultiplier = 1.0f
    
    
    
    // FPS Display
    var currentFPS = 0
    
    
    // Gaze Line Curvature
    var gazeCurvatureEnabled = true
    var gazeCurvatureDownward = 0.3f    // How much to curve gaze lines downward
    var gazeCurvatureStrength = 0.5f    // Strength of the curvature effect

    // Gyroscope
    private var gyroVelocityX = 0.0f
    private var gyroVelocityY = 0.0f
    private var gyroSensitivity = 0.1f
    private var lastGyroTimestamp: Long = 0

    // --- Blink Detection Variables ---
    private var leftEyeOpenHeight: Float = 0f
    private var rightEyeOpenHeight: Float = 0f
    private val leftEyeHeightHistory = LinkedList<Pair<Long, Float>>()
    private val rightEyeHeightHistory = LinkedList<Pair<Long, Float>>()
    private val heightHistoryDuration = 1000L

    // Click (Half-Blink) Logic
    private val HALF_BLINK_THRESHOLD = 0.6f
    private val FULL_BLINK_THRESHOLD = 0.85f
    private val CLICK_MIN_DURATION_MS = 150L
    private val CLICK_MAX_DURATION_MS = 800L
    private var isClickTriggered = false
    private var halfBlinkStartTime: Long = 0
    private var savedClickPosition: Pair<Float, Float>? = null
    private var showBlueDot: Boolean = false
    private var blueDotStartTime: Long = 0
    private var lastOpenEyeUpdateTime: Long = 0

    // --- Data and Drawing Variables ---
    private var landmarks: List<Pair<Float, Float>> = emptyList()
    private var lastPointerPosition: Pair<Float, Float>? = null

    // Paint objects
    private val whitePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
    private val purplePaint = Paint().apply { color = Color.MAGENTA; style = Paint.Style.FILL; isAntiAlias = true }
    private val bluePaint = Paint().apply { color = Color.BLUE; style = Paint.Style.FILL; isAntiAlias = true }
    private val greenPaint = Paint().apply { color = Color.GREEN; style = Paint.Style.FILL; isAntiAlias = true }
    private val gazePaint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
    private val headVectorPaint = Paint().apply { color = Color.rgb(255, 165, 0); style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
    private val clickDotPaint = Paint().apply { color = Color.BLUE; style = Paint.Style.FILL; isAntiAlias = true }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 30f; style = Paint.Style.FILL; isAntiAlias = true; textAlign = Paint.Align.LEFT }
    private val pointerPaint = Paint().apply { color = Color.argb(200, 255, 165, 0); style = Paint.Style.FILL; isAntiAlias = true }


    // Landmark Indices
    private val sphereRightEyeIndices = listOf(359, 467, 260, 259, 257, 258, 286, 414, 463, 341, 256, 252, 253, 254, 339, 255)
    private val sphereLeftEyeIndices = listOf(130, 247, 30, 29, 27, 28, 56, 190, 243, 112, 26, 22, 23, 24, 110, 25)
    private val rightEyeIndices = listOf(362, 398, 384, 385, 386, 387, 388, 382, 381, 380, 374, 373, 390, 466, 263, 249)
    private val leftEyeIndices = listOf(246, 161, 160, 159, 158, 157, 7, 163, 144, 145, 153, 154, 33, 155, 173, 133)
    private val rightPupilIndex = 473
    private val leftPupilIndex = 468
    private val headDirectionIndices = listOf(127, 162, 21, 54, 103, 67, 109, 10, 338, 297, 332, 284, 251, 389, 356)
    private val headDirectionTargetIndex = 9

    // Data classes
    private data class EyeSphere(val centerX: Float, val centerY: Float, val radius: Float, val scaledRadius: Float = radius, val zScale: Float = 1f)
    private data class GazeLine(val startX: Float, val startY: Float, val endX: Float, val endY: Float)

    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscopeSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    init {
        startGyroscopeListening()
        initializeOptimizedParameters()
    }
    
    // Initialize system with provided optimized parameters
    private fun initializeOptimizedParameters() {
        // Set base parameters with improved values
        pointerGazeSensitivityX = 2.5f  // Reduced for better control
        pointerGazeSensitivityY = 8.0f  // Increased for better vertical movement
        pointerHeadSensitivity = 0.6f
        headTiltYBaseSensitivity = 2.5f
        pointerGyroSensitivity = 200.0f
        pointerDampingFactor = 0.7f  // Reduced for more responsive movement
        distanceScalingFactor = 1.2f  // Much more reasonable scaling
        
        // Initialize calibration data with improved values
        calibrationData = CalibrationData(
            avgYPositionInfluence = -0.04440979f,  // From latest data
            avgDistanceRange = 2.188483f,  // From latest data
            avgGazeSensitivity = 1.3426464f,  // From latest data
            avgEyeYMin = 1347.292f,  // From latest data
            avgEyeYMax = 1627.4675f,  // From latest data
            avgSphereSizeMin = 67.13453f,  // From latest data
            avgSphereSizeMax = 95.32056f,  // From latest data
            isCalibrated = true,
            clickCount = 340,  // From latest data
            xErrorCorrection = 1.0467837f,  // From latest data
            yErrorCorrection = 0.67287123f,  // From latest data
            xOffsetCorrection = 0f,  // Fixed: was causing cursor jumping
            yOffsetCorrection = 0f,  // Fixed: was causing cursor to always stay down
            avgErrorMagnitude = 1724.7052f  // From latest data
        )
        
        // Update system parameters
        updateSystemFromCalibration()
    }

    // --- Public Methods for Control ---

    fun setLandmarks(newLandmarks: List<Pair<Float, Float>>) {
        landmarks = newLandmarks
        invalidate()
    }

    fun clear() {
        landmarks = emptyList()
        invalidate()
    }

    fun updateEyePosition(leftX: Float? = null, leftY: Float? = null, leftZ: Float? = null, rightX: Float? = null, rightY: Float? = null, rightZ: Float? = null) {
        leftX?.let { leftEyeX = it }
        leftY?.let { leftEyeY = it }
        leftZ?.let { leftEyeZ = it }
        rightX?.let { rightEyeX = it }
        rightY?.let { rightEyeY = it }
        rightZ?.let { rightEyeZ = it }
        
        updateDistanceScaling()
        invalidate()
    }

    fun updatePointerControls(
        gazeSensitivityX: Float? = null,
        gazeSensitivityY: Float? = null,
        headSensitivity: Float? = null,
        headTiltYBaseSensitivity: Float? = null,
        gyroSensitivity: Float? = null,
        damping: Float? = null
    ) {
        gazeSensitivityX?.let { pointerGazeSensitivityX = it }
        gazeSensitivityY?.let { pointerGazeSensitivityY = it }
        headSensitivity?.let { pointerHeadSensitivity = it }
        headTiltYBaseSensitivity?.let { this.headTiltYBaseSensitivity = it }
        gyroSensitivity?.let { pointerGyroSensitivity = it }
        damping?.let { pointerDampingFactor = it }
        invalidate()
    }
    
    fun updateSphereStretchControls(
        stretchFactor: Float? = null,
        bottomStretchMultiplier: Float? = null,
        outerStretchMultiplier: Float? = null
    ) {
        stretchFactor?.let { sphereStretchFactor = it }
        bottomStretchMultiplier?.let { sphereBottomStretchMultiplier = it }
        outerStretchMultiplier?.let { sphereOuterStretchMultiplier = it }
        invalidate()
    }
    
    fun updateFPS(fps: Int) {
        currentFPS = fps
        invalidate()
    }
    
    fun updateGazeCurvature(
        enabled: Boolean? = null,
        downward: Float? = null,
        strength: Float? = null
    ) {
        enabled?.let { gazeCurvatureEnabled = it }
        downward?.let { gazeCurvatureDownward = it }
        strength?.let { gazeCurvatureStrength = it }
        invalidate()
    }
    
    fun updateEnhancedCursorControls(
        gazeTipChangeThreshold: Float? = null,
        minSphereSize: Float? = null,
        maxSphereSize: Float? = null,
        yPositionMultiplier: Float? = null
    ) {
        gazeTipChangeThreshold?.let { this.gazeTipChangeThreshold = it }
        minSphereSize?.let { this.minSphereSize = it }
        maxSphereSize?.let { this.maxSphereSize = it }
        invalidate()
    }
    
    // Dynamic Calibration Methods
    fun startCalibration() {
        isCalibrating = true
        calibrationPoints.clear()
        tapVisualizations.clear()
        calibrationClickCount = 0
        loadCalibrationData()
        invalidate()
    }
    
    fun stopCalibration() {
        isCalibrating = false
        saveCalibrationData()
        invalidate()
    }
    
    fun isCalibrationActive(): Boolean = isCalibrating
    
    fun getCalibrationProgress(): String {
        return if (isCalibrating) {
            "Calibrating... $calibrationClickCount clicks"
        } else {
            "Calibrated ✓ ($calibrationClickCount clicks)"
        }
    }
    
    fun handleCalibrationTap(tapX: Float, tapY: Float) {
        if (!isCalibrating || landmarks.isEmpty()) return
        
        // Get current cursor position
        val currentCursorX = lastPointerPosition?.first ?: (width / 2f)
        val currentCursorY = lastPointerPosition?.second ?: (height / 2f)
        
        // Calculate error between tap location and cursor position
        // Positive error means cursor needs to move right/up to reach tap
        val errorX = tapX - currentCursorX
        val errorY = tapY - currentCursorY
        val errorMagnitude = sqrt((errorX * errorX + errorY * errorY).toDouble()).toFloat()
        
        // Add tap visualization with error info
        tapVisualizations.add(TapVisualization(tapX, tapY, System.currentTimeMillis()))
        
        // Calculate current eye data
        val leftSphere = calculateEyeSphere(
            sphereLeftEyeIndices.mapNotNull { landmarks.getOrNull(it) },
            leftEyeX, leftEyeY, leftEyeZ, 0f, 0f, false
        )
        val rightSphere = calculateEyeSphere(
            sphereRightEyeIndices.mapNotNull { landmarks.getOrNull(it) },
            rightEyeX, rightEyeY, rightEyeZ, 0f, 0f, true
        )
        
        val averageEyeX = (leftSphere.centerX + rightSphere.centerX) / 2f
        val averageEyeY = (leftSphere.centerY + rightSphere.centerY) / 2f
        val averageSphereSize = (leftSphere.radius + rightSphere.radius) / 2f
        val gazeVelocity = calculateGazeTipChange(
            averageEyeX,
            averageEyeY
        )
        val yPositionInfluence = calculateYPositionInfluence(averageEyeY, height.toFloat())
        val distanceRange = calculateDistanceRange(averageSphereSize)
        val adaptiveSensitivity = calculateAdaptiveSensitivity(
            gazeVelocity, averageEyeY, height.toFloat(), averageSphereSize
        )
        
        // Store calibration point with error data
        calibrationPoints.add(
            CalibrationPoint(
                tapX, tapY, currentCursorX, currentCursorY, averageEyeY, averageSphereSize,
                gazeVelocity, yPositionInfluence, distanceRange, adaptiveSensitivity,
                errorX, errorY, errorMagnitude, averageSphereSize, averageEyeX, averageEyeY
            )
        )
        
        calibrationClickCount++
        
        // Update formulas every 20 clicks with error correction
        if (calibrationClickCount % 20 == 0) {
            updateFormulasFromCalibration()
        }
        
        // Check for AI optimization
        checkAiOptimization()
        
        invalidate()
    }
    
    // Reset calibration data
    fun resetCalibration() {
        calibrationPoints.clear()
        tapVisualizations.clear()
        calibrationClickCount = 0
        calibrationData = CalibrationData()
        isCalibrating = false
        
        // Reset to default values
        minSphereSize = 15f
        maxSphereSize = 45f
        typicalEyeMinYRatio = 0.4f
        typicalEyeMaxYRatio = 0.9f
        
        // Clear saved data
        try {
            val file = File(context.filesDir, "calibration_data.dat")
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            // Handle error silently
        }
        
        invalidate()
    }
    
    private fun updateFormulasFromCalibration() {
        if (calibrationPoints.isEmpty()) return
        
        // Calculate averages from all data (no cleaning)
        calibrationData.avgYPositionInfluence = calibrationPoints.map { it.yPositionInfluence }.average().toFloat()
        calibrationData.avgDistanceRange = calibrationPoints.map { it.distanceRange }.average().toFloat()
        calibrationData.avgGazeSensitivity = calibrationPoints.map { it.adaptiveSensitivity }.average().toFloat()
        
        // Calculate error-based corrections
        val avgErrorX = calibrationPoints.map { it.errorX }.average().toFloat()
        val avgErrorY = calibrationPoints.map { it.errorY }.average().toFloat()
        calibrationData.avgErrorMagnitude = calibrationPoints.map { it.errorMagnitude }.average().toFloat()
        
        // Calculate error correction factors (correct direction)
        val screenWidth = width.toFloat()
        val screenHeight = height.toFloat()
        
        // X correction: if cursor is consistently off to the right, increase X sensitivity
        calibrationData.xErrorCorrection = if (avgErrorX != 0f) {
            (1.0f + (avgErrorX / screenWidth) * 0.5f).coerceIn(0.5f, 1.5f)
        } else 1.0f
        
        // Y correction: if cursor is consistently off vertically, adjust Y sensitivity
        calibrationData.yErrorCorrection = if (avgErrorY != 0f) {
            (1.0f + (avgErrorY / screenHeight) * 0.5f).coerceIn(0.5f, 1.5f)
        } else 1.0f
        
        // Offset corrections: adjust base cursor position (correct direction)
        calibrationData.xOffsetCorrection = avgErrorX * 0.3f
        calibrationData.yOffsetCorrection = avgErrorY * 0.3f
        
        // Update eye Y ranges
        val eyeYs = calibrationPoints.map { it.averageEyeY }
        calibrationData.avgEyeYMin = eyeYs.minOrNull() ?: 0f
        calibrationData.avgEyeYMax = eyeYs.maxOrNull() ?: 0f
        
        // Update sphere size ranges
        val sphereSizes = calibrationPoints.map { it.averageSphereSize }
        calibrationData.avgSphereSizeMin = sphereSizes.minOrNull() ?: 0f
        calibrationData.avgSphereSizeMax = sphereSizes.maxOrNull() ?: 0f
        
        calibrationData.isCalibrated = true
        calibrationData.clickCount = calibrationClickCount
        
        // Update system parameters
        updateSystemFromCalibration()
    }
    
    private fun updateSystemFromCalibration() {
        if (!calibrationData.isCalibrated) return
        
        // Update sphere size ranges using provided optimized values
        minSphereSize = calibrationData.avgSphereSizeMin  // 65.951935f
        maxSphereSize = calibrationData.avgSphereSizeMax  // 119.09108f
        
        // Update eye Y ratios using provided optimized ranges
        val screenHeight = height.toFloat()
        val userMinRatio = calibrationData.avgEyeYMin / screenHeight  // 1155.8164f
        val userMaxRatio = calibrationData.avgEyeYMax / screenHeight  // 1665.7925f
        
        // Use the provided optimized ranges directly
        typicalEyeMinYRatio = userMinRatio.coerceIn(0.1f, 0.8f)
        typicalEyeMaxYRatio = userMaxRatio.coerceIn(0.2f, 0.95f)
        
        // Update distance scaling factor with provided value
        distanceScalingFactor = 171792.53f
        
        // Update gaze sensitivities with provided optimized values
        pointerGazeSensitivityX = 19.031168f
        pointerGazeSensitivityY = 0.011528987f
        
        // Update damping factor with provided value
        pointerDampingFactor = 0.9f
        
        invalidate()
    }
    
    // File persistence methods
    private fun saveCalibrationData() {
        try {
            val file = File(context.filesDir, "calibration_data.dat")
            val outputStream = ObjectOutputStream(FileOutputStream(file))
            outputStream.writeObject(calibrationData)
            outputStream.close()
        } catch (e: Exception) {
            // Handle error silently
        }
    }
    
    private fun loadCalibrationData() {
        try {
            val file = File(context.filesDir, "calibration_data.dat")
            if (file.exists()) {
                val inputStream = ObjectInputStream(FileInputStream(file))
                calibrationData = inputStream.readObject() as CalibrationData
                inputStream.close()
                
                // Update system parameters from loaded data
                updateSystemFromCalibration()
            }
        } catch (e: Exception) {
            // Handle error silently, use defaults
            calibrationData = CalibrationData()
        }
    }
    
    
    


    // --- Gyroscope Sensor Handling ---

    private fun startGyroscopeListening() {
        gyroscopeSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun stopGyroscopeListening() {
        sensorManager.unregisterListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopGyroscopeListening()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            val gyroX = event.values[0]
            val gyroY = event.values[1]

            val currentTime = System.currentTimeMillis()
            if (lastGyroTimestamp != 0L) {
                val deltaTime = (currentTime - lastGyroTimestamp) / 1000.0f
                gyroVelocityX = (gyroY * gyroSensitivity * deltaTime).coerceIn(-3f, 3f)
                gyroVelocityY = (-gyroX * gyroSensitivity * deltaTime).coerceIn(-3f, 3f)
            }
            lastGyroTimestamp = currentTime
        }
    }


    // --- Calculation and Helper Functions ---

    private fun updateDistanceScaling() {
        val averageZ = (leftEyeZ + rightEyeZ) / 2.0f
        distanceScalingFactor = (neutralFaceDepth / (averageZ + neutralFaceDepth)).coerceIn(0.5f, 2.0f)
    }
    
    // Enhanced distance ranging function (1-3 range)
    private fun calculateDistanceRange(sphereSize: Float): Float {
        val normalizedSize = ((sphereSize - minSphereSize) / (maxSphereSize - minSphereSize)).coerceIn(0f, 1f)
        return 1f + (normalizedSize * 2f) // Range: 1-3
    }
    
    // Calculate normalized eye position (0-1) within typical range
    private fun calculateNormalizedEyePosition(eyeY: Float, screenHeight: Float): Float {
        // Use full screen range for better upward gaze detection
        val minY = screenHeight * 0.05f  // Top 5% of screen
        val maxY = screenHeight * 0.95f  // Bottom 5% of screen
        val eyeRange = maxY - minY
        
        return ((eyeY - minY) / eyeRange).coerceIn(0f, 1f)
    }
    
    // Calculate gaze line tip change for better starting point detection
    private fun calculateGazeTipChange(currentGazeX: Float, currentGazeY: Float): Float {
        val deltaX = currentGazeX - lastGazeTipX
        val deltaY = currentGazeY - lastGazeTipY
        val velocity = sqrt(deltaX * deltaX + deltaY * deltaY)
        
        lastGazeTipX = currentGazeX
        lastGazeTipY = currentGazeY
        gazeTipVelocity = velocity
        
        return velocity
    }
    
    // Calculate Y position influence based on eye position relative to screen
    private fun calculateYPositionInfluence(eyeY: Float, screenHeight: Float): Float {
        val normalizedEyeY = calculateNormalizedEyePosition(eyeY, screenHeight)
        
        // Map to -3 to +3 range for stronger cursor influence
        val yInfluence = (normalizedEyeY - 0.5f) * 6f
        
        // Apply enhanced scaling for better upward movement
        val scaledInfluence = yInfluence * (2f + abs(yInfluence) * 0.3f)
        
        return scaledInfluence.coerceIn(-3f, 3f)
    }
    
    // Calculate eye Y velocity for adaptive control
    private fun calculateEyeYVelocity(currentEyeY: Float): Float {
        val deltaY = currentEyeY - lastEyeYPosition
        lastEyeYPosition = currentEyeY
        eyeYVelocity = deltaY
        return deltaY
    }
    
    // Enhanced adaptive sensitivity based on user behavior
    private fun calculateAdaptiveSensitivity(gazeVelocity: Float, eyeY: Float, screenHeight: Float, sphereSize: Float): Float {
        val baseSensitivity = 0.8f  // Reduced base sensitivity
        
        // Movement factor based on gaze velocity
        val movementFactor = when {
            gazeVelocity < 2f -> 0.5f      // Reduce sensitivity for drift
            gazeVelocity < 8f -> 0.8f      // Normal sensitivity
            gazeVelocity < 15f -> 1.2f     // Increase for deliberate movement
            else -> 1.5f                   // High sensitivity for rapid movement
        }
        
        // Position factor - enhance sensitivity for upward gaze
        val normalizedEyeY = calculateNormalizedEyePosition(eyeY, screenHeight)
        val positionFactor = if (normalizedEyeY < 0.3f) {
            1.5f  // Higher sensitivity for upper screen areas
        } else {
            0.8f + (normalizedEyeY * 0.4f)  // Range: 0.8-1.2
        }
        
        // Distance factor (closer = more sensitive)
        val distanceRange = calculateDistanceRange(sphereSize)
        val distanceFactor = 2f - distanceRange  // Range: -1 to 1 (inverse relationship)
        
        return baseSensitivity * movementFactor * positionFactor * (1f + distanceFactor * 0.2f)
    }

    private fun adjustPosition(point: Pair<Float, Float>, xOffset: Float, yOffset: Float, zOffset: Float): Pair<Float, Float> {
        val zScale = 1f + (zOffset / 1000f)
        return Pair(point.first + xOffset * zScale, point.second + yOffset * zScale)
    }

    private fun calculateEyeSphere(eyePoints: List<Pair<Float, Float>>, xOffset: Float, yOffset: Float, zOffset: Float, headDirectionX: Float = 0f, headDirectionY: Float = 0f, isRightEye: Boolean = true): EyeSphere {
        if (eyePoints.isEmpty()) return EyeSphere(0f, 0f, 0f)
        
        val adjustedPoints = eyePoints.map { adjustPosition(it, xOffset, yOffset, zOffset) }
        val centerX = adjustedPoints.map { it.first }.average().toFloat()
        val centerY = adjustedPoints.map { it.second }.average().toFloat()
        val radius = adjustedPoints.map { point -> sqrt((point.first - centerX).pow(2) + (point.second - centerY).pow(2)) }.average().toFloat()
        val zScale = 1f + (zOffset / 1000f)
        
        // Small downward adjustment to bring gaze lines up a little (just a few pixels)
        val adjustedCenterY = centerY + 6f
        
        return EyeSphere(centerX, adjustedCenterY, radius, radius * 2f, zScale)
    }

    private fun calculateGazeLine(sphereCenter: Pair<Float, Float>, pupilPoint: Pair<Float, Float>, extensionFactor: Float = 2f): GazeLine {
        val directionX = pupilPoint.first - sphereCenter.first
        val directionY = pupilPoint.second - sphereCenter.second
        
        // Apply downward curvature to the gaze line
        val curvatureEffect = if (gazeCurvatureEnabled) {
            // Calculate how much to curve downward based on the line length and curvature settings
            val lineLength = sqrt(directionX * directionX + directionY * directionY)
            val curvatureAmount = lineLength * gazeCurvatureDownward * gazeCurvatureStrength
            curvatureAmount
        } else {
            0f
        }
        
        // Apply the curvature by adding downward component
        val curvedDirectionX = directionX
        val curvedDirectionY = directionY + curvatureEffect
        
        val endX = pupilPoint.first + curvedDirectionX * extensionFactor
        val endY = pupilPoint.second + curvedDirectionY * extensionFactor
        
        return GazeLine(sphereCenter.first, sphereCenter.second, endX, endY)
    }
    
    private fun calculateWeightedAveragePoint(facePoints: List<Pair<Float, Float>>, yOffset: Float): Pair<Float, Float> {
        if (facePoints.isEmpty()) return Pair(0f, 0f)
        val sumX = facePoints.sumOf { it.first.toDouble() }.toFloat()
        val sumY = facePoints.sumOf { it.second.toDouble() }.toFloat()
        return Pair(sumX / facePoints.size, (sumY / facePoints.size) + yOffset)
    }
    
    private fun calculateHeadDirection(start: Pair<Float, Float>, end: Pair<Float, Float>): Triple<Float, Float, Float> {
        val dx = end.first - start.first
        val dy = end.second - start.second
        val magnitude = sqrt(dx * dx + dy * dy)
        val angle = atan2(dy, dx)
        val normalizedX = cos(angle)
        val normalizedY = sin(angle)
        return Triple(normalizedX, normalizedY, magnitude)
    }

    private fun calculateEyeHeight(eyePoints: List<Pair<Float, Float>>): Float {
        if (eyePoints.isEmpty()) return 0f
        val minY = eyePoints.minOf { it.second }
        val maxY = eyePoints.maxOf { it.second }
        return abs(maxY - minY)
    }

    private fun getAverageEyeHeight(eyeHeightHistory: LinkedList<Pair<Long, Float>>): Float {
        val currentTime = System.currentTimeMillis()
        eyeHeightHistory.removeAll { currentTime - it.first > heightHistoryDuration }
        if (eyeHeightHistory.isEmpty()) return 0f
        return eyeHeightHistory.sumOf { it.second.toDouble() }.toFloat() / eyeHeightHistory.size
    }
    
    private fun draw3DSphere(canvas: Canvas, sphere: EyeSphere, isRightEye: Boolean) {
        if (sphere.radius <= 0) return
    
        val adjustedRadius = sphere.scaledRadius * sphere.zScale
    
        val colors = if (isRightEye) {
            intArrayOf(Color.argb((200 * sphere.zScale).toInt(), 100, 150, 255), Color.argb((180 * sphere.zScale).toInt(), 50, 100, 255), Color.argb((160 * sphere.zScale).toInt(), 0, 50, 255))
        } else {
            intArrayOf(Color.argb((200 * sphere.zScale).toInt(), 100, 255, 100), Color.argb((180 * sphere.zScale).toInt(), 50, 255, 50), Color.argb((160 * sphere.zScale).toInt(), 0, 200, 0))
        }
    
        val gradient = RadialGradient(sphere.centerX - adjustedRadius * 0.2f, sphere.centerY - adjustedRadius * 0.2f, adjustedRadius, colors, floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
    
        val spherePaint = Paint().apply { isAntiAlias = true; shader = gradient; style = Paint.Style.FILL }
        canvas.drawCircle(sphere.centerX, sphere.centerY, adjustedRadius, spherePaint)
    }

    // --- Calibration UI Drawing ---
    
    private fun drawCalibrationUI(canvas: Canvas) {
        val currentTime = System.currentTimeMillis()
        
        // Draw tap visualizations
        tapVisualizations.removeAll { currentTime - it.startTime > it.duration }
        
        val tapPaint = Paint().apply {
            color = Color.CYAN
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        val tapStrokePaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        
        // Draw tap circles with fade effect and error vectors
        tapVisualizations.forEach { tap ->
            val elapsed = currentTime - tap.startTime
            val alpha = (255 * (1f - elapsed.toFloat() / tap.duration)).toInt().coerceIn(0, 255)
            val size = 20f + (elapsed.toFloat() / tap.duration) * 30f
            
            tapPaint.alpha = alpha
            tapStrokePaint.alpha = alpha
            
            canvas.drawCircle(tap.x, tap.y, size, tapStrokePaint)
            canvas.drawCircle(tap.x, tap.y, size * 0.7f, tapPaint)
        }
        
        // Draw error vectors for recent calibration points
        val recentPoints = calibrationPoints.takeLast(5) // Show last 5 points
        val errorPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        
        recentPoints.forEach { point ->
            val errorAlpha = (255 * (1f - (currentTime - point.timestamp).toFloat() / 5000f)).toInt().coerceIn(0, 255)
            errorPaint.alpha = errorAlpha
            
            // Draw error vector from cursor to tap location
            canvas.drawLine(
                point.cursorX, point.cursorY,
                point.tapX, point.tapY,
                errorPaint
            )
            
            // Draw small circles at both ends
            canvas.drawCircle(point.cursorX, point.cursorY, 8f, errorPaint)
            canvas.drawCircle(point.tapX, point.tapY, 8f, errorPaint)
        }
        
        // Draw instruction text
        val instructionText = "Look at where you want to click, then tap anywhere"
        val instructionPaint = Paint().apply {
            color = Color.YELLOW
            textSize = 32f
            style = Paint.Style.FILL
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        
        canvas.drawText(instructionText, width / 2f, height - 100f, instructionPaint)
        
        // Draw calibration status
        val statusText = getCalibrationProgress()
        val statusPaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            style = Paint.Style.FILL
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        
        canvas.drawText(statusText, width / 2f, height - 60f, statusPaint)
        
        // Draw data quality indicator
        if (calibrationPoints.isNotEmpty()) {
            val qualityText = "Data Points: ${calibrationPoints.size}"
            val qualityPaint = Paint().apply {
                color = Color.LTGRAY
                textSize = 24f
                style = Paint.Style.FILL
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            
            canvas.drawText(qualityText, width / 2f, height - 30f, qualityPaint)
        }
    }

    // --- Main Drawing Logic ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)


        if (landmarks.isEmpty()) return

        // Landmark and Sphere Processing
        val sphereRightEyePoints = sphereRightEyeIndices.mapNotNull { landmarks.getOrNull(it) }
        val sphereLeftEyePoints = sphereLeftEyeIndices.mapNotNull { landmarks.getOrNull(it) }
        val rightEyePoints = rightEyeIndices.mapNotNull { landmarks.getOrNull(it) }
        val leftEyePoints = leftEyeIndices.mapNotNull { landmarks.getOrNull(it) }
        val leftPupil = landmarks.getOrNull(leftPupilIndex)
        val rightPupil = landmarks.getOrNull(rightPupilIndex)

        // Calculate head direction first
        val headDirectionPoints = headDirectionIndices.mapNotNull { landmarks.getOrNull(it) }
        val headDirectionTarget = landmarks.getOrNull(headDirectionTargetIndex)
        var headDirectionX = 0f
        var headDirectionY = 0f
        
        headDirectionTarget?.let {
            val averageFacePoint = calculateWeightedAveragePoint(headDirectionPoints, averageFaceWeightYOffset)
            val (dirX, dirY, magnitude) = calculateHeadDirection(averageFacePoint, it)
            headDirectionX = dirX * magnitude
            headDirectionY = dirY * magnitude
        }
        
        val rightSphere = calculateEyeSphere(sphereRightEyePoints, rightEyeX, rightEyeY, rightEyeZ, headDirectionX, headDirectionY, isRightEye = true)
        val leftSphere = calculateEyeSphere(sphereLeftEyePoints, leftEyeX, leftEyeY, leftEyeZ, headDirectionX, headDirectionY, isRightEye = false)

        draw3DSphere(canvas, leftSphere, isRightEye = false)
        draw3DSphere(canvas, rightSphere, isRightEye = true)

        // Gaze Vector Calculation
        var totalGazeX = 0f
        var totalGazeY = 0f
        var gazeCount = 0

        leftPupil?.let {
            val gazeLine = calculateGazeLine(Pair(leftSphere.centerX, leftSphere.centerY), it)
            canvas.drawLine(gazeLine.startX, gazeLine.startY, gazeLine.endX, gazeLine.endY, gazePaint)
            totalGazeX += gazeLine.endX
            totalGazeY += gazeLine.endY
            gazeCount++
        }
        rightPupil?.let {
            val gazeLine = calculateGazeLine(Pair(rightSphere.centerX, rightSphere.centerY), it)
            canvas.drawLine(gazeLine.startX, gazeLine.startY, gazeLine.endX, gazeLine.endY, gazePaint)
            totalGazeX += gazeLine.endX
            totalGazeY += gazeLine.endY
            gazeCount++
        }

        if (gazeCount > 0) {
            val averageGazeX = totalGazeX / gazeCount
            val averageGazeY = totalGazeY / gazeCount

            // Draw head direction vector
            headDirectionTarget?.let {
                val averageFacePoint = calculateWeightedAveragePoint(headDirectionPoints, averageFaceWeightYOffset)
                val (dirX, dirY, magnitude) = calculateHeadDirection(averageFacePoint, it)
                canvas.drawLine(averageFacePoint.first, averageFacePoint.second, it.first + dirX * 2, it.second + dirY * 2, headVectorPaint)
            }

            // --- ENHANCED POINTER LOCATION LOGIC ---

            // 1. Calculate the actual gaze direction vector from the average gaze point
            val gazeDirectionX = averageGazeX - ((leftSphere.centerX + rightSphere.centerX) / 2f)
            val gazeDirectionY = averageGazeY - ((leftSphere.centerY + rightSphere.centerY) / 2f)
            
            // 2. Calculate gaze tip change for adaptive control
            val gazeTipVelocity = calculateGazeTipChange(averageGazeX, averageGazeY)
            
            // 3. Calculate eye Y position influence
            val averageEyeY = (leftSphere.centerY + rightSphere.centerY) / 2f
            val eyeYInfluence = calculateYPositionInfluence(averageEyeY, height.toFloat())
            val eyeYVelocity = calculateEyeYVelocity(averageEyeY)
            
            // 4. Calculate distance range (1-3) based on sphere size
            val averageSphereSize = (leftSphere.radius + rightSphere.radius) / 2f
            val distanceRange = calculateDistanceRange(averageSphereSize)
            
            // 5. Calculate adaptive sensitivity based on user behavior
            adaptiveSensitivity = calculateAdaptiveSensitivity(gazeTipVelocity, averageEyeY, height.toFloat(), averageSphereSize)
            
            // 6. Calculate the screen center as the base reference point
            val screenCenterX = width / 2f + staticX
            val screenCenterY = height / 2f + staticY
            
            // 7. Apply head direction influence to the screen center
            val headInfluencedCenterX = screenCenterX + (headDirectionX * pointerHeadSensitivity)
            val headInfluencedCenterY = screenCenterY - (headDirectionY * headTiltYBaseSensitivity)
            
            // 8. Map gaze direction to screen coordinates with controlled scaling
            val gazeScreenOffsetX = gazeDirectionX * pointerGazeSensitivityX * adaptiveSensitivity * (1f + distanceRange * 0.1f)
            val gazeScreenOffsetY = gazeDirectionY * pointerGazeSensitivityY * adaptiveSensitivity * (1f + distanceRange * 0.1f)
            
            // 9. Apply eye Y position influence with improved calculation
            val yPositionInfluence = calculateYPositionInfluence(averageEyeY, height.toFloat())
            val yPositionOffset = yPositionInfluence * 120f // Further increased for better upward movement
            
            // 10. Calculate gyro influence for stabilization
            val gyroInfluenceX = -gyroVelocityX * pointerGyroSensitivity
            val gyroInfluenceY = -gyroVelocityY * pointerGyroSensitivity

            // 11. Calculate the final target position
            val targetX = headInfluencedCenterX + gazeScreenOffsetX + gyroInfluenceX
            val targetY = headInfluencedCenterY + gazeScreenOffsetY + gyroInfluenceY + yPositionOffset
            
            // 12. Apply enhanced error correction with provided optimized values
            val correctedTargetX = if (calibrationData.isCalibrated) {
                // Apply only multiplicative correction factor, no offset to prevent jumping
                targetX * calibrationData.xErrorCorrection
            } else {
                targetX
            }
            
            val correctedTargetY = if (calibrationData.isCalibrated) {
                // Apply only multiplicative correction factor, no offset to prevent downward bias
                targetY * calibrationData.yErrorCorrection
            } else {
                targetY
            }
            
            // 13. Apply Damping for Smooth Motion.
            val previousPosition = lastPointerPosition ?: Pair(correctedTargetX, correctedTargetY)
            val finalX = previousPosition.first + (correctedTargetX - previousPosition.first) * pointerDampingFactor
            val finalY = previousPosition.second + (correctedTargetY - previousPosition.second) * pointerDampingFactor

            // 6. Clamp to screen bounds and draw the pointer.
            val clampedX = finalX.coerceIn(0f, width.toFloat())
            val clampedY = finalY.coerceIn(0f, height.toFloat())

            canvas.drawCircle(clampedX, clampedY, 20f, pointerPaint)
            lastPointerPosition = Pair(clampedX, clampedY)

            // --- Blink/Click Detection Logic ---
            val currentTime = System.currentTimeMillis()
            val currentLeftEyeHeight = calculateEyeHeight(leftEyePoints)
            val currentRightEyeHeight = calculateEyeHeight(rightEyePoints)
            leftEyeHeightHistory.add(Pair(currentTime, currentLeftEyeHeight))
            rightEyeHeightHistory.add(Pair(currentTime, currentRightEyeHeight))

            if (currentTime - lastOpenEyeUpdateTime > 1000L) {
                val newLeftOpen = getAverageEyeHeight(leftEyeHeightHistory)
                val newRightOpen = getAverageEyeHeight(rightEyeHeightHistory)
                if (newLeftOpen > 5f) leftEyeOpenHeight = newLeftOpen
                if (newRightOpen > 5f) rightEyeOpenHeight = newRightOpen
                lastOpenEyeUpdateTime = currentTime
            }

            val avgLeftEyeHeight = getAverageEyeHeight(leftEyeHeightHistory)
            val avgRightEyeHeight = getAverageEyeHeight(rightEyeHeightHistory)
            val leftClosure = if (leftEyeOpenHeight > 0) 1f - (avgLeftEyeHeight / leftEyeOpenHeight) else 0f
            val rightClosure = if (rightEyeOpenHeight > 0) 1f - (avgRightEyeHeight / rightEyeOpenHeight) else 0f
            val maxClosure = maxOf(leftClosure, rightClosure).coerceIn(0f, 1f)

            val isHalfClosed = maxClosure in (HALF_BLINK_THRESHOLD + 0.01f)..(FULL_BLINK_THRESHOLD - 0.01f)
            val isFullyClosed = maxClosure >= FULL_BLINK_THRESHOLD

            if (isFullyClosed) {
                halfBlinkStartTime = 0
                isClickTriggered = false
            } else if (isHalfClosed) {
                if (halfBlinkStartTime == 0L) {
                    halfBlinkStartTime = currentTime
                }
                val clickDuration = currentTime - halfBlinkStartTime
                if (clickDuration in CLICK_MIN_DURATION_MS..CLICK_MAX_DURATION_MS && !isClickTriggered) {
                    showBlueDot = true
                    blueDotStartTime = currentTime
                    savedClickPosition = Pair(clampedX, clampedY)
                    isClickTriggered = true
                }
            } else {
                halfBlinkStartTime = 0
                isClickTriggered = false
            }

            if (showBlueDot) {
                savedClickPosition?.let { canvas.drawCircle(it.first, it.second, 15f, clickDotPaint) }
                if (currentTime - blueDotStartTime > 1500) {
                    showBlueDot = false
                    savedClickPosition = null
                }
            }
        }
        
        // Draw calibration UI
        if (isCalibrating) {
            drawCalibrationUI(canvas)
        }
        
        // Draw raw landmarks for debugging
        for ((index, landmark) in landmarks.withIndex()) {
            val paintToUse = when (index) {
                leftPupilIndex, rightPupilIndex -> purplePaint
                in rightEyeIndices -> bluePaint
                in leftEyeIndices -> greenPaint
                else -> whitePaint.apply { alpha = 100 }
            }
            canvas.drawCircle(landmark.first, landmark.second, 3f, paintToUse)
        }
        
        // Draw Debug Text
        val pointerPosText = "Pointer: (${lastPointerPosition?.first?.toInt()}, ${lastPointerPosition?.second?.toInt()})"
        val gazeYText = "Gaze Y Sens: ${"%.2f".format(pointerGazeSensitivityY)}"
        val headTiltYText = "Head Tilt Y Sens: ${"%.2f".format(headTiltYBaseSensitivity)}"
        canvas.drawText(pointerPosText, 20f, 50f, textPaint)
        canvas.drawText(gazeYText, 20f, 90f, textPaint)
        canvas.drawText(headTiltYText, 20f, 130f, textPaint)
        val fpsText = "FPS: $currentFPS"
        val gazeText = "Gaze: ${if (gazeCurvatureEnabled) "ON" else "OFF"} D${"%.2f".format(gazeCurvatureDownward)} S${"%.2f".format(gazeCurvatureStrength)}"
        
        canvas.drawText(fpsText, 20f, 170f, textPaint)
        canvas.drawText(gazeText, 20f, 210f, textPaint)
        
        // Enhanced debug information
        val averageEyeY = (leftSphere.centerY + rightSphere.centerY) / 2f
        val averageSphereSize = (leftSphere.radius + rightSphere.radius) / 2f
        val normalizedEyeY = calculateNormalizedEyePosition(averageEyeY, height.toFloat())
        
        val distanceRangeText = "Distance Range: ${"%.2f".format(calculateDistanceRange(averageSphereSize))}"
        val gazeVelocityText = "Gaze Velocity: ${"%.2f".format(gazeTipVelocity)}"
        val adaptiveSensText = "Adaptive Sens: ${"%.2f".format(adaptiveSensitivity)}"
        val eyeYInfluenceText = "Eye Y Influence: ${"%.2f".format(calculateYPositionInfluence(averageEyeY, height.toFloat()))}"
        val normalizedEyeText = "Normalized Eye Y: ${"%.2f".format(normalizedEyeY)}"
        
        canvas.drawText(distanceRangeText, 20f, 250f, textPaint)
        canvas.drawText(gazeVelocityText, 20f, 290f, textPaint)
        canvas.drawText(adaptiveSensText, 20f, 330f, textPaint)
        canvas.drawText(eyeYInfluenceText, 20f, 370f, textPaint)
        canvas.drawText(normalizedEyeText, 20f, 410f, textPaint)
        
        // Calibration data info
        val calibrationInfoText = getCalibrationDataInfo()
        canvas.drawText(calibrationInfoText, 20f, 450f, textPaint)
        
        // AI prompting message
        if (isAiPrompting) {
            val promptingTime = (System.currentTimeMillis() - aiPromptingStartTime) / 1000f
            val aiPromptingText = "🤖 AI is analyzing your data and generating new formula... (${promptingTime.toInt()}s)\nPlease wait and avoid clicking too much during optimization."
            val aiPromptingPaint = Paint().apply {
                color = Color.YELLOW
                textSize = 24f
                isFakeBoldText = true
            }
            canvas.drawText(aiPromptingText, 20f, 490f, aiPromptingPaint)
        }
        
        // Error correction info
        if (calibrationData.isCalibrated) {
            val errorCorrectionText = "X Corr: ${"%.2f".format(calibrationData.xErrorCorrection)}, Y Corr: ${"%.2f".format(calibrationData.yErrorCorrection)}"
            val errorOffsetText = "X Off: ${"%.1f".format(calibrationData.xOffsetCorrection)}, Y Off: ${"%.1f".format(calibrationData.yOffsetCorrection)}"
            val avgErrorText = "Avg Error: ${"%.1f".format(calibrationData.avgErrorMagnitude)}px"
            
            canvas.drawText(errorCorrectionText, 20f, 490f, textPaint)
            canvas.drawText(errorOffsetText, 20f, 530f, textPaint)
            canvas.drawText(avgErrorText, 20f, 570f, textPaint)
        }
        
        // Debug information
        if (debugInfoEnabled) {
            drawDebugInformation(canvas)
        }
    }
    
    
    // Get calibration status for UI
    fun getCalibrationStatus(): String {
        return when {
            isCalibrating -> "Calibrating... $calibrationClickCount clicks"
            calibrationData.isCalibrated -> "Calibrated ✓ ($calibrationClickCount clicks)"
            else -> "Not Calibrated"
        }
    }
    
    // Get calibration data for debugging
    fun getCalibrationDataInfo(): String {
        return if (calibrationData.isCalibrated) {
            "Y Inf: ${"%.2f".format(calibrationData.avgYPositionInfluence)}, " +
            "Dist: ${"%.2f".format(calibrationData.avgDistanceRange)}, " +
            "Gaze: ${"%.2f".format(calibrationData.avgGazeSensitivity)}, " +
            "Error: ${"%.1f".format(calibrationData.avgErrorMagnitude)}px"
        } else {
            "No calibration data"
        }
    }
    
    // Debug information display
    private fun drawDebugInformation(canvas: Canvas) {
        val debugPaint = Paint().apply {
            color = Color.WHITE
            textSize = 12f
            style = Paint.Style.FILL
            isAntiAlias = true
            setShadowLayer(2f, 0f, 0f, Color.BLACK)
        }
        
        var yOffset = 650f
        
        // Eye position information
        if (landmarks.isNotEmpty()) {
            val leftSphere = calculateEyeSphere(
                sphereLeftEyeIndices.mapNotNull { landmarks.getOrNull(it) },
                leftEyeX, leftEyeY, leftEyeZ, 0f, 0f, false
            )
            val rightSphere = calculateEyeSphere(
                sphereRightEyeIndices.mapNotNull { landmarks.getOrNull(it) },
                rightEyeX, rightEyeY, rightEyeZ, 0f, 0f, true
            )
            
            val avgEyeX = (leftSphere.centerX + rightSphere.centerX) / 2f
            val avgEyeY = (leftSphere.centerY + rightSphere.centerY) / 2f
            val avgSphereSize = (leftSphere.radius + rightSphere.radius) / 2f
            
            canvas.drawText("Eye Position: X=${"%.1f".format(avgEyeX)}, Y=${"%.1f".format(avgEyeY)}", 20f, yOffset, debugPaint)
            yOffset += 20f
            canvas.drawText("Eye Distance: ${"%.1f".format(avgSphereSize)}", 20f, yOffset, debugPaint)
            yOffset += 20f
            
            // Head direction
            val headDirectionPoints = headDirectionIndices.mapNotNull { landmarks.getOrNull(it) }
            val headDirectionTarget = landmarks.getOrNull(headDirectionTargetIndex)
            headDirectionTarget?.let {
                val averageFacePoint = calculateWeightedAveragePoint(headDirectionPoints, averageFaceWeightYOffset)
                val (dirX, dirY, magnitude) = calculateHeadDirection(averageFacePoint, it)
                canvas.drawText("Head Direction: X=${"%.2f".format(dirX)}, Y=${"%.2f".format(dirY)}", 20f, yOffset, debugPaint)
                yOffset += 20f
            }
            
            // Current error if calibrating
            if (isCalibrating && calibrationPoints.isNotEmpty()) {
                val lastPoint = calibrationPoints.last()
                canvas.drawText("Last Error: X=${"%.1f".format(lastPoint.errorX)}, Y=${"%.1f".format(lastPoint.errorY)}", 20f, yOffset, debugPaint)
                yOffset += 20f
                canvas.drawText("Error Magnitude: ${"%.1f".format(lastPoint.errorMagnitude)}px", 20f, yOffset, debugPaint)
                yOffset += 20f
            }
            
            // AI optimization status
            if (aiOptimizationEnabled) {
                canvas.drawText("AI Optimization: ENABLED", 20f, yOffset, debugPaint)
                yOffset += 20f
                if (aiOptimizedFormula.isNotEmpty()) {
                    canvas.drawText("Last AI Update: ${(System.currentTimeMillis() - lastAiOptimizationTime) / 1000}s ago", 20f, yOffset, debugPaint)
                    yOffset += 20f
                }
            }
        }
    }
    
    // AI optimization methods
    fun setDebugInfoEnabled(enabled: Boolean) {
        debugInfoEnabled = enabled
        invalidate()
    }
    
    fun setAiOptimizationEnabled(enabled: Boolean, apiKey: String, modelName: String) {
        aiOptimizationEnabled = enabled
        geminiApiKey = apiKey
        geminiModelName = modelName
    }
    
    // Check if AI optimization should be triggered
    private fun checkAiOptimization() {
        if (!aiOptimizationEnabled || geminiApiKey.isEmpty()) return
        if (!isCalibrating) return
        if (calibrationPoints.size < minCalibrationPoints) return
        
        // Check if we have enough high-error points
        val highErrorPoints = calibrationPoints.filter { it.errorMagnitude > errorThreshold }
        if (highErrorPoints.size >= 5) {
            // Trigger AI optimization
            optimizeFormulaWithAI()
        }
    }
    
    private fun optimizeFormulaWithAI() {
        // Show AI prompting message
        isAiPrompting = true
        aiPromptingStartTime = System.currentTimeMillis()
        lastAiOptimizationTime = System.currentTimeMillis()
        
        // Get last 100 data points for analysis
        val recentPoints = calibrationPoints.takeLast(100)
        if (recentPoints.size < 10) {
            isAiPrompting = false
            return
        }
        
        // Analyze error patterns
        val errorAnalysis = analyzeErrorPatterns(recentPoints)
        
        // Create AI-optimized parameters
        val optimizedParams = createOptimizedParameters(errorAnalysis)
        
        // Apply optimizations
        applyOptimizedParameters(optimizedParams)
        
        // Save formula snapshot
        saveFormulaSnapshot(optimizedParams, errorAnalysis)
        
        val advancedFormulas = generateAdvancedFormula(errorAnalysis)
        
        aiOptimizedFormula = "AI optimization completed at ${lastAiOptimizationTime}\n" +
                "Optimized ${recentPoints.size} recent data points\n" +
                "Average error reduced by ${errorAnalysis.errorReduction}%\n\n" +
                "Advanced Formula Variations:\n$advancedFormulas"
    }
    
    private fun analyzeErrorPatterns(points: List<CalibrationPoint>): ErrorAnalysis {
        val avgErrorX = points.map { it.errorX }.average().toFloat()
        val avgErrorY = points.map { it.errorY }.average().toFloat()
        val avgErrorMagnitude = points.map { it.errorMagnitude }.average().toFloat()
        
        // Analyze distance correlation
        val distanceErrors = points.map { it.sphereSize to it.errorMagnitude }
        val distanceCorrelation = calculateCorrelation(distanceErrors)
        
        // Analyze Y position correlation
        val yPositionErrors = points.map { it.eyeYPosition to it.errorY }
        val yPositionCorrelation = calculateCorrelation(yPositionErrors)
        
        // Analyze X position correlation
        val xPositionErrors = points.map { it.eyeXPosition to it.errorX }
        val xPositionCorrelation = calculateCorrelation(xPositionErrors)
        
        return ErrorAnalysis(
            avgErrorX = avgErrorX,
            avgErrorY = avgErrorY,
            avgErrorMagnitude = avgErrorMagnitude,
            distanceCorrelation = distanceCorrelation,
            yPositionCorrelation = yPositionCorrelation,
            xPositionCorrelation = xPositionCorrelation,
            errorReduction = calculateErrorReduction(points)
        )
    }
    
    private fun calculateCorrelation(data: List<Pair<Float, Float>>): Float {
        if (data.size < 2) return 0f
        
        val n = data.size
        val sumX = data.sumOf { it.first.toDouble() }
        val sumY = data.sumOf { it.second.toDouble() }
        val sumXY = data.sumOf { it.first * it.second.toDouble() }
        val sumX2 = data.sumOf { (it.first * it.first).toDouble() }
        val sumY2 = data.sumOf { (it.second * it.second).toDouble() }
        
        val numerator = n * sumXY - sumX * sumY
        val denominator = sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY))
        
        return if (denominator != 0.0) (numerator / denominator).toFloat() else 0f
    }
    
    private fun calculateErrorReduction(points: List<CalibrationPoint>): Float {
        if (points.size < 20) return 0f
        
        val firstHalf = points.take(points.size / 2)
        val secondHalf = points.drop(points.size / 2)
        
        val firstHalfAvg = firstHalf.map { it.errorMagnitude }.average()
        val secondHalfAvg = secondHalf.map { it.errorMagnitude }.average()
        
        return if (firstHalfAvg > 0) {
            ((firstHalfAvg - secondHalfAvg) / firstHalfAvg * 100).toFloat()
        } else 0f
    }
    
    private fun createOptimizedParameters(analysis: ErrorAnalysis): OptimizedParameters {
        // Enhanced parameter optimization with advanced mathematical operators
        
        // X sensitivity with exponential scaling for high correlation
        val xSensitivityAdjustment = if (analysis.xPositionCorrelation > 0.3f) {
            val baseAdjustment = 1.0f + (analysis.avgErrorX / 1000f) * 0.1f
            // Apply exponential scaling for stronger correlations
            if (analysis.xPositionCorrelation > 0.7f) {
                baseAdjustment * 1.2f.pow(analysis.xPositionCorrelation)
            } else {
                baseAdjustment
            }
        } else 1.0f
        
        // Y sensitivity with logarithmic scaling for better fine-tuning
        val ySensitivityAdjustment = if (analysis.yPositionCorrelation > 0.3f) {
            val baseAdjustment = 1.0f + (analysis.avgErrorY / 1000f) * 0.1f
            // Apply logarithmic scaling for smoother adjustments
            if (analysis.yPositionCorrelation > 0.5f) {
                baseAdjustment * (1.0f + ln(analysis.yPositionCorrelation + 1.0f))
            } else {
                baseAdjustment
            }
        } else 1.0f
        
        // Distance adjustment with power function scaling
        val distanceAdjustment = if (analysis.distanceCorrelation > 0.3f) {
            val baseAdjustment = 1.0f + (analysis.distanceCorrelation * 0.2f)
            // Apply power scaling for non-linear distance effects
            baseAdjustment.pow(1.0f + analysis.distanceCorrelation * 0.5f)
        } else 1.0f
        
        // Advanced offset calculations with quadratic scaling
        val xOffsetAdjustment = -analysis.avgErrorX * (0.3f + analysis.xPositionCorrelation * 0.2f)
        val yOffsetAdjustment = -analysis.avgErrorY * (0.3f + analysis.yPositionCorrelation * 0.2f)
        
        // Dynamic damping based on error patterns
        val dampingAdjustment = when {
            analysis.avgErrorMagnitude > 800f -> 0.15f  // High error - more damping
            analysis.avgErrorMagnitude > 500f -> 0.1f   // Medium error - moderate damping
            analysis.avgErrorMagnitude > 200f -> 0.05f  // Low error - light damping
            else -> 0f  // Very low error - no additional damping
        }
        
        return OptimizedParameters(
            xSensitivityMultiplier = xSensitivityAdjustment.coerceIn(0.3f, 3.0f),
            ySensitivityMultiplier = ySensitivityAdjustment.coerceIn(0.3f, 3.0f),
            distanceScalingMultiplier = distanceAdjustment.coerceIn(0.3f, 3.0f),
            xOffsetAdjustment = xOffsetAdjustment,
            yOffsetAdjustment = yOffsetAdjustment,
            dampingAdjustment = dampingAdjustment,
            // New advanced parameters
            exponentialScaling = analysis.xPositionCorrelation > 0.7f,
            logarithmicScaling = analysis.yPositionCorrelation > 0.5f,
            powerScaling = analysis.distanceCorrelation > 0.6f,
            quadraticOffset = analysis.avgErrorMagnitude > 400f
        )
    }
    
    private fun applyOptimizedParameters(params: OptimizedParameters) {
        // Apply optimized parameters to current settings
        pointerGazeSensitivityX *= params.xSensitivityMultiplier
        pointerGazeSensitivityY *= params.ySensitivityMultiplier
        distanceScalingFactor *= params.distanceScalingMultiplier
        pointerDampingFactor = (pointerDampingFactor + params.dampingAdjustment).coerceIn(0.1f, 0.9f)
        
        // Update calibration data with new offsets
        if (calibrationData.isCalibrated) {
            calibrationData.xOffsetCorrection += params.xOffsetAdjustment
            calibrationData.yOffsetCorrection += params.yOffsetAdjustment
        }
    }
    
    private fun saveFormulaSnapshot(params: OptimizedParameters, analysis: ErrorAnalysis) {
        val snapshot = FormulaSnapshot(
            version = ++currentFormulaVersion,
            timestamp = System.currentTimeMillis(),
            baseParameters = mapOf(
                "gazeSensitivityX" to pointerGazeSensitivityX,
                "gazeSensitivityY" to pointerGazeSensitivityY,
                "headSensitivity" to pointerHeadSensitivity,
                "gyroSensitivity" to pointerGyroSensitivity,
                "dampingFactor" to pointerDampingFactor,
                "distanceScaling" to distanceScalingFactor
            ),
            errorCorrections = mapOf(
                "xErrorCorrection" to calibrationData.xErrorCorrection,
                "yErrorCorrection" to calibrationData.yErrorCorrection,
                "xOffsetCorrection" to calibrationData.xOffsetCorrection,
                "yOffsetCorrection" to calibrationData.yOffsetCorrection
            ),
            calibrationStats = mapOf(
                "clickCount" to calibrationData.clickCount.toFloat(),
                "avgErrorMagnitude" to calibrationData.avgErrorMagnitude,
                "avgYPositionInfluence" to calibrationData.avgYPositionInfluence,
                "avgDistanceRange" to calibrationData.avgDistanceRange
            ),
            aiOptimizations = mapOf(
                "xSensitivityMultiplier" to params.xSensitivityMultiplier,
                "ySensitivityMultiplier" to params.ySensitivityMultiplier,
                "distanceScalingMultiplier" to params.distanceScalingMultiplier,
                "xOffsetAdjustment" to params.xOffsetAdjustment,
                "yOffsetAdjustment" to params.yOffsetAdjustment
            ),
            performance = mapOf(
                "errorReduction" to analysis.errorReduction,
                "distanceCorrelation" to analysis.distanceCorrelation,
                "yPositionCorrelation" to analysis.yPositionCorrelation,
                "xPositionCorrelation" to analysis.xPositionCorrelation
            )
        )
        
        formulaHistory.add(snapshot)
        if (formulaHistory.size > maxHistorySize) {
            formulaHistory.removeAt(0)
        }
        
        // Save to file
        saveFormulaHistory()
    }
    
    private fun saveFormulaHistory() {
        try {
            val file = File(context.filesDir, "formula_history.dat")
            val outputStream = ObjectOutputStream(FileOutputStream(file))
            outputStream.writeObject(formulaHistory)
            outputStream.close()
        } catch (e: Exception) {
            Log.e("OverlayView", "Failed to save formula history", e)
        }
    }
    
    private fun loadFormulaHistory() {
        try {
            val file = File(context.filesDir, "formula_history.dat")
            if (file.exists()) {
                val inputStream = ObjectInputStream(FileInputStream(file))
                @Suppress("UNCHECKED_CAST")
                formulaHistory = inputStream.readObject() as MutableList<FormulaSnapshot>
                inputStream.close()
                currentFormulaVersion = formulaHistory.maxOfOrNull { it.version } ?: 0
            }
        } catch (e: Exception) {
            Log.e("OverlayView", "Failed to load formula history", e)
        }
    }
    
    // Data classes for AI optimization
    data class ErrorAnalysis(
        val avgErrorX: Float,
        val avgErrorY: Float,
        val avgErrorMagnitude: Float,
        val distanceCorrelation: Float,
        val yPositionCorrelation: Float,
        val xPositionCorrelation: Float,
        val errorReduction: Float
    )
    
    data class OptimizedParameters(
        val xSensitivityMultiplier: Float,
        val ySensitivityMultiplier: Float,
        val distanceScalingMultiplier: Float,
        val xOffsetAdjustment: Float,
        val yOffsetAdjustment: Float,
        val dampingAdjustment: Float,
        val exponentialScaling: Boolean = false,
        val logarithmicScaling: Boolean = false,
        val powerScaling: Boolean = false,
        val quadraticOffset: Boolean = false
    )
    
    // Formula data for display
    data class FormulaData(
        val baseParameters: String,
        val errorCorrections: String,
        val aiFormula: String,
        val calibrationData: String,
        val fullFormula: String,
        val formulaHistory: String,
        val jsonFormula: String = ""  // JSON format
    )
    
    // Formula snapshot for history
    data class FormulaSnapshot(
        val version: Int,
        val timestamp: Long,
        val baseParameters: Map<String, Float>,
        val errorCorrections: Map<String, Float>,
        val calibrationStats: Map<String, Float>,
        val aiOptimizations: Map<String, Float>,
        val performance: Map<String, Float>
    ) : Serializable
    
    // Generate JSON formula data
    private fun generateFormulaJson(): JSONObject {
        val formulaJson = FormulaJson(
            version = "1.0",
            timestamp = System.currentTimeMillis(),
            baseParameters = BaseParametersJson(
                gazeSensitivityX = pointerGazeSensitivityX,
                gazeSensitivityY = pointerGazeSensitivityY,
                headSensitivity = pointerHeadSensitivity,
                headTiltYBase = headTiltYBaseSensitivity,
                gyroSensitivity = pointerGyroSensitivity,
                dampingFactor = pointerDampingFactor,
                distanceScaling = distanceScalingFactor
            ),
            errorCorrections = ErrorCorrectionsJson(
                xCorrectionFactor = calibrationData.xErrorCorrection,
                yCorrectionFactor = calibrationData.yErrorCorrection,
                xOffset = calibrationData.xOffsetCorrection,
                yOffset = calibrationData.yOffsetCorrection,
                averageError = calibrationData.avgErrorMagnitude,
                clickCount = calibrationData.clickCount
            ),
            calibrationData = CalibrationDataJson(
                pointsCollected = calibrationPoints.size,
                eyeYRange = Pair(calibrationData.avgEyeYMin, calibrationData.avgEyeYMax),
                sphereSizeRange = Pair(calibrationData.avgSphereSizeMin, calibrationData.avgSphereSizeMax),
                yPositionInfluence = calibrationData.avgYPositionInfluence,
                distanceRange = calibrationData.avgDistanceRange,
                gazeSensitivity = calibrationData.avgGazeSensitivity
            ),
            aiOptimization = AiOptimizationJson(
                completedAt = lastAiOptimizationTime,
                optimizedPoints = calibrationPoints.takeLast(100).size,
                errorReduction = if (formulaHistory.isNotEmpty()) formulaHistory.last().errorReduction else 0f,
                status = if (isAiPrompting) "Processing..." else "Completed"
            ),
            formulaVariations = generateFormulaVariations()
        )
        
        return convertFormulaToJson(formulaJson)
    }
    
    private fun convertFormulaToJson(formula: FormulaJson): JSONObject {
        val json = JSONObject()
        
        // Basic info
        json.put("version", formula.version)
        json.put("timestamp", formula.timestamp)
        
        // Base parameters
        val baseParams = JSONObject()
        baseParams.put("gazeSensitivityX", formula.baseParameters.gazeSensitivityX)
        baseParams.put("gazeSensitivityY", formula.baseParameters.gazeSensitivityY)
        baseParams.put("headSensitivity", formula.baseParameters.headSensitivity)
        baseParams.put("headTiltYBase", formula.baseParameters.headTiltYBase)
        baseParams.put("gyroSensitivity", formula.baseParameters.gyroSensitivity)
        baseParams.put("dampingFactor", formula.baseParameters.dampingFactor)
        baseParams.put("distanceScaling", formula.baseParameters.distanceScaling)
        json.put("baseParameters", baseParams)
        
        // Error corrections
        val errorCorrections = JSONObject()
        errorCorrections.put("xCorrectionFactor", formula.errorCorrections.xCorrectionFactor)
        errorCorrections.put("yCorrectionFactor", formula.errorCorrections.yCorrectionFactor)
        errorCorrections.put("xOffset", formula.errorCorrections.xOffset)
        errorCorrections.put("yOffset", formula.errorCorrections.yOffset)
        errorCorrections.put("averageError", formula.errorCorrections.averageError)
        errorCorrections.put("clickCount", formula.errorCorrections.clickCount)
        json.put("errorCorrections", errorCorrections)
        
        // Calibration data
        val calibData = JSONObject()
        calibData.put("pointsCollected", formula.calibrationData.pointsCollected)
        calibData.put("eyeYMin", formula.calibrationData.eyeYRange.first)
        calibData.put("eyeYMax", formula.calibrationData.eyeYRange.second)
        calibData.put("sphereSizeMin", formula.calibrationData.sphereSizeRange.first)
        calibData.put("sphereSizeMax", formula.calibrationData.sphereSizeRange.second)
        calibData.put("yPositionInfluence", formula.calibrationData.yPositionInfluence)
        calibData.put("distanceRange", formula.calibrationData.distanceRange)
        calibData.put("gazeSensitivity", formula.calibrationData.gazeSensitivity)
        json.put("calibrationData", calibData)
        
        // AI optimization
        val aiOpt = JSONObject()
        aiOpt.put("completedAt", formula.aiOptimization.completedAt)
        aiOpt.put("optimizedPoints", formula.aiOptimization.optimizedPoints)
        aiOpt.put("errorReduction", formula.aiOptimization.errorReduction)
        aiOpt.put("status", formula.aiOptimization.status)
        json.put("aiOptimization", aiOpt)
        
        // Formula variations
        val variations = JSONArray()
        formula.formulaVariations.forEach { variation ->
            val varJson = JSONObject()
            varJson.put("name", variation.name)
            varJson.put("description", variation.description)
            varJson.put("xFormula", variation.xFormula)
            varJson.put("yFormula", variation.yFormula)
            variations.put(varJson)
        }
        json.put("formulaVariations", variations)
        
        return json
    }
    
    private fun generateFormulaVariations(): List<FormulaVariationJson> {
        return listOf(
            FormulaVariationJson(
                name = "Linear Formula",
                description = "Basic linear mapping with sensitivity adjustments",
                xFormula = "targetX = gazeDirectionX * gazeSensitivityX * adaptiveSensitivity",
                yFormula = "targetY = gazeDirectionY * gazeSensitivityY * adaptiveSensitivity + yPositionOffset"
            ),
            FormulaVariationJson(
                name = "Exponential Formula",
                description = "Exponential scaling for strong correlations",
                xFormula = "targetX = gazeDirectionX * gazeSensitivityX * exp(correlationX * 0.5)",
                yFormula = "targetY = gazeDirectionY * gazeSensitivityY * exp(correlationY * 0.5) + yPositionOffset"
            ),
            FormulaVariationJson(
                name = "Logarithmic Formula",
                description = "Logarithmic scaling for moderate correlations",
                xFormula = "targetX = gazeDirectionX * gazeSensitivityX * ln(correlationX + 1)",
                yFormula = "targetY = gazeDirectionY * gazeSensitivityY * ln(correlationY + 1) + yPositionOffset"
            ),
            FormulaVariationJson(
                name = "Quadratic Offset Formula",
                description = "Quadratic error correction for high-error scenarios",
                xFormula = "targetX = gazeDirectionX * gazeSensitivityX + (errorX^2 * 0.001)",
                yFormula = "targetY = gazeDirectionY * gazeSensitivityY + (errorY^2 * 0.001) + yPositionOffset"
            ),
            FormulaVariationJson(
                name = "Compound Multiplicative Formula",
                description = "Complex multiplicative scaling with multiple factors",
                xFormula = "targetX = gazeDirectionX * gazeSensitivityX * distanceRange * adaptiveSensitivity * (1 + correlationX * 0.3)",
                yFormula = "targetY = gazeDirectionY * gazeSensitivityY * distanceRange * adaptiveSensitivity * (1 + correlationY * 0.3) + yPositionOffset"
            )
        )
    }
    
    // Parse JSON formula and apply parameters
    private fun parseAndApplyFormulaJson(jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)
            
            // Parse base parameters
            val baseParams = json.getJSONObject("baseParameters")
            pointerGazeSensitivityX = baseParams.getDouble("gazeSensitivityX").toFloat()
            pointerGazeSensitivityY = baseParams.getDouble("gazeSensitivityY").toFloat()
            pointerHeadSensitivity = baseParams.getDouble("headSensitivity").toFloat()
            headTiltYBaseSensitivity = baseParams.getDouble("headTiltYBase").toFloat()
            pointerGyroSensitivity = baseParams.getDouble("gyroSensitivity").toFloat()
            pointerDampingFactor = baseParams.getDouble("dampingFactor").toFloat()
            distanceScalingFactor = baseParams.getDouble("distanceScaling").toFloat()
            
            // Parse error corrections
            val errorCorrections = json.getJSONObject("errorCorrections")
            calibrationData.xErrorCorrection = errorCorrections.getDouble("xCorrectionFactor").toFloat()
            calibrationData.yErrorCorrection = errorCorrections.getDouble("yCorrectionFactor").toFloat()
            calibrationData.xOffsetCorrection = errorCorrections.getDouble("xOffset").toFloat()
            calibrationData.yOffsetCorrection = errorCorrections.getDouble("yOffset").toFloat()
            calibrationData.avgErrorMagnitude = errorCorrections.getDouble("averageError").toFloat()
            calibrationData.clickCount = errorCorrections.getInt("clickCount")
            
            // Parse calibration data
            val calibData = json.getJSONObject("calibrationData")
            calibrationData.avgEyeYMin = calibData.getDouble("eyeYMin").toFloat()
            calibrationData.avgEyeYMax = calibData.getDouble("eyeYMax").toFloat()
            calibrationData.avgSphereSizeMin = calibData.getDouble("sphereSizeMin").toFloat()
            calibrationData.avgSphereSizeMax = calibData.getDouble("sphereSizeMax").toFloat()
            calibrationData.avgYPositionInfluence = calibData.getDouble("yPositionInfluence").toFloat()
            calibrationData.avgDistanceRange = calibData.getDouble("distanceRange").toFloat()
            calibrationData.avgGazeSensitivity = calibData.getDouble("gazeSensitivity").toFloat()
            
            formulaUpdateTime = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e("FormulaJson", "Failed to parse formula JSON: ${e.message}")
            false
        }
    }
    
    // Get current formula as JSON string
    fun getFormulaJsonString(): String {
        val json = generateFormulaJson()
        return json.toString(2) // Pretty print with 2-space indentation
    }
    
    // Advanced formula generation with multiple mathematical operators
    private fun generateAdvancedFormula(analysis: ErrorAnalysis): String {
        val baseX = pointerGazeSensitivityX
        val baseY = pointerGazeSensitivityY
        val baseDistance = distanceScalingFactor
        
        // Generate different formula variations based on error patterns
        val formulas = mutableListOf<String>()
        
        // Formula 1: Exponential scaling for high correlations
        if (analysis.xPositionCorrelation > 0.7f || analysis.yPositionCorrelation > 0.7f) {
            val expFormula = """
                Exponential Formula:
                X = baseX * e^(correlationX * 0.5) * gazeDirectionX * distanceRange
                Y = baseY * e^(correlationY * 0.5) * gazeDirectionY * distanceRange
            """.trimIndent()
            formulas.add(expFormula)
        }
        
        // Formula 2: Logarithmic scaling for moderate correlations
        if (analysis.xPositionCorrelation > 0.5f || analysis.yPositionCorrelation > 0.5f) {
            val logFormula = """
                Logarithmic Formula:
                X = baseX * ln(correlationX + 1) * gazeDirectionX * distanceRange
                Y = baseY * ln(correlationY + 1) * gazeDirectionY * distanceRange
            """.trimIndent()
            formulas.add(logFormula)
        }
        
        // Formula 3: Power scaling for distance effects
        if (analysis.distanceCorrelation > 0.6f) {
            val powerFormula = """
                Power Formula:
                X = baseX * (distanceRange^correlationDistance) * gazeDirectionX
                Y = baseY * (distanceRange^correlationDistance) * gazeDirectionY
            """.trimIndent()
            formulas.add(powerFormula)
        }
        
        // Formula 4: Quadratic offset for high errors
        if (analysis.avgErrorMagnitude > 400f) {
            val quadFormula = """
                Quadratic Offset Formula:
                X = baseX * gazeDirectionX + (errorX^2 * 0.001)
                Y = baseY * gazeDirectionY + (errorY^2 * 0.001)
            """.trimIndent()
            formulas.add(quadFormula)
        }
        
        // Formula 5: Multiplicative compound scaling
        val compoundFormula = """
            Compound Multiplicative Formula:
            X = baseX * gazeDirectionX * distanceRange * adaptiveSensitivity * 
                (1 + correlationX * 0.3) * (1 + errorX * 0.0001)
            Y = baseY * gazeDirectionY * distanceRange * adaptiveSensitivity * 
                (1 + correlationY * 0.3) * (1 + errorY * 0.0001)
        """.trimIndent()
        formulas.add(compoundFormula)
        
        // Formula 6: Ratio-based proportional scaling
        val ratioFormula = """
            Ratio-Based Formula:
            X = baseX * (gazeDirectionX / (1 + abs(errorX) * 0.001)) * distanceRange
            Y = baseY * (gazeDirectionY / (1 + abs(errorY) * 0.001)) * distanceRange
        """.trimIndent()
        formulas.add(ratioFormula)
        
        return formulas.joinToString("\n\n")
    }
    
    // Enhanced AI prompt for formula generation with operator considerations
    private fun generateEnhancedFormulaPrompt(): String {
        return """
            ENHANCED PUPIL CURSOR CONTROL FORMULA GENERATION
            ================================================
            
            Current System Parameters:
            - Gaze Sensitivity X: $pointerGazeSensitivityX
            - Gaze Sensitivity Y: $pointerGazeSensitivityY
            - Head Sensitivity: $pointerHeadSensitivity
            - Gyro Sensitivity: $pointerGyroSensitivity
            - Damping Factor: $pointerDampingFactor
            - Distance Scaling: $distanceScalingFactor
            
            Error Correction Factors:
            - X Correction: ${calibrationData.xErrorCorrection}
            - Y Correction: ${calibrationData.yErrorCorrection}
            - X Offset: ${calibrationData.xOffsetCorrection}
            - Y Offset: ${calibrationData.yOffsetCorrection}
            - Average Error: ${calibrationData.avgErrorMagnitude}px
            
            FORMULA GENERATION STRATEGY:
            
            1. OPERATOR SELECTION CRITERIA:
               - Use EXPONENTIAL (e^x) for strong correlations (>0.7)
               - Use LOGARITHMIC (ln(x+1)) for moderate correlations (0.3-0.7)
               - Use POWER (x^y) for distance-based non-linear effects
               - Use QUADRATIC (x^2) for high-error scenarios (>400px)
               - Use MULTIPLICATIVE (*) for compound scaling
               - Use ADDITIVE (+) for linear offsets
               - Use DIVISIVE (/) for proportional relationships
            
            2. MULTIPLIER EFFECT CONSIDERATIONS:
               - Correlation strength determines exponential base
               - Error magnitude influences power scaling exponent
               - Distance range affects logarithmic scaling factor
               - Position influence modifies additive offset magnitude
               - Velocity patterns adjust multiplicative coefficients
            
            3. FORMULA EVOLUTION RULES:
               - Test operator combinations systematically
               - Measure performance impact of each operator change
               - Evolve formula structure based on error reduction
               - Consider operator interactions and dependencies
               - Optimize for user-specific calibration patterns
            
            4. ADVANCED MATHEMATICAL OPERATORS:
               - Trigonometric: sin(), cos(), tan() for periodic effects
               - Hyperbolic: sinh(), cosh(), tanh() for saturation effects
               - Root functions: sqrt(), cbrt() for diminishing returns
               - Absolute: abs() for magnitude-based scaling
               - Sign: sign() for direction-based adjustments
               - Clamp: clamp() for bounded ranges
            
            5. COMPOUND FORMULA STRUCTURES:
               - Nested operators: f(g(h(x))) for complex relationships
               - Conditional operators: x > threshold ? f(x) : g(x)
               - Weighted combinations: w1*f1(x) + w2*f2(x) + w3*f3(x)
               - Adaptive scaling: base * (1 + correlation * factor)
               - Error feedback: current + (error * learning_rate)
            
            Generate formulas that:
            - Minimize average error magnitude
            - Maximize correlation with user behavior
            - Adapt to changing calibration patterns
            - Provide smooth cursor movement
            - Handle edge cases gracefully
            - Scale appropriately with distance and position
        """.trimIndent()
    }

    fun getFormulaData(): FormulaData {
        // Generate JSON formula
        val jsonFormula = getFormulaJsonString()
        
        // Also generate the old text format for backward compatibility
        val baseParams = """
            Base Parameters:
            - Gaze Sensitivity X: $pointerGazeSensitivityX
            - Gaze Sensitivity Y: $pointerGazeSensitivityY
            - Head Sensitivity: $pointerHeadSensitivity
            - Head Tilt Y Base: $headTiltYBaseSensitivity
            - Gyro Sensitivity: $pointerGyroSensitivity
            - Damping Factor: $pointerDampingFactor
            - Distance Scaling: $distanceScalingFactor
        """.trimIndent()
        val baseParams = """
            Base Parameters:
            - Gaze Sensitivity X: $pointerGazeSensitivityX
            - Gaze Sensitivity Y: $pointerGazeSensitivityY
            - Head Sensitivity: $pointerHeadSensitivity
            - Head Tilt Y Base: $headTiltYBaseSensitivity
            - Gyro Sensitivity: $pointerGyroSensitivity
            - Damping Factor: $pointerDampingFactor
            - Distance Scaling: $distanceScalingFactor
        """.trimIndent()
        
        val errorCorrections = if (calibrationData.isCalibrated) {
            """
            Error Corrections:
            - X Correction Factor: ${calibrationData.xErrorCorrection}
            - Y Correction Factor: ${calibrationData.yErrorCorrection}
            - X Offset: ${calibrationData.xOffsetCorrection}
            - Y Offset: ${calibrationData.yOffsetCorrection}
            - Average Error: ${calibrationData.avgErrorMagnitude}px
            - Click Count: ${calibrationData.clickCount}
            """.trimIndent()
        } else {
            "No error corrections applied"
        }
        
        val aiFormula = if (aiOptimizedFormula.isNotEmpty()) {
            aiOptimizedFormula
        } else {
            "No AI optimization applied"
        }
        
        val enhancedPrompt = generateEnhancedFormulaPrompt()
        
        val calibData = if (calibrationData.isCalibrated) {
            """
            Calibration Data:
            - Points Collected: ${calibrationPoints.size}
            - Eye Y Range: ${calibrationData.avgEyeYMin} - ${calibrationData.avgEyeYMax}
            - Sphere Size Range: ${calibrationData.avgSphereSizeMin} - ${calibrationData.avgSphereSizeMax}
            - Y Position Influence: ${calibrationData.avgYPositionInfluence}
            - Distance Range: ${calibrationData.avgDistanceRange}
            - Gaze Sensitivity: ${calibrationData.avgGazeSensitivity}
            """.trimIndent()
        } else {
            "No calibration data available"
        }
        
        val formulaHistoryText = if (formulaHistory.isNotEmpty()) {
            val historyEntries = formulaHistory.takeLast(5).joinToString("\n") { snapshot ->
                "Version ${snapshot.version} (${Date(snapshot.timestamp)}): " +
                "Error Reduction: ${"%.1f".format(snapshot.performance["errorReduction"] ?: 0f)}%, " +
                "Distance Corr: ${"%.2f".format(snapshot.performance["distanceCorrelation"] ?: 0f)}"
            }
            "Formula History (Last ${formulaHistory.size} versions):\n$historyEntries"
        } else {
            "No formula history available"
        }
        
        val fullFormula = """
            PUPIL CURSOR CONTROL FORMULA
            ===========================
            
            $baseParams
            
            $errorCorrections
            
            AI Optimization:
            $aiFormula
            
            $calibData
            
            Enhanced Formula Generation:
            $enhancedPrompt
            
            $formulaHistoryText
            
            Generated: ${System.currentTimeMillis()}
        """.trimIndent()
        
        return FormulaData(baseParams, errorCorrections, aiFormula, calibData, fullFormula, formulaHistoryText, jsonFormula)
    }
}