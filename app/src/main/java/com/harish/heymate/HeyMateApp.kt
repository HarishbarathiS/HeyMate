package com.harish.heymate

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.harish.heymate.ble.GlassesBle
import com.harish.heymate.core.CaptureCoordinator
import com.harish.heymate.data.Prefs
import com.harish.heymate.wifitransfer.GalleryState
import com.harish.heymate.wifitransfer.WifiImportCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HeyMateApp : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** App-wide Coil loader with a video-frame decoder, so AsyncImage on an .mp4 shows a frame. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()

    override fun onCreate() {
        super.onCreate()

        // 1. Bring up the vendor BLE stack + receivers.
        GlassesBle.init(this)

        // 2. Start the capture → agent pipeline (listens for glasses button events once connected).
        CaptureCoordinator.init(this)

        // 2b. Wi-Fi media import coordinator (holds app context; no work until the user starts it).
        WifiImportCoordinator.init(this)
        // 2c. Gallery "new content" tracker (derives new-item count from BLE media count vs imported).
        GalleryState.init(this)

        // 3. Restore the bound device and reconnect automatically.
        val prefs = Prefs(this)
        appScope.launch {
            GlassesBle.setAutoReconnect(prefs.autoReconnect.first())
            val mac = prefs.boundMacNow()
            if (!mac.isNullOrBlank()) {
                val name = prefs.boundName.first()?.takeIf { it.isNotBlank() }
                GlassesBle.restoreBoundDevice(mac, name)
            }
        }
    }
}
