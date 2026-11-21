package com.hinnka.mycamera.lut


private fun setIdentityMatrix(matrix: FloatArray) {
    matrix.fill(0f)
    matrix[0] = 1f
    matrix[5] = 1f
    matrix[10] = 1f
    matrix[15] = 1f
}

/** Retains the cardinal OES orientation for the delayed ImageReader output presentation. */
internal class MgcEisPresentationTransform {
    val matrix = FloatArray(16).also(::setIdentityMatrix)
    private var captured = false

    /**
     * Initializes the ImageReader presentation transform without waiting for an OES frame.
     *
     * Video EIS+ intentionally does not attach the recorder's OES surface to Camera2: frames are
     * delivered through the timestamp-matched YUV ImageReader instead. Consequently the recorder
     * cannot discover the producer transform through [SurfaceTexture.getTransformMatrix]. Camera2
     * guarantees that SENSOR_ORIENTATION is cardinal, so it is sufficient to rotate the raw YUV
     * image into the same natural-device orientation used by the normal OES recording path.
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
