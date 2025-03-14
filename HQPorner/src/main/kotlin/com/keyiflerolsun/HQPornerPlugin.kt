package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HQPornerPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HQPorner())
        registerExtractorAPI(MyDaddy())
    }
}