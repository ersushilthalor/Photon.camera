package com.hinnka.mycamera.lut

import kotlin.math.abs

private fun setIdentityMatrix(matrix: FloatArray) {
    matrix.fill(0f)
    matrix[0] = 1f
    matrix[5] = 1f
    matrix[10] = 1f
    matrix[15] = 1f
}

/**
 * Holds the orientation-only transform used to present delayed ImageReader output.
 *
 * Video encoding can derive its fixed output orientation from Camera2 sensor metadata.
 * The live preview instead mirrors the actual Camera2 OES producer transform so the
 * stabilized ImageReader texture has the same rotation/reflection as the viewfinder.
 */
internal class MgcEisPresentationTransform {
    val matrix = FloatArray(16).also(::setIdentityMatrix)
    private var captured = false

    val isCaptured: Boolean
        get() = captured

    /**
     * Updates only the cardinal rotation/reflection axes from the active Camera2 OES frame.
     *
     * SurfaceTexture's matrix also contains producer crop/scale information. Those values are
     * intentionally not copied: EIS keeps its existing crop, geometry and stabilization mesh.
     * Call this only after updateTexImage() accepted a frame for the active camera generation.
     *
     * Unlike captureFromSensorOrientation(), this is deliberately refreshable on each OES frame:
     * after a camera handoff a queued old callback must not permanently freeze the old lens'
     * front-camera reflection/rotation into the new EIS session.
     */
    fun updateFromOes(oesMatrix: FloatArray): Boolean {
        if (oesMatrix.size < 16) return false

        val uAlongX = abs(oesMatrix[0]) >= abs(oesMatrix[1])
        val vAlongX = abs(oesMatrix[4]) >= abs(oesMatrix[5])
        val uMagnitude = maxOf(abs(oesMatrix[0]), abs(oesMatrix[1]))
        val vMagnitude = maxOf(abs(oesMatrix[4]), abs(oesMatrix[5]))
        if (uAlongX == vAlongX || uMagnitude < 0.5f || vMagnitude < 0.5f) {
            return false
        }

        val resolved = FloatArray(16).also(::setIdentityMatrix)
        if (!uAlongX) {
            // The OES transform is a cardinal 90° rotation. Convert its bottom-left
            // producer origin to the regular 2D texture's orientation.
            resolved[0] = 0f
            resolved[5] = 0f
            resolved[4] = if (oesMatrix[4] >= 0f) 1f else -1f
            resolved[1] = if (oesMatrix[1] >= 0f) -1f else 1f
            resolved[12] = if (oesMatrix[4] >= 0f) 0f else 1f
            resolved[13] = if (oesMatrix[1] >= 0f) 1f else 0f
        } else {
            resolved[0] = if (oesMatrix[0] >= 0f) 1f else -1f
            resolved[5] = if (oesMatrix[5] >= 0f) -1f else 1f
            resolved[12] = if (oesMatrix[0] >= 0f) 0f else 1f
            resolved[13] = if (oesMatrix[5] >= 0f) 1f else 0f
        }

        System.arraycopy(resolved, 0, matrix, 0, matrix.size)
        captured = true
        return true
    }

    /**
     * Sensor-metadata orientation remains available for the video encoder path, where Camera2's
     * output orientation is resolved independently of the live OES viewfinder presentation.
     */
    fun captureFromSensorOrientation(sensorOrientationDegrees: Int): Boolean {
        if (captured) return true
        val resolved = resolveMgcEisPresentationMatrix(sensorOrientationDegrees) ?: return false
        System.arraycopy(resolved, 0, matrix, 0, matrix.size)
        captured = true
        return true
    }

    fun reset() {
        setIdentityMatrix(matrix)
        captured = false
    }
}


/** Camera stream identity used to invalidate orientation state across lens switches. */
internal data class MgcEisCameraKey(
    val sensorOrientation: Int,
    val lensFacing: Int,
    /** Camera2 camera ID: two sensors can share facing and orientation but not stream identity. */
    val cameraId: String = "",
)

/**
 * Tracks the active Camera2 stream. A key change invalidates the ImageReader presentation
 * transform and advances the generation before frames from the replacement stream are consumed.
 */
internal class MgcEisCameraKeyTracker(
    initialKey: MgcEisCameraKey,
    private val presentationTransform: MgcEisPresentationTransform,
) {
    @Volatile
    private var activeKey: MgcEisCameraKey = initialKey

    @Volatile
    var generation: Long = 0L
        private set

    @Synchronized
    fun update(nextKey: MgcEisCameraKey): Boolean {
        if (nextKey == activeKey) return false
        activeKey = nextKey
        presentationTransform.reset()
        generation += 1L
        return true
    }
}

/** Maps natural-display coordinates to the unrotated Camera2 YUV texture. */
internal fun resolveMgcEisPresentationMatrix(sensorOrientationDegrees: Int): FloatArray? {
    if (sensorOrientationDegrees % 90 != 0) return null
    val matrix = FloatArray(16).also {
        it[10] = 1f
        it[15] = 1f
    }
    when (Math.floorMod(sensorOrientationDegrees, 360)) {
        0 -> {
            matrix[0] = 1f
            matrix[5] = 1f
        }
        90 -> {
            // Clockwise display rotation: source = (1 - outputV, outputU).
            matrix[4] = -1f
            matrix[1] = 1f
            matrix[12] = 1f
        }
        180 -> {
            matrix[0] = -1f
            matrix[5] = -1f
            matrix[12] = 1f
            matrix[13] = 1f
        }
        270 -> {
            // Counter-clockwise display rotation: source = (outputV, 1 - outputU).
            matrix[4] = 1f
            matrix[1] = -1f
            matrix[13] = 1f
        }
    }
    return matrix
}
