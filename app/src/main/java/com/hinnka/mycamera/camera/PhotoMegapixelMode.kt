package com.hinnka.mycamera.camera

/**
 * Selects the still-photo sensor pixel mode.
 *
 * M50 is only active when the current camera advertises a maximum-resolution JPEG
 * stream through Camera2. It never fabricates 50 MP by interpolating a 12 MP image.
 */
enum class PhotoMegapixelMode {
    M12,
    M50,
}
