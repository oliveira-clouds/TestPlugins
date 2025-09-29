package com.AnimesDigital
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimesDigitalPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimesDigitalProvider())
    }
}
