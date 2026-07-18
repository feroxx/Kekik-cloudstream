package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Watch2MoviesPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Watch2Movies())
        registerExtractorAPI(W2MExtractor("https://0123movie.space/",     "UpCloud",  context))
        registerExtractorAPI(W2MExtractor("https://player.videasy.net/", "Videasy",  context))
        registerExtractorAPI(W2MExtractor("https://vidsrc.cc/",          "VidSrc",   context))
        registerExtractorAPI(W2MExtractor("https://vidfast.pro/",        "VidFast",  context))
        registerExtractorAPI(W2MExtractor("https://hanatyury.online/",   "UpCloud",  context))
        registerExtractorAPI(W2MExtractor("https://pepepeyo.xyz/",       "Vidmoly",  context))
        registerExtractorAPI(W2MExtractor("https://zizicoi.online/",     "UpCloud",  context))
        registerExtractorAPI(W2MExtractor("https://watch2movies.net/",   "W2M",      context))
    }
}