package org.opentrafficmap.receiver

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // IVIM speed limits and SPATEM timing: decoded with the generated ASN.1 tables.
        val decoder = org.opentrafficmap.receiver.asn1.ItsFrameDecoder { set -> assets.open("its_asn1/$set.json") }
        V2xHub.messageDecoder = { payload -> decoder.decode(payload).lastOrNull()?.value }
        if (Prefs.osmandAddon(this)) org.opentrafficmap.receiver.osmand.OsmAndBridge.start(this)
        AppCompatDelegate.setDefaultNightMode(
            if (Prefs.forceDarkMode(this)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        )
    }
}
