package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class GolgeTVPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(GolgeTV())
        registerExtractorAPI(Golge19())
        registerExtractorAPI(Golge16())
        // TODO: apiler bozuk uygulamada da hata donuyo duzeldiginde tamamlanicak
        //registerExtractorAPI(Golge17())
        //registerExtractorAPI(Golge7())
        //registerExtractorAPI(Golge8())
    }
}