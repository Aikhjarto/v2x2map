package org.opentrafficmap.receiver

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * Turns light OpenStreetMap tiles into a dark map: invert, rotate hue by 180° (keeps water
 * blue and parks green) and dim. Used by the phone app's "Dark" layer and the Android Auto
 * night map, since keyless dark tile servers (CARTO Dark Matter) now require an API key.
 */
object NightTiles {
    val filter = ColorMatrixColorFilter(ColorMatrix().apply {
        set(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f))
        postConcat(ColorMatrix(floatArrayOf(   // CSS hue-rotate(180deg)
            -0.574f, 1.430f, 0.144f, 0f, 0f,
            0.426f, 0.430f, 0.144f, 0f, 0f,
            0.426f, 1.430f, -0.856f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
        postConcat(ColorMatrix().apply { setScale(0.80f, 0.82f, 0.88f, 1f) })
    })
}
