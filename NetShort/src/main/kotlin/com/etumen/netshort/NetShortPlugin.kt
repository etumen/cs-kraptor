package com.etumen.netshort

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NetShortPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NetShort())
    }
}
