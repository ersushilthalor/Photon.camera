package com.hinnka.mycamera.lut

import com.hinnka.mycamera.utils.NativeLibraryLoader

object LutProcessor {
    init {
        NativeLibraryLoader.ensureLoaded()
    }

    external fun resampleLutNative(srcData: ShortArray, size: Int, curveType: Int): ShortArray?
    external fun resampleSizeNative(srcData: ShortArray, srcSize: Int, targetSize: Int): ShortArray?
}
