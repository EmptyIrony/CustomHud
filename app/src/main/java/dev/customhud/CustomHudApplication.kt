package dev.customhud

import android.app.Application
import android.os.Build
import android.os.Process
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

    override fun onCreate() {
        super.onCreate()
        log = HudLog()
        layoutStore = LayoutStore(this)
        media = MediaMonitor(this)
        log.add(
            "APP",
            "processCreated android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} uid=${Process.myUid()} buildId=${BuildConfig.BUILD_ID} layoutRevision=${layoutStore.current.revision}",
        )
    }
}
