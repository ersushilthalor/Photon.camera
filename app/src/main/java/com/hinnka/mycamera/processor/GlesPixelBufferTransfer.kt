package com.hinnka.mycamera.processor

import com.hinnka.mycamera.utils.NativeLibraryLoader

/** GLES pixel-buffer operations missing from Android's Java GLES bindings. */
internal object GlesPixelBufferTransfer {
    init {
        NativeLibraryLoader.ensureLoaded()
    }

    /** Queues an RGBA16F upload from PBO offset zero into an existing 2D texture. */
    external fun uploadRgba16fPboToTexture(
        pixelBufferObject: Int,
        textureId: Int,
        width: Int,
        height: Int,
    ): Boolean
}
