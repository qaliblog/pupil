package com.qali.pupil

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.sqrt
import kotlin.math.pow
import android.graphics.Paint.Align
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos // Import cos
import kotlin.math.sin
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.LinkedList
import android.util.Log

class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs), SensorEventListener {

var pscpChange = 0f
var yDiffChange = 0f
var targetY = 0f
var adjustedMoveY = 0f
var gyroYchange = 0f
// Position control variables for each eye
var leftEyeX = 0f
var leftEyeY = -17f
var leftEyeZ = 0f

var rightEyeX = 0f
var rightEyeY = -17f
var rightEyeZ = 0f

// New control variables for the average gaze dot position
var moveX = 1.7f
var moveY = 2.0f
var staticX = 0f
var staticY = 0f
var distanceScalingFactor = 1f
var distanceEffectMoveX = 1400f
var distanceEffectMoveY = 1800f
var averageFaceWeightYOffset = 70f
var moveXHeadDirectionScale = 1f
var moveYHeadDirectionScale = 1f


// Gyroscope control variables
private var gyroVelocityX = 0.0f
private var gyroVelocityY = 0.0f
private var gyroVelocityZ = 0.0f
private var gyroSensitivity = 0.1f

// New scale variables
private var gyroScaleX = 0.0f  // Default scaling to 1 (no change)
private var gyroScaleY = 0.0f
private var gyroScaleZ = 0.0f

// Variables for tracking previous values of gyroInfluence
private var prevGyroVelocityX = 0.0f
private var prevGyroVelocityY = 0.0f
private var prevGyroVelocityZ = 0.0f

private var lastGyroTimestamp: Long = 0

private var lastOpenEyeUpdateTime: Long = 0

// --- New variables for eye height averaging ---
private val leftEyeHeightHistory = LinkedList<Pair<Long, Float>>()
private val rightEyeHeightHistory = LinkedList<Pair<Long, Float>>()
private val heightHistoryDuration = 1000L // 1 second window
private var leftEyeOpenHeight: Float = 0f
private var rightEyeOpenHeight: Float = 0f


// Variables for the average dot
private val averageDotPositions = LinkedList<Pair<Float, Float>>()
private val maxAverageDotSize = 10 // Number of frames to average over

private var landmarks: List<Pair<Float, Float>> = emptyList()

private val whitePaint = Paint().apply {
    color = Color.WHITE
    style = Paint.Style.FILL
    isAntiAlias = true
}

private val purplePaint = Paint().apply {
    color = Color.MAGENTA
    style = Paint.Style.FILL
    isAntiAlias = true
}

private val bluePaint = Paint().apply {
    color = Color.BLUE
    style = Paint.Style.FILL
    isAntiAlias = true
}

private val greenPaint = Paint().apply {
    color = Color.GREEN
    style = Paint.Style.FILL
    isAntiAlias = true
}

private val gazePaint = Paint().apply {
    color = Color.RED
    style = Paint.Style.STROKE
    strokeWidth = 3f
    isAntiAlias = true
}

private val orangePaint = Paint().apply {
    color = Color.rgb(255, 165, 0)
    style = Paint.Style.STROKE
    strokeWidth = 5f
    isAntiAlias = true
}

private val blinkDotPaint = Paint().apply {
    color = Color.BLUE
    style = Paint.Style.FILL
    isAntiAlias = true
}

private val textPaint = Paint().apply {
    color = Color.WHITE
    textSize = 30f // Increased text size
    style = Paint.Style.FILL
    isAntiAlias = true
    textAlign = Align.LEFT
}

// Right eye indices (blue)
private val sphereRightEyeIndices = listOf(
    359, 467, 260, 259, 257, 258, 286, 414, 463, 341, 256, 252, 253, 254, 339, 255
)

// Left eye indices (green)
private val sphereLeftEyeIndices = listOf(
    130, 247, 30, 29, 27, 28, 56, 190, 243, 112, 26, 22, 23, 24, 110, 25
)

// Right eye indices (blue)
private val rightEyeIndices = listOf(
    362,398, 384, 385, 386, 387, 388,
    382, 381, 380, 374, 373, 390, 466, 263, 249
)

// Left eye indices (green)
private val leftEyeIndices = listOf(
    246, 161, 160, 159, 158, 157,
    7, 163, 144, 145, 153, 154,
    33, 155,173, 133
)

// Pupil indices
private val rightPupilIndex = 473
private val leftPupilIndex = 468

// Face landmarks indices
private val faceLandmarkIndices = (0..477).toList()

// Indices for head direction
private val headDirectionIndices = listOf(127, 162, 21, 54, 103, 67, 109, 10, 338, 297, 332, 284, 251, 389, 356)

//Head direction target indice
private val headDirectionTargetIndex = 9

private data class EyeSphere(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val scaledRadius: Float = radius * 2f,
    val zScale: Float = 1f,
    val volume: Float = 0f
)

private data class GazeLine(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)

// --- Sensor initialization ---
private val sensorManager: SensorManager =
    context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
private val gyroscopeSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

init {
    // Start listening to the gyroscope sensor in the constructor
    startGyroscopeListening()
}

private fun startGyroscopeListening() {
    gyroscopeSensor?.let {
        sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
    }
}

// --- Sensor listener implementation ---
override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    //  Not needed for this example
}

override fun onSensorChanged(event: SensorEvent?) {
    if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
        val gyroX = event.values[0]
        val gyroY = event.values[1]
        val gyroZ = event.values[2] // Get Z-axis rotation

        val currentTime = System.currentTimeMillis()
        if (lastGyroTimestamp != 0L){
            val deltaTime = (currentTime - lastGyroTimestamp) / 1000f // Time in seconds

            //Calculate gyro velocity based on angular speed (and delta time for scaling).
            gyroVelocityX = (gyroY * gyroSensitivity  * deltaTime)
            gyroVelocityY = (-gyroX * gyroSensitivity * deltaTime)
            gyroYchange += -gyroX * gyroSensitivity * deltaTime
            gyroVelocityZ = (gyroZ * gyroSensitivity * deltaTime) // Get Z-axis rotation

            // Limit gyro influence so it doesnt go crazy
            gyroVelocityX = gyroVelocityX.coerceIn(-3f, 3f)
            gyroVelocityY = gyroVelocityY.coerceIn(-3f, 3f)
            gyroVelocityZ = gyroVelocityZ.coerceIn(-3f, 3f)

            invalidate()
        }

        lastGyroTimestamp = currentTime
    }
}

override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    stopGyroscopeListening()
}

private fun stopGyroscopeListening() {
    sensorManager.unregisterListener(this)
}


// Function to calculate sphere volume based on radius
private fun calculateSphereVolume(radius: Float): Float {
    return (4f/3f) * PI.toFloat() * radius.pow(3)
}

// Function to apply 3D position adjustments
private fun adjustPosition(point: Pair<Float, Float>, xOffset: Float, yOffset: Float, zOffset: Float): Pair<Float, Float> {
    // Z affects the scale/position of x and y
    val zScale = 1f + (zOffset / 1000f)  // Adjust divisor to control Z sensitivity
    return Pair(
        point.first + xOffset * zScale,
        point.second + yOffset * zScale
    )
}

private fun calculateGazeLine(
    sphereCenter: Pair<Float, Float>,
    pupilPoint: Pair<Float, Float>,
    extensionFactor: Float = 2f
): GazeLine {
    val directionX = pupilPoint.first - sphereCenter.first
    val directionY = pupilPoint.second - sphereCenter.second

    val endX = pupilPoint.first + directionX * extensionFactor
    val endY = pupilPoint.second + directionY * extensionFactor

    return GazeLine(
        sphereCenter.first,
        sphereCenter.second,
        endX,
        endY
    )
}


private fun calculateEyeSphere(eyePoints: List<Pair<Float, Float>>, xOffset: Float, yOffset: Float, zOffset: Float): EyeSphere {
    if (eyePoints.isEmpty()) return EyeSphere(0f, 0f, 0f)

    // Adjust all points based on position controls
    val adjustedPoints = eyePoints.map { point ->
        adjustPosition(point, xOffset, yOffset, zOffset)
    }

    val centerX = adjustedPoints.map { it.first }.average().toFloat()
    val centerY = adjustedPoints.map { it.second }.average().toFloat()

    val radius = adjustedPoints.map { point ->
        sqrt((point.first - centerX).pow(2) + (point.second - centerY).pow(2))
    }.average().toFloat()

    // Calculate sphere volume
    val volume = calculateSphereVolume(radius)

    // Calculate Z scale factor
    val zScale = 1f + (zOffset / 1000f)  // Adjust divisor to control Z sensitivity


    return EyeSphere(centerX, centerY, radius, radius * 2f, zScale, volume)
}

// Function to calculate eye area based on a set of points
private fun calculateEyeArea(eyePoints: List<Pair<Float, Float>>): Float {
    if (eyePoints.size < 3) return 0f // Need at least 3 points for a simple area approximation

    // Implementation of shoelace formula
    var area = 0.0f
    val n = eyePoints.size
    for (i in 0 until n) {
        val p1 = eyePoints[i]
        val p2 = eyePoints[(i + 1) % n] // Wrap around to the first point at the end
        area += (p1.first * p2.second - p2.first * p1.second)
    }
    return abs(area / 2.0f)
}

private fun calculateWeightedAveragePoint(facePoints: List<Pair<Float, Float>>, yOffset: Float): Pair<Float, Float> {
    if(facePoints.isEmpty()) return Pair(0f,0f)

    var sumX = 0f
    var sumY = 0f
    for (point in facePoints) {
        sumX += point.first
        sumY += point.second
    }
    return Pair(sumX / facePoints.size, (sumY / facePoints.size) + yOffset)
}

// Function to calculate the head direction vector
private fun calculateHeadDirection(start: Pair<Float, Float>, end: Pair<Float, Float>): Triple<Float, Float, Float> {
    val dx = end.first - start.first
    val dy = end.second - start.second
    val magnitude = sqrt(dx * dx + dy * dy)

    val angle = atan2(dy, dx) //Use atan2 here to get the correct angle

    val normalizedMagnitudeX = cos(angle) //Use cosine to generate the x axis vector
    val normalizedMagnitudeY = sin(angle) //Use sine to generate the y axis vector

    // Return direction (normalized dx, dy) and magnitude
    return Triple(normalizedMagnitudeX, normalizedMagnitudeY, magnitude)
}


private fun draw3DSphere(canvas: Canvas, sphere: EyeSphere, isRightEye: Boolean) {
    if (sphere.radius <= 0) return

    // Adjust radius based on Z position
    val adjustedRadius = sphere.scaledRadius * sphere.zScale

    val colors = if (isRightEye) {
        intArrayOf(
            Color.argb((200 * sphere.zScale).toInt(), 100, 150, 255),
            Color.argb((180 * sphere.zScale).toInt(), 50, 100, 255),
            Color.argb((160 * sphere.zScale).toInt(), 0, 50, 255)
        )
    } else {
        intArrayOf(
            Color.argb((200 * sphere.zScale).toInt(), 100, 255, 100),
            Color.argb((180 * sphere.zScale).toInt(), 50, 255, 50),
            Color.argb((160 * sphere.zScale).toInt(), 0, 200, 0)
        )
    }

    val positions = floatArrayOf(0f, 0.7f, 1f)

    val lightOffsetX = -adjustedRadius * 0.2f
    val lightOffsetY = -adjustedRadius * 0.2f

    val gradient = RadialGradient(
        sphere.centerX + lightOffsetX,
        sphere.centerY + lightOffsetY,
        adjustedRadius,
        colors,
        positions,
        Shader.TileMode.CLAMP
    )

    val spherePaint = Paint().apply {
        isAntiAlias = true
        shader = gradient
        style = Paint.Style.FILL
    }

    canvas.drawCircle(sphere.centerX, sphere.centerY, adjustedRadius, spherePaint)

    val highlightPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        shader = RadialGradient(
            sphere.centerX - adjustedRadius * 0.3f,
            sphere.centerY - adjustedRadius * 0.3f,
            adjustedRadius * 0.4f,
            Color.argb((100 * sphere.zScale).toInt(), 255, 255, 255),
            Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawCircle(sphere.centerX, sphere.centerY, adjustedRadius, highlightPaint)

    val edgePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f * sphere.zScale
        color = if (isRightEye)
            Color.argb((80 * sphere.zScale).toInt(), 200, 220, 255)
        else
            Color.argb((80 * sphere.zScale).toInt(), 200, 255, 200)
    }
    canvas.drawCircle(sphere.centerX, sphere.centerY, adjustedRadius, edgePaint)
}

// Function to update position values and redraw
fun updateEyePosition(
    leftX: Float? = null,
    leftY: Float? = null,
    leftZ: Float? = null,
    rightX: Float? = null,
    rightY: Float? = null,
    rightZ: Float? = null
) {
    leftX?.let { leftEyeX = it }
    leftY?.let { leftEyeY = it }
    leftZ?.let { leftEyeZ = it }
    rightX?.let { rightEyeX = it }
    rightY?.let { rightEyeY = it }
    rightZ?.let { rightEyeZ = it }
    invalidate()
}

// Function to update the control variable of brown dot
fun updateGazeDotControl(
    moveX: Float? = null,
    moveY: Float? = null,
    staticX: Float? = null,
    staticY: Float? = null,
    distanceScalingFactor: Float? = null,
    distanceEffectMoveX: Float? = null,
    distanceEffectMoveY: Float? = null,
    averageFaceWeightYOffset: Float? = null,
    moveXHeadDirectionScale: Float? = null,
    moveYHeadDirectionScale: Float? = null,
    gyroScaleX: Float? = null,
    gyroScaleY: Float? = null,
    gyroScaleZ: Float? = null
) {
    moveX?.let { this.moveX = it }
    moveY?.let { this.moveY = it }
    staticX?.let { this.staticX = it }
    staticY?.let { this.staticY = it }
    distanceScalingFactor?.let { this.distanceScalingFactor = it }
    distanceEffectMoveX?.let { this.distanceEffectMoveX = it }
    distanceEffectMoveY?.let { this.distanceEffectMoveY = it }
    averageFaceWeightYOffset?.let { this.averageFaceWeightYOffset = it }
    moveXHeadDirectionScale?.let { this.moveXHeadDirectionScale = it }
    moveYHeadDirectionScale?.let { this.moveYHeadDirectionScale = it }
    gyroScaleX?.let { this.gyroScaleX = it }
    gyroScaleY?.let { this.gyroScaleY = it }
    gyroScaleZ?.let { this.gyroScaleZ = it }
    invalidate()
}


fun clear() {
    landmarks = emptyList()
    invalidate()
}

fun setLandmarks(newLandmarks: List<Pair<Float, Float>>) {
    landmarks = newLandmarks
    invalidate()
}

// --- Blink Detection Variables ---
private var leftEyeOpenArea: Float = 0f
private var rightEyeOpenArea: Float = 0f
private var blinkStartTime: Long = 0
private var savedBlinkPosition: Pair<Float, Float>? = null
private var isBlinking = false
private var showBlueDot: Boolean = false
private var blueDotStartTime: Long = 0
private var blinkTriggered: Boolean = false

// --- New variables for averaging eye area ---
private val leftEyeAreaHistory = LinkedList<Pair<Long, Float>>()
private val rightEyeAreaHistory = LinkedList<Pair<Long, Float>>()
private val areaHistoryDuration = 500L // 1 second window


private fun getAverageEyeArea(eyeAreaHistory: LinkedList<Pair<Long, Float>>): Float {
    val currentTime = System.currentTimeMillis()

    // Remove old entries
    while (eyeAreaHistory.isNotEmpty() && currentTime - eyeAreaHistory.first.first > areaHistoryDuration){
        eyeAreaHistory.removeFirst()
    }

    if (eyeAreaHistory.isEmpty()) return 0f // Return 0 if no data

    var sum = 0f
    for(entry in eyeAreaHistory){
        sum += entry.second
    }

    return sum / eyeAreaHistory.size
}


private fun calculateEyeHeight(eyePoints: List<Pair<Float, Float>>): Float {
    if (eyePoints.isEmpty()) return 0f

    val minY = eyePoints.minOf { it.second }
    val maxY = eyePoints.maxOf { it.second }

    return abs(maxY - minY)
}

private fun getAverageEyeHeight(eyeHeightHistory: LinkedList<Pair<Long, Float>>): Float {
    val currentTime = System.currentTimeMillis()

    // Remove old entries
    while (eyeHeightHistory.isNotEmpty() && currentTime - eyeHeightHistory.first.first > heightHistoryDuration){
        eyeHeightHistory.removeFirst()
    }
    if (eyeHeightHistory.isEmpty()) return 0f // Return 0 if no data

    var sum = 0f
    for(entry in eyeHeightHistory){
        sum += entry.second
    }

    return sum / eyeHeightHistory.size
}




override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)

    var totalEndX = 0f
    var totalEndY = 0f
    var validGazeLines = 0
    var rightEyeDistance = 0f
    var leftEyeDistance = 0f
    var averageFaceWeightPoint: Pair<Float, Float> = Pair(0f, 0f)
    var averageGazeX: Float = 0f
    var averageGazeY: Float = 0f

    if (landmarks.isNotEmpty()) {
        // Get points for each eye
        val sphereRightEyePoints = sphereRightEyeIndices.mapNotNull { index ->
            landmarks.getOrNull(index)
        }
        val sphereLeftEyePoints = sphereLeftEyeIndices.mapNotNull { index ->
            landmarks.getOrNull(index)
        }


        // Calculate spheres with position adjustments
        val rightSphere = calculateEyeSphere(sphereRightEyePoints, rightEyeX, rightEyeY, rightEyeZ)
        val leftSphere = calculateEyeSphere(sphereLeftEyePoints, leftEyeX, leftEyeY, leftEyeZ)

        // Calculate eye points
        val rightEyePoints = rightEyeIndices.mapNotNull { index ->
            landmarks.getOrNull(index)
        }
        val leftEyePoints = leftEyeIndices.mapNotNull { index ->
            landmarks.getOrNull(index)
        }
        // Calculate eye height
        val currentLeftEyeHeight = calculateEyeHeight(leftEyePoints)
        val currentRightEyeHeight = calculateEyeHeight(rightEyePoints)

        // Add current height to history
        leftEyeHeightHistory.add(Pair(System.currentTimeMillis(), currentLeftEyeHeight))
        rightEyeHeightHistory.add(Pair(System.currentTimeMillis(), currentRightEyeHeight))

        // Get average eye heights
        val averageLeftEyeHeight = getAverageEyeHeight(leftEyeHeightHistory)
        val averageRightEyeHeight = getAverageEyeHeight(rightEyeHeightHistory)

        // --- Open Eye Baseline Update Logic ---
        val currentTime = System.currentTimeMillis()
        val rollingOpenAreaDuration = 1500L  // Look back at most 1.5 seconds
        val openEyeUpdateThreshold = 1000L // 1 second to update open eye area

        // Use to track how long eyes have been consistently open
        if(!isBlinking){
            //Update left eye open
            while (leftEyeHeightHistory.isNotEmpty() && currentTime - leftEyeHeightHistory.first.first > rollingOpenAreaDuration) {
                leftEyeHeightHistory.removeFirst()
            }
            // Update right eye open
            while (rightEyeHeightHistory.isNotEmpty() && currentTime - rightEyeHeightHistory.first.first > rollingOpenAreaDuration) {
                rightEyeHeightHistory.removeFirst()
            }

            // Check if open eye update timer has elapsed

            if(currentTime - lastOpenEyeUpdateTime > openEyeUpdateThreshold){
                if (leftEyeHeightHistory.isNotEmpty()) leftEyeOpenHeight = getAverageEyeHeight(leftEyeHeightHistory)
                if (rightEyeHeightHistory.isNotEmpty()) rightEyeOpenHeight = getAverageEyeHeight(rightEyeHeightHistory)
                lastOpenEyeUpdateTime = currentTime
            }

        }else{
            lastOpenEyeUpdateTime = currentTime
        }


        // Calculate distances using the radius as approximation
        val rightEyeRadius = rightSphere.radius
        val leftEyeRadius = leftSphere.radius


        // Invert radius into distance.
        // Add a small value to avoid division by zero and a minimum distance value.
        rightEyeDistance = 1f / (rightEyeRadius + 0.2f)
        leftEyeDistance = 1f / (leftEyeRadius + 0.2f)

        // Calculate face points
        val facePoints = headDirectionIndices.mapNotNull { index ->
            landmarks.getOrNull(index)
        }

        // Calculate the average face weight point using weighted average and Y offset
        averageFaceWeightPoint = calculateWeightedAveragePoint(facePoints, averageFaceWeightYOffset)

        // Draw the spheres
        draw3DSphere(canvas, leftSphere, false)
        draw3DSphere(canvas, rightSphere, true)


        // Get original pupil positions without adjustments
        val leftPupil = landmarks.getOrNull(leftPupilIndex)
        val rightPupil = landmarks.getOrNull(rightPupilIndex)

        // Calculate and draw gaze lines, accumulating endpoints
        var totalGazeX = 0f
        var totalGazeY = 0f
        var gazeCount = 0

        leftPupil?.let { pupil ->
            val gazeLine = calculateGazeLine(
                Pair(leftSphere.centerX, leftSphere.centerY),
                pupil // Original position, no adjustments
            )
            canvas.drawLine(
                gazeLine.startX,
                gazeLine.startY,
                gazeLine.endX,
                gazeLine.endY,
                gazePaint
            )
            totalGazeX += gazeLine.endX
            totalGazeY += gazeLine.endY
            totalEndX += gazeLine.endX
            totalEndY += gazeLine.endY
            gazeCount++
            validGazeLines++
        }

        rightPupil?.let { pupil ->
            val gazeLine = calculateGazeLine(
                Pair(rightSphere.centerX, rightSphere.centerY),
                pupil // Original position, no adjustments
            )
            canvas.drawLine(
                gazeLine.startX,
                gazeLine.startY,
                gazeLine.endX,
                gazeLine.endY,
                gazePaint
            )
            totalGazeX += gazeLine.endX
            totalGazeY += gazeLine.endY
            totalEndX += gazeLine.endX
            totalEndY += gazeLine.endY
            gazeCount++
            validGazeLines++
        }


        // Calculate average gaze X and Y
        if (gazeCount > 0) {
            averageGazeX = totalGazeX / gazeCount
            averageGazeY = totalGazeY / gazeCount
        }



        // Draw landmarks without position adjustments
        for ((index, landmark) in landmarks.withIndex()) {
            val paintToUse = when (index) {
                leftPupilIndex, rightPupilIndex -> purplePaint
                in rightEyeIndices -> bluePaint
                in leftEyeIndices -> greenPaint
                else -> whitePaint
            }
            // Use original positions for landmarks
            canvas.drawCircle(landmark.first, landmark.second, 3f, paintToUse)
        }
        //Draw Head Direction Line
        val headDirectionPoint = landmarks.getOrNull(headDirectionTargetIndex)
        headDirectionPoint?.let {
            val headDirectionResult = calculateHeadDirection(averageFaceWeightPoint, it)
            val directionX = headDirectionResult.first
            val directionY = headDirectionResult.second
            val magnitude = headDirectionResult.third

            val endX = it.first + directionX
            val endY = it.second + directionY

            canvas.drawLine(
                averageFaceWeightPoint.first,
                averageFaceWeightPoint.second,
                endX,
                endY,
                orangePaint
            )

            val extendedEndX = it.first + directionX * 2
            val extendedEndY = it.second + directionY * 2
            canvas.drawLine(
                averageFaceWeightPoint.first,
                averageFaceWeightPoint.second,
                extendedEndX,
                extendedEndY,
                orangePaint
            )

            val headDirectionScaleX = directionX * magnitude * moveXHeadDirectionScale // Taking the absolute value
            val headDirectionScaleY = directionY * magnitude * moveYHeadDirectionScale


            // Draw the brown dot at the adjusted average endpoint of gaze lines
            if (validGazeLines > 0) {
                val averageEndX = totalEndX / validGazeLines
                val averageEndY = totalEndY / validGazeLines

                // Calculate the "movement" from the average gaze
                var moveXChange = 0f
                var moveYChange = 0f

                if (validGazeLines > 0) {
                    moveXChange = averageEndX - width / 2f
                    moveYChange = averageEndY - height / 2f
                }
                
                //Average eye Radius
                val averageEyeRadius = (rightEyeDistance + leftEyeDistance) / 2

                // Inverted distance factor
                val distanceFactor = averageEyeRadius

                // Distance scaling effect
                val sensitivityScale = distanceFactor

  
                
                

     // 1. Calculate screen center Y
                val screenCenterY = height / 2f
                // 2. Get landmark Y at index 9
                val landmark168Y = landmarks.getOrNull(168)?.second ?: 0f
                val landmark6Y = landmarks.getOrNull(6)?.second ?: 0f
                val landmark197Y = landmarks.getOrNull(197)?.second ?: 0f

                // 3. Calculate Y difference
                var yDiff = screenCenterY - ((landmark168Y+landmark6Y+landmark197Y)/3)
                
                
                var pscp = sensitivityScale
             
                val pscpText = "                                                    pscp: ${String.format("%.4f", pscp)}"
                canvas.drawText(pscpText, 10f, 40f, textPaint)
                 
                
                
                val headDirectionScaleYText = "                                                                                                          headDirectionScaleY: ${String.format("%.6f",headDirectionScaleY)}"
                 canvas.drawText(headDirectionScaleYText, 10f, 40f, textPaint)


                
                
                
                 // 4. Add the difference to moveYChange
                yDiffChange += yDiff
                pscpChange += pscp
                
                
                if (yDiff>= 450){
                    if(pscp>= 0.01){
                        if(headDirectionScaleY-30>=0){
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/1000))) // top section, top distance, head direction down
                        }else{
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/1000))) // top section, top distance, head direction up
                        }
                    }
                    
                    else if(pscp>= 0.008){
                        if(headDirectionScaleY-35>0){
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/1000))) // top section, mid distance, head direction down
                        }else{
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/1000))) // top section, mid distance, head direction up
                        }
                    }
                    
                    else if(pscp>= 0.007){
                        if(headDirectionScaleY-35>0){
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/1000))) // top section, close distance, head direction down
                        }else{
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/1000))) // top section, close distance, head direction up
                        }
                    }
                    
                    else{
                        if(headDirectionScaleY-35>0){
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/500))) // top section, super close distance, head direction down
                        }else{
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/200))) // top section, super close distance, head direction up
                        }                        }
                }
                
                
                else if (yDiff>= 0){
                    if(pscp>= 0.01){
                        if(headDirectionScaleY-30>=0){
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/1000))) // mid section, top distance, head direction down
                                                        }else{
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/1000))) // mid section, top distance, head direction up
                        }
                    }
                    
                    else if(pscp>= 0.008){
                        if(headDirectionScaleY-35>0){
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/1000))) // mid section, mid distance, head direction down
                        }else{
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/1000))) // mid section, mid distance, head direction up
                        }
                    }
                    
                    else if(pscp>= 0.007){
                        if(headDirectionScaleY-35>0){
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/1000))) // mid section, close distance, head direction down
                        }else{
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/1000))) // mid section, close distance, head direction up
                        }
                    }
                    
                    else{
                        if(headDirectionScaleY-35>0){
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/1000)))  // mid section, super close distance, head direction down
                        }else{
                            moveYChange += (yDiff* (1.37f-pscp*16.5f + ((headDirectionScaleY -30)/1000)))  // mid section, super close distance, head direction up
                        }                 
                    }
                }
                
                
                
                
                else if (yDiff>= -450){
                    if(pscp>= 0.01){
                        if(headDirectionScaleY-30>=0){
                            moveYChange += (yDiff* (1.15f-pscp*28.5f + ((headDirectionScaleY -30)/1000))) // bottom section, top distance, head direction down
                        }else{
                            moveYChange += (yDiff* (1.15f-pscp*28.5f + (-3 *(headDirectionScaleY -30)/1000))) // bottom section, top distance, head direction up
                        }
                    }
                    
                    else if(pscp>= 0.008){
                        if(headDirectionScaleY-35>0){
                            moveYChange += (yDiff* (1.11f-pscp*28.5f + ((headDirectionScaleY -35)/1000))) // bottom section, mid distance, head direction down
                        }else{
                            moveYChange += (yDiff* (1.11f-pscp*28.5f + ((headDirectionScaleY -35)/1000))) // bottom section, mid distance, head direction up
                        }
                    }
                    
                    else if(pscp>= 0.007){
                        if(headDirectionScaleY-35>0){
                            moveYChange += (yDiff* (1.06f-pscp*28.5f + ((headDirectionScaleY -35)/1000))) // bottom section, close distance, head direction down
                        }else{
                            moveYChange += (yDiff* (1.06f-pscp*28.5f + ((headDirectionScaleY -35)/1000))) // bottom section, close distance, head direction up
                        }           
                    }
                    
                    else{
                        if(headDirectionScaleY-35>0){
                            moveYChange += (yDiff* (1.07f-pscp*28.5f + ((headDirectionScaleY -35)/1000))) // bottom section, super close distance, head direction down
                        }else{
                            moveYChange += (yDiff* (1.07f-pscp*28.5f + ((headDirectionScaleY -35)/1000))) // bottom section, super close distance, head direction up
                        }
                    }
                }
                
                else{
                    if(pscp>= 0.01){
                        if(headDirectionScaleY-30>=0){
                            moveYChange += (yDiff* (1.18f-pscp*28.5f + (0.4f *(headDirectionScaleY -30)/1000))) // Super bottom section, top distance, head direction down
                        }else{
                            moveYChange += (yDiff* (1.15f-pscp*28.5f + (-3 *(headDirectionScaleY -30)/1000))) // Super bottom section, top distance, head direction up
                        }
                    }
                    
                    else if(pscp>= 0.008){
                        if(headDirectionScaleY-35>0){
                            moveYChange += (yDiff* (1.11f-pscp*28.5f + ((headDirectionScaleY -35)/1000))) // Super bottom section, mid distance, head direction down
                        }else{
                            moveYChange += (yDiff* (1.11f-pscp*28.5f + ((headDirectionScaleY -35)/1000))) // Super bottom section, mid distance, head direction up
                        }
                    }
                    
                    else if(pscp>= 0.007){
                        if(headDirectionScaleY-35>0){
                            moveYChange += (yDiff* (1.06f-pscp*28.5f + ((headDirectionScaleY -35)/1000))) // Super bottom section, close distance, head direction down
                        }else{
                            moveYChange += (yDiff* (1.06f-pscp*28.5f + ((headDirectionScaleY -35)/1000))) // Super bottom section, close distance, head direction up
                        }        
                    }
                    
                    else{
                        if(headDirectionScaleY-35>0){
                            moveYChange += (yDiff* (1.07f-pscp*28.5f + ((headDirectionScaleY -35)/1000))) // Super bottom section, super close distance, head direction down
                        }else{
                            moveYChange += (yDiff* (1.07f-pscp*28.5f + ((headDirectionScaleY -35)/1000))) // Super bottom section, super close distance, head direction up
                        }                    
                    }
                }
                
                moveYChange = moveYChange + pscp*100
                val moveYChangeText = "moveYChange: ${String.format("%.4f", moveYChange)}"
                canvas.drawText(moveYChangeText, 10f, 40f, textPaint)
                
                 

                              // Apply moveX and moveY with distance adjustment for sensitivity
                val adjustedMoveX = moveX * (sensitivityScale * distanceEffectMoveX * distanceScalingFactor)
                //Apply gyroscope scaling based on the gyro velocity magnitude
                gyroScaleX = 1f + (abs(gyroVelocityX) / 2f)
                gyroScaleY = 1f + (abs(gyroVelocityY) / 2f)
                gyroScaleZ = 1f + (abs(gyroVelocityZ) / 2f)

                // Gyroscope influence (Scaling now!)
                val gyroScaleXChange = gyroVelocityX * gyroScaleX
                val gyroScaleYChange = gyroVelocityY * gyroScaleY
                val gyroScaleZChange = gyroVelocityZ * gyroScaleZ


                if(headDirectionScaleY >=0){
                    adjustedMoveY = -moveY * (sensitivityScale * distanceEffectMoveY * distanceScalingFactor)
                }else{
                    adjustedMoveY = -moveY * (sensitivityScale * distanceEffectMoveY * distanceScalingFactor)
                }

              //Apply gyro to position
                var targetX = (width / 2f) + (moveXChange * adjustedMoveX) + staticX + (-gyroScaleXChange * 200f)
                if (moveYChange >= 0){
                     targetY = ((height / 2f) - (moveYChange * adjustedMoveY)  + staticY  + (-gyroScaleYChange * 200f))
                }else{
                     targetY = ((height / 2f) -  (moveYChange * adjustedMoveY)  + staticY  + (-gyroScaleYChange * 200f))
                }


                // Damping factor (you can adjust this)
                val damping = 0.3f
                var finalX = 0f
                var finalY = 0f


                // Apply damping to X value
                if(abs(targetX - (averageDotPositions.lastOrNull()?.first ?: 0f)) < 100f){
                    finalX = (averageDotPositions.lastOrNull()?.first ?: targetX) + (targetX - (averageDotPositions.lastOrNull()?.first ?: targetX)) * damping
                }else{
                    finalX = targetX
                }

                // Apply damping to Y value
                if(abs(targetY - (averageDotPositions.lastOrNull()?.second ?: 0f)) < 100f){
                    finalY =  (averageDotPositions.lastOrNull()?.second ?: targetY) + (targetY - (averageDotPositions.lastOrNull()?.second ?: targetY)) * damping
                }else{
                    finalY = targetY
                }
                     
                // Set current values as previous values, so they will be used in next frame
                prevGyroVelocityX = gyroVelocityX
                prevGyroVelocityY = gyroVelocityY
                prevGyroVelocityZ = gyroVelocityZ

                // Keep the brown dot within frame bounds
                finalX = finalX.coerceIn(0f, width.toFloat())
                finalY = finalY.coerceIn(0f, height.toFloat())

                // Apply head direction only at the end after the damping
                finalX += headDirectionScaleX
                finalY += 0

                val brownPaint = Paint().apply {
                    color = Color.rgb(165, 42, 42)                        
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
                canvas.drawCircle(finalX, finalY, 10f, brownPaint)
                // Update the average dot positions queue
                averageDotPositions.add(Pair(finalX, finalY))
                if (averageDotPositions.size > maxAverageDotSize) {
                    averageDotPositions.removeFirst()
                }


                // Calculate average position of brown dot
                var avgX = 0f
                var avgY = 0f
                for (pos in averageDotPositions) {
                    avgX += pos.first
                    avgY += pos.second
                }
                if (averageDotPositions.isNotEmpty()) {
                    avgX /= averageDotPositions.size
                    avgY /= averageDotPositions.size

                    val avgPaint = Paint().apply {
                        color = Color.argb(128, 255, 165, 0) // Half-transparent orange
                        style = Paint.Style.FILL
                        isAntiAlias = true
                    }
                    canvas.drawCircle(avgX, avgY, 15f, avgPaint) // Draw orange average dot
                }
                // --- Blink Detection Logic ---
                val leftEyeClosedPercentage = 1f - (averageLeftEyeHeight / leftEyeOpenHeight).coerceIn(0f,1f)
                val rightEyeClosedPercentage = 1f - (averageRightEyeHeight / rightEyeOpenHeight).coerceIn(0f,1f)


                val closeBlink = 0.1f
                val blinkThreshold = 0.4f

                val isEyeClosed = ((leftEyeClosedPercentage > closeBlink*0.25f|| rightEyeClosedPercentage > closeBlink* 0.25f) || (leftEyeClosedPercentage > closeBlink*0.25f && rightEyeClosedPercentage > closeBlink* 0.25f))

                if (isEyeClosed) {
                    if (!isBlinking) {
                        isBlinking = true
                        blinkStartTime = System.currentTimeMillis()
                        savedBlinkPosition = Pair(avgX, avgY)
                    }
                }else{
                    isBlinking = false
                    blinkTriggered = false
                }

                var blinkDuration: Long = 0
                if((leftEyeClosedPercentage > closeBlink || rightEyeClosedPercentage > closeBlink) || (leftEyeClosedPercentage > closeBlink && rightEyeClosedPercentage > closeBlink)){
                    blinkDuration = (System.currentTimeMillis() - blinkStartTime)
                }else{
                    blinkDuration = 0
                    isBlinking = false
                    blinkTriggered = false
                }

                // Check for half blink only if the trigger has not been activated
                if (isEyeClosed &&  !blinkTriggered && blinkDuration < 2000 && blinkDuration > 200 && (((leftEyeClosedPercentage > closeBlink && leftEyeClosedPercentage < blinkThreshold) || (rightEyeClosedPercentage > closeBlink && rightEyeClosedPercentage < blinkThreshold)) || ((leftEyeClosedPercentage > closeBlink && leftEyeClosedPercentage < blinkThreshold) && (rightEyeClosedPercentage > closeBlink && rightEyeClosedPercentage < blinkThreshold))))
                {
                    showBlueDot = true
                    blueDotStartTime = System.currentTimeMillis()
                    blinkTriggered = true
                    blinkDuration = 0

                }else{
                    blinkTriggered = false
                }
                //Draw blue dot
                if(showBlueDot) {
                    savedBlinkPosition?.let {
                        canvas.drawCircle(it.first, it.second, 10f, blinkDotPaint)
                    }

                    val blueDotDuration = System.currentTimeMillis() - blueDotStartTime
                    if (blueDotDuration > 2000){
                        showBlueDot = false
                        savedBlinkPosition = null

                    }
                }
            }
        }
    }

}

init {
    showBlueDot = false
}


}

