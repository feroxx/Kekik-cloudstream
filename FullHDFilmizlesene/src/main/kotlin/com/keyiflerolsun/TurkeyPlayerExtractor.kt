// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class TurkeyPlayer : ExtractorApi() {
    override val name            = "TurkeyPlayer"
    override val mainUrl         = "https://watch.turkeyplayer.com/"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef = referer ?: ""
        val pageContent = app.get(url, referer = extRef).text
        Log.d("Kekik_${this.name}", "PageContent » $pageContent")

        // ID'yi al
        val id = Regex("""id=(\d+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""data-id=['"]?(\d+)['"]?""").find(pageContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Video ID bulunamadı")

        // md5 hash'i çek
        val hash = Regex("""/m3u8/8/([a-fA-F0-9]{32})/master\.txt""").find(pageContent)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("MD5 hash bulunamadı")

        val masterUrl = "https://watch.turkeyplayer.com/m3u8/8/$hash/master.txt?s=1&id=$id&cache=1"
        Log.d("Kekik_${this.name}", "masterUrl » $masterUrl")

        callback.invoke(
            newExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = videoLink,
                type = ExtractorLinkType.M3U8
            ) {
                quality = Qualities.Unknown.value
                headers = mapOf("Referer" to extRef)
            }
        )
    }
}
