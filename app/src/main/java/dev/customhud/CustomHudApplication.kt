package dev.customhud

import android.app.Application
import android.os.Build
import android.os.Process
import dev.customhud.adapters.EcarxCarHub
import dev.customhud.adapters.EcarxVehicleFeed
import dev.customhud.layout.LayoutStore
import dev.customhud.logging.HudLog
import dev.customhud.media.MediaMonitor

class CustomHudApplication : Application() {
    lateinit var log: HudLog
        private set
    lateinit var layoutStore: LayoutStore
        private set
    lateinit var media: MediaMonitor
        private set
    /** The one ECARX Car connection of the process (gear subscription + vehicle poller + probe). */
    lateinit var ecarx: EcarxCarHub
        private set
    /** ECARX vehicle data shared by the HUD windows and the probe page. */
    lateinit var vehicle: EcarxVehicleFeed
        private set

    override fun onCreate() {
        super.onCreate()
        log = HudLog()
        layoutStore = LayoutStore(this)
        media = MediaMonitor(this)
        ecarx = EcarxCarHub(this, object : EcarxCarHub.Log {
            override fun info(message: String) = log.add("ECARX", message)
            override fun warn(message: String) = log.add("ECARX", message, "WARN", "ECARX_CAR")
        })
        vehicle = EcarxVehicleFeed(ecarx, log)
        log.add(
            "APP",
            "processCreated android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} uid=${Process.myUid()} buildId=${BuildConfig.BUILD_ID} layoutRevision=${layoutStore.current.revision}",
        )
    }
}
