package com.hinnka.mycamera.lut


private fun setIdentityMatrix(matrix: FloatArray) {
    matrix.fill(0f)
    matrix[0] = 1f
    matrix[5] = 1f
    matrix[10] = 1f
    matrix[15] = 1f
}

/** Retains the active Camera2 sensor orientation for delayed ImageReader output presentation. */
internal class MgcEisPresentationTransform {
    val matrix = FloatArray(16).also(::setIdentityMatrix)
    private var captured = false

    /**
     * Initializes the ImageReader presentation transform without waiting for an OES frame.
     *
     * The OES preview and ImageReader YUV output are separate Camera2 producers. Their buffer
     * transforms are not interchangeable, and an OES callback can still be queued by the previous
     * camera session while a lens switch is in progress. Use the new camera's immutable
     * SENSOR_ORIENTATION instead of sampling a potentially stale OES transform. Camera2 defines
     * this value in cardinal 90-degree increments, which is sufficient to orient the raw YUV image
     * into the same natural-device coordinate space as the live preview.
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
