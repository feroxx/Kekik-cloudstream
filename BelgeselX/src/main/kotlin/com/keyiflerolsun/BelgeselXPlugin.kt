package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BelgeselXPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BelgeselX())
        registerExtractorAPI(Odnoklassniki())
    }
}