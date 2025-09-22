package com.DoramasOnline

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DoramasOnlinePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DoramasOnline())
    }
}
