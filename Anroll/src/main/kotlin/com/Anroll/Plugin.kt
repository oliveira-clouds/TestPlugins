package com.Anroll

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnrollPlugin : Plugin() {
    override fun load(context: com.lagradost.cloudstream3.utils.AppContext) {
        registerMainAPI(Anroll())
    }
}
