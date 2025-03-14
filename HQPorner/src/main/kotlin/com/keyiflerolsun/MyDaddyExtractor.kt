// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class MyDaddy : ExtractorApi() {
    override val name            = "MyDaddy"
    override val mainUrl         = "https://www.mydaddy.cc"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef   = referer ?: ""
        Log.d("Kekik_${this.name}", "url » $url")

        val document = app.get(url).document.selectFirst("script:containsData(do_pl())")?.toString()
        val jw       = document?.substringAfter("replaceAll")?.substringAfter(",")?.substringBefore(")") ?: ""

        val (one, _, three) = jw.split("+").map { it.trim().removeSurrounding("\"") }
        val first     = document?.let { Regex("""${one}\s*=\s*"(.*?)";""").find(it)?.groupValues?.get(1) }?.removePrefix("//")?.removeSuffix("/") ?:""
        val third     = document?.let { Regex("""${three}\s*=\s*"(.*?)";""").find(it)?.groupValues?.get(1) } ?:""
        val finalurl  = "https://${first}/pubs/${third}"
        val regex     = Regex("""title=\\"(\d+p|4K)""")
        val matches   = regex.findAll(document.toString())

        val qualities = mutableListOf<String>()
        for (match in matches) {
            val quality = match.groupValues[1]

            if (quality == "4K") {
                qualities.add("2160")
            } else {
                qualities.add(quality.dropLast(1))
            }
        }

        for (quality in qualities) {
            callback.invoke(
                ExtractorLink(
                    source  = name,
                    name    = name,
                    url     = "${finalurl}/${quality}.mp4",
                    referer = extRef,
                    quality = getQualityFromName(quality)
                )
            )
        }
    }
}