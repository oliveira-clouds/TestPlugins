package com.AnimeQ
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeQPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeQ())
    }
}
