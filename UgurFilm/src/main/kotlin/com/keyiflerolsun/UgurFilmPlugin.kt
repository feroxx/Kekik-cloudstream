package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class UgurFilmPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(UgurFilm())
        registerExtractorAPI(MailRu())
        registerExtractorAPI(Odnoklassniki())
    }
}