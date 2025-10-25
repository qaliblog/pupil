# Eye Positioning and Cursor Control Analysis

## Problem Statement

The camera and user eyes are typically positioned in the **bottom 60%** of the screen, with eyes rarely appearing in the upper 40%. This creates several challenges:

1. **Negative/Positive Range Issues**: Current calculations can produce negative values that interfere with cursor positioning
2. **Asymmetric Distribution**: Most eye positions are in the lower portion, making upper screen areas harder to reach
3. **Inconsistent Sensitivity**: The same eye movement should produce consistent cursor movement regardless of screen position

## Current System Analysis

### Eye Position Distribution
- **Typical Range**: Eyes appear in bottom 60% of screen (Y: 0.4 * screenHeight to screenHeight)
- **Rare Range**: Eyes appear in top 40% of screen (Y: 0 to 0.4 * screenHeight)
- **Center Reference**: Screen center is at Y = screenHeight / 2

### Current Issues in `calculateYPositionInfluence()`

```kotlin
// PROBLEMATIC: Can produce negative values
val eyeOffsetFromCenter = (eyeY - screenCenterY) / screenCenterY
return eyeOffsetFromCenter * 0.5f
```

**Problems:**
- When `eyeY < screenCenterY` (eyes in upper half), result is negative
- Negative values can cause unexpected cursor behavior
- No normalization for the typical bottom 60% range

## Proposed Solution: Normalized Eye Position System

### 1. Eye Position Normalization

Instead of using screen center as reference, use the **typical eye range** as the baseline:

```kotlin
// Define typical eye position range
val typicalEyeMinY = screenHeight * 0.4f  // Bottom 60% starts here
val typicalEyeMaxY = screenHeight * 0.9f  // Leave some margin at bottom
val typicalEyeRange = typicalEyeMaxY - typicalEyeMinY

// Normalize eye position to 0-1 range within typical area
val normalizedEyeY = ((eyeY - typicalEyeMinY) / typicalEyeRange).coerceIn(0f, 1f)
```

### 2. Cursor Position Mapping

Map normalized eye position to cursor sensitivity:

```kotlin
// Map normalized position to cursor influence
// 0.0 = eyes at top of typical range (less sensitive)
// 1.0 = eyes at bottom of typical range (more sensitive)
val yPositionInfluence = (normalizedEyeY - 0.5f) * 2f  // Range: -1 to +1
```

### 3. Distance-Based Scaling

Use sphere size to determine distance range (1-3):

```kotlin
// Sphere size ranges (pixels)
val minSphereSize = 15f    // Far away
val maxSphereSize = 45f    // Close up

// Distance range calculation
val normalizedSphereSize = ((sphereSize - minSphereSize) / (maxSphereSize - minSphereSize)).coerceIn(0f, 1f)
val distanceRange = 1f + (normalizedSphereSize * 2f)  // Range: 1-3
```

### 4. Gaze Velocity Analysis

Track gaze line tip changes for movement detection:

```kotlin
// Gaze velocity calculation
val gazeVelocity = sqrt(deltaX² + deltaY²)

// Movement detection thresholds
val staticThreshold = 2f      // Below this = static/drift
val deliberateThreshold = 8f  // Above this = deliberate movement
val rapidThreshold = 15f      // Above this = rapid movement
```

### 5. Adaptive Sensitivity Formula

Combine all factors for adaptive sensitivity:

```kotlin
val baseSensitivity = 1.0f

// Movement factor based on gaze velocity
val movementFactor = when {
    gazeVelocity < staticThreshold -> 0.7f      // Reduce sensitivity for drift
    gazeVelocity < deliberateThreshold -> 1.0f  // Normal sensitivity
    gazeVelocity < rapidThreshold -> 1.3f       // Increase for deliberate movement
    else -> 1.5f                                // High sensitivity for rapid movement
}

// Position factor based on eye Y position
val positionFactor = 0.8f + (normalizedEyeY * 0.4f)  // Range: 0.8-1.2

// Distance factor
val distanceFactor = 2f - distanceRange  // Range: -1 to 1 (inverse relationship)

// Final adaptive sensitivity
val adaptiveSensitivity = baseSensitivity * movementFactor * positionFactor * (1f + distanceFactor * 0.3f)
```

## Complete Formula Implementation

### Eye Position Normalization
```kotlin
private fun calculateNormalizedEyePosition(eyeY: Float, screenHeight: Float): Float {
    val typicalEyeMinY = screenHeight * 0.4f
    val typicalEyeMaxY = screenHeight * 0.9f
    val typicalEyeRange = typicalEyeMaxY - typicalEyeMinY
    
    return ((eyeY - typicalEyeMinY) / typicalEyeRange).coerceIn(0f, 1f)
}
```

### Y Position Influence (Fixed)
```kotlin
private fun calculateYPositionInfluence(eyeY: Float, screenHeight: Float): Float {
    val normalizedEyeY = calculateNormalizedEyePosition(eyeY, screenHeight)
    
    // Map to -1 to +1 range for cursor influence
    val yInfluence = (normalizedEyeY - 0.5f) * 2f
    
    // Apply non-linear scaling for better control
    val scaledInfluence = yInfluence * (1f + abs(yInfluence) * 0.3f)
    
    return scaledInfluence.coerceIn(-1f, 1f)
}
```

### Distance Range Calculation
```kotlin
private fun calculateDistanceRange(sphereSize: Float): Float {
    val minSphereSize = 15f
    val maxSphereSize = 45f
    
    val normalizedSize = ((sphereSize - minSphereSize) / (maxSphereSize - minSphereSize)).coerceIn(0f, 1f)
    return 1f + (normalizedSize * 2f)  // Range: 1-3
}
```

### Enhanced Adaptive Sensitivity
```kotlin
private fun calculateAdaptiveSensitivity(gazeVelocity: Float, eyeY: Float, screenHeight: Float, sphereSize: Float): Float {
    val baseSensitivity = 1.0f
    
    // Movement factor
    val movementFactor = when {
        gazeVelocity < 2f -> 0.7f
        gazeVelocity < 8f -> 1.0f
        gazeVelocity < 15f -> 1.3f
        else -> 1.5f
    }
    
    // Position factor (eyes in bottom 60% get higher sensitivity)
    val normalizedEyeY = calculateNormalizedEyePosition(eyeY, screenHeight)
    val positionFactor = 0.8f + (normalizedEyeY * 0.4f)
    
    // Distance factor (closer = more sensitive)
    val distanceRange = calculateDistanceRange(sphereSize)
    val distanceFactor = 2f - distanceRange
    
    return baseSensitivity * movementFactor * positionFactor * (1f + distanceFactor * 0.3f)
}
```

### Final Cursor Position Calculation
```kotlin
// Calculate final cursor position
val gazeDirectionX = averageGazeX - ((leftSphere.centerX + rightSphere.centerX) / 2f)
val gazeDirectionY = averageGazeY - ((leftSphere.centerY + rightSphere.centerY) / 2f)

val screenCenterX = width / 2f + staticX
val screenCenterY = height / 2f + staticY

// Head direction influence
val headInfluencedCenterX = screenCenterX + (headDirectionX * pointerHeadSensitivity)
val headInfluencedCenterY = screenCenterY - (headDirectionY * headTiltYBaseSensitivity)

// Enhanced gaze mapping
val averageEyeY = (leftSphere.centerY + rightSphere.centerY) / 2f
val averageSphereSize = (leftSphere.radius + rightSphere.radius) / 2f
val gazeVelocity = calculateGazeTipChange(averageGazeX, averageGazeY)

val adaptiveSensitivity = calculateAdaptiveSensitivity(gazeVelocity, averageEyeY, height.toFloat(), averageSphereSize)
val distanceRange = calculateDistanceRange(averageSphereSize)
val yPositionInfluence = calculateYPositionInfluence(averageEyeY, height.toFloat())

val gazeScreenOffsetX = gazeDirectionX * pointerGazeSensitivityX * distanceScalingFactor * adaptiveSensitivity * distanceRange
val gazeScreenOffsetY = gazeDirectionY * pointerGazeSensitivityY * distanceScalingFactor * adaptiveSensitivity * distanceRange

// Apply Y position influence
val yPositionOffset = yPositionInfluence * 30f  // Adjust multiplier as needed

// Gyro stabilization
val gyroInfluenceX = -gyroVelocityX * pointerGyroSensitivity
val gyroInfluenceY = -gyroVelocityY * pointerGyroSensitivity

// Final position
val targetX = headInfluencedCenterX + gazeScreenOffsetX + gyroInfluenceX
val targetY = headInfluencedCenterY + gazeScreenOffsetY + gyroInfluenceY + yPositionOffset
```

## Key Improvements

1. **No Negative Values**: All calculations produce predictable, bounded ranges
2. **Typical Range Focus**: Optimized for bottom 60% eye positioning
3. **Consistent Sensitivity**: Same eye movement produces consistent cursor movement
4. **Adaptive Behavior**: System adjusts based on user behavior patterns
5. **Distance Awareness**: Closer/farther positioning affects sensitivity appropriately
6. **Smooth Transitions**: Non-linear scaling prevents abrupt sensitivity changes

## Testing Recommendations

1. **Eye Position Range**: Test with eyes in different vertical positions
2. **Distance Variation**: Test with different distances from camera
3. **Movement Patterns**: Test deliberate vs. accidental movements
4. **Edge Cases**: Test extreme eye positions and rapid movements
5. **Calibration**: Allow users to adjust sensitivity multipliers for personal preference