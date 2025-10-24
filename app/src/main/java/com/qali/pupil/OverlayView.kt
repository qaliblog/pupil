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
    
    // Eye Sphere Stretching for Better Gaze Lines
    var eyeStretchEnabled = true
    var eyeStretchDownward = 0.15f      // Stretch eyes downward
    var eyeStretchOutward = 0.1f        // Stretch eyes outward (left/right)
    
    // Cursor Position Range Effects
    var cursorRangeEnabled = true
    var cursorXRange = 0.3f             // X position range effect on cursor
    var cursorYRange = 0.2f             // Y position range effect on cursor
    var cursorDistanceRange = 0.4f      // Distance range effect on cursor

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
    private data class EyeSphere(val centerX: Float, val centerY: Float, val radius: Float, val scaledRadius: Float = radius * 2f, val zScale: Float = 1f)
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
    
    fun updateEyeStretch(
        enabled: Boolean? = null,
        downward: Float? = null,
        outward: Float? = null
    ) {
        enabled?.let { eyeStretchEnabled = it }
        downward?.let { eyeStretchDownward = it }
        outward?.let { eyeStretchOutward = it }
        invalidate()
    }
    
    fun updateCursorRange(
        enabled: Boolean? = null,
        xRange: Float? = null,
        yRange: Float? = null,
        distanceRange: Float? = null
    ) {
        enabled?.let { cursorRangeEnabled = it }
        xRange?.let { cursorXRange = it }
        yRange?.let { cursorYRange = it }
        distanceRange?.let { cursorDistanceRange = it }
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

        // Apply eye stretching for better gaze line accuracy
        var stretchX = 1f
        var stretchY = 1f
        
        if (eyeStretchEnabled) {
            // Stretch downward (Y) and outward (X) slightly
            stretchY += eyeStretchDownward
            stretchX += eyeStretchOutward
            
            // Add slight outward stretch based on eye position
            if (isRightEye) {
                stretchX += eyeStretchOutward * 0.5f  // Right eye stretches more outward
            } else {
                stretchX += eyeStretchOutward * 0.3f  // Left eye stretches less outward
            }
        }

        return EyeSphere(centerX, centerY, radius, radius * 2f * stretchX, radius * 2f * stretchY)
    }

    private fun calculateGazeLine(sphereCenter: Pair<Float, Float>, pupilPoint: Pair<Float, Float>, extensionFactor: Float = 2f): GazeLine {
        val directionX = pupilPoint.first - sphereCenter.first
        val directionY = pupilPoint.second - sphereCenter.second
        val endX = pupilPoint.first + directionX * extensionFactor
        val endY = pupilPoint.second + directionY * extensionFactor
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

            // --- CORRECTED POINTER LOCATION LOGIC ---

            // 1. Calculate the actual gaze direction vector from the average gaze point
            // This represents where the user is actually looking in 3D space
            val gazeDirectionX = averageGazeX - ((leftSphere.centerX + rightSphere.centerX) / 2f)
            val gazeDirectionY = averageGazeY - ((leftSphere.centerY + rightSphere.centerY) / 2f)
            
            // 2. Calculate the screen center as the base reference point
            val screenCenterX = width / 2f + staticX
            val screenCenterY = height / 2f + staticY
            
            // 3. Apply head direction influence to the screen center
            // This creates a dynamic reference point that follows head movement
            val headInfluencedCenterX = screenCenterX + (headDirectionX * pointerHeadSensitivity)
            val headInfluencedCenterY = screenCenterY - (headDirectionY * headTiltYBaseSensitivity)
            
            // 4. Map gaze direction to screen coordinates with range effects
            // Scale the gaze direction by sensitivity and distance factors
            var gazeScreenOffsetX = gazeDirectionX * pointerGazeSensitivityX * distanceScalingFactor
            var gazeScreenOffsetY = gazeDirectionY * pointerGazeSensitivityY * distanceScalingFactor
            
            // Apply cursor range effects for better accuracy
            if (cursorRangeEnabled) {
                // X position range effect - affects horizontal cursor movement
                val eyeCenterX = (leftSphere.centerX + rightSphere.centerX) / 2f
                val screenCenterX = width / 2f
                val xPositionFactor = (eyeCenterX - screenCenterX) / screenCenterX  // -1 to 1
                gazeScreenOffsetX += xPositionFactor * cursorXRange * 100f
                
                // Y position range effect - affects vertical cursor movement  
                val eyeCenterY = (leftSphere.centerY + rightSphere.centerY) / 2f
                val screenCenterY = height / 2f
                val yPositionFactor = (eyeCenterY - screenCenterY) / screenCenterY  // -1 to 1
                gazeScreenOffsetY += yPositionFactor * cursorYRange * 100f
                
                // Distance range effect - affects both X and Y based on distance from camera
                val distanceFactor = (distanceScalingFactor - 1f).coerceIn(-1f, 1f)  // Normalize distance scaling
                gazeScreenOffsetX += distanceFactor * cursorDistanceRange * 50f
                gazeScreenOffsetY += distanceFactor * cursorDistanceRange * 30f
            }
            
            // 5. Calculate gyro influence for stabilization
            val gyroInfluenceX = -gyroVelocityX * pointerGyroSensitivity
            val gyroInfluenceY = -gyroVelocityY * pointerGyroSensitivity

            // 6. Calculate the final target position
            // Start from head-influenced center, add gaze offset, and apply gyro stabilization
            val targetX = headInfluencedCenterX + gazeScreenOffsetX + gyroInfluenceX
            val targetY = headInfluencedCenterY + gazeScreenOffsetY + gyroInfluenceY
            
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
        val stretchText = "Stretch: ${if (eyeStretchEnabled) "ON" else "OFF"} D${"%.2f".format(eyeStretchDownward)} O${"%.2f".format(eyeStretchOutward)}"
        val rangeText = "Range: ${if (cursorRangeEnabled) "ON" else "OFF"} X${"%.2f".format(cursorXRange)} Y${"%.2f".format(cursorYRange)} D${"%.2f".format(cursorDistanceRange)}"
        
        canvas.drawText(fpsText, 20f, 170f, textPaint)
        canvas.drawText(stretchText, 20f, 210f, textPaint)
        canvas.drawText(rangeText, 20f, 250f, textPaint)
    }
}