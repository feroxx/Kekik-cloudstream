package com.keyiflerolsun

import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class SetPlay : ExtractorApi() {
    override val name            = "SetPlay"
    override val mainUrl         = "https://setplay.shop"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val iSource = app.get(url, referer = referer).text

        val jsonString = Regex("""FirePlayer\([^,]+,\s*(\{.*?\})\s*,\s*(?:true|false)\)""", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(iSource)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Player konfigurasyonu bulunamadı")

        val json = AppUtils.parseJson<Map<String, Any>>(jsonString)

        val videoServer = json["videoServer"]?.toString() ?: "1"
        val videoUrl = (json["videoUrl"]?.toString() ?: "").replace("\\/", "/")

        val uri = Uri.parse(url)
        val partKey = uri.getQueryParameter("partKey") ?: ""
        
        val suffix = when {
            partKey.contains("turkcedublaj", ignoreCase = true) -> "Dublaj"
            partKey.contains("turkcealtyazi", ignoreCase = true) -> "Altyazı"
            partKey.isNotEmpty() -> partKey
            else -> {
                val title = json["title"]?.toString() ?: "Bilinmeyen"
                title.substringAfterLast(".", "Bilinmeyen")
            }
        }

        val m3uLink = "$mainUrl$videoUrl?s=$videoServer"

        Log.d("Kekik_${this.name}", "Setplay Final Link » $m3uLink")

        callback.invoke(
            newExtractorLink(
                source  = this.name,
                name    = "${this.name} - $suffix",
                url     = m3uLink,
                type    = ExtractorLinkType.M3U8
            ) {
                quality = Qualities.Unknown.value
                headers = mapOf("Referer" to url)
            }
        )
    }
}
