package com.qali.pupil

import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import java.util.LinkedList
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs), SensorEventListener {

    // --- Control and Tuning Variables ---

    var pointerGazeSensitivityX = 16.0f
    var pointerGazeSensitivityY = 18.0f
    var pointerHeadSensitivity = 0.6f
    var headTiltYBaseSensitivity = 2.5f
    var pointerGyroSensitivity = 200f
    var pointerDampingFactor = 0.4f

    // Eye Position and Distance Scaling
    var leftEyeX = 0f
    var leftEyeY = -17f
    var leftEyeZ = 0f
    var rightEyeX = 0f
    var rightEyeY = -17f
    var rightEyeZ = 0f
    private var distanceScalingFactor = 1.0f
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
    private val typicalEyeMinYRatio = 0.4f  // Bottom 60% starts here
    private val typicalEyeMaxYRatio = 0.9f  // Leave margin at bottom

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
        val typicalEyeMinY = screenHeight * typicalEyeMinYRatio
        val typicalEyeMaxY = screenHeight * typicalEyeMaxYRatio
        val typicalEyeRange = typicalEyeMaxY - typicalEyeMinY
        
        return ((eyeY - typicalEyeMinY) / typicalEyeRange).coerceIn(0f, 1f)
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
        
        // Map to -1 to +1 range for cursor influence
        val yInfluence = (normalizedEyeY - 0.5f) * 2f
        
        // Apply non-linear scaling for better control
        val scaledInfluence = yInfluence * (1f + abs(yInfluence) * 0.3f)
        
        return scaledInfluence.coerceIn(-1f, 1f)
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
        val baseSensitivity = 1.0f
        
        // Movement factor based on gaze velocity
        val movementFactor = when {
            gazeVelocity < 2f -> 0.7f      // Reduce sensitivity for drift
            gazeVelocity < 8f -> 1.0f      // Normal sensitivity
            gazeVelocity < 15f -> 1.3f     // Increase for deliberate movement
            else -> 1.5f                   // High sensitivity for rapid movement
        }
        
        // Position factor (eyes in bottom 60% get higher sensitivity)
        val normalizedEyeY = calculateNormalizedEyePosition(eyeY, screenHeight)
        val positionFactor = 0.8f + (normalizedEyeY * 0.4f)  // Range: 0.8-1.2
        
        // Distance factor (closer = more sensitive)
        val distanceRange = calculateDistanceRange(sphereSize)
        val distanceFactor = 2f - distanceRange  // Range: -1 to 1 (inverse relationship)
        
        return baseSensitivity * movementFactor * positionFactor * (1f + distanceFactor * 0.3f)
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
            
            // 8. Map gaze direction to screen coordinates with enhanced scaling
            val gazeScreenOffsetX = gazeDirectionX * pointerGazeSensitivityX * distanceScalingFactor * adaptiveSensitivity * distanceRange
            val gazeScreenOffsetY = gazeDirectionY * pointerGazeSensitivityY * distanceScalingFactor * adaptiveSensitivity * distanceRange
            
            // 9. Apply eye Y position influence with improved calculation
            val yPositionInfluence = calculateYPositionInfluence(averageEyeY, height.toFloat())
            val yPositionOffset = yPositionInfluence * 30f // Adjusted multiplier for better control
            
            // 10. Calculate gyro influence for stabilization
            val gyroInfluenceX = -gyroVelocityX * pointerGyroSensitivity
            val gyroInfluenceY = -gyroVelocityY * pointerGyroSensitivity

            // 11. Calculate the final target position
            val targetX = headInfluencedCenterX + gazeScreenOffsetX + gyroInfluenceX
            val targetY = headInfluencedCenterY + gazeScreenOffsetY + gyroInfluenceY + yPositionOffset
            
            // 5. Apply Damping for Smooth Motion.
            val previousPosition = lastPointerPosition ?: Pair(targetX, targetY)
            val finalX = previousPosition.first + (targetX - previousPosition.first) * pointerDampingFactor
            val finalY = previousPosition.second + (targetY - previousPosition.second) * pointerDampingFactor

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
    }
}