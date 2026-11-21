package org.opentrafficmap.receiver

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Road-sign speed limits (IVIM) for the car screen: decoded with the generated ASN.1 tables.
        val decoder = org.opentrafficmap.receiver.asn1.ItsFrameDecoder { set -> assets.open("its_asn1/$set.json") }
        V2xHub.ivimDecoder = { payload -> decoder.decode(payload).lastOrNull()?.value?.takeIf { it.at("ivi") != null } }
        AppCompatDelegate.setDefaultNightMode(
            if (Prefs.forceDarkMode(this)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )
    }
}
