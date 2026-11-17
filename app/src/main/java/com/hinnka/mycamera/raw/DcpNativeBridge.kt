package com.hinnka.mycamera.raw

import com.hinnka.mycamera.utils.NativeLibraryLoader

object DcpNativeBridge {
    init {
        NativeLibraryLoader.ensureLoaded()
    }

    external fun parseDcpToJson(filePath: String): String
}
