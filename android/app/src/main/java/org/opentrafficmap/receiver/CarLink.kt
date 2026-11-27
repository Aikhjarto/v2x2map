package org.opentrafficmap.receiver

/** Whether the Android Auto car screen of V2X2MAP is currently open (set by the car session). */
object CarLink {
    @Volatile var carScreenActive = false
}
