// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class SetPlay : ExtractorApi() {
    override val name            = "SetPlay"
    override val mainUrl         = "https://setplay.shop"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val iSource = app.get(url, referer = referer).text

        // 1. JS içindeki FirePlayer fonksiyonuna parametre olarak verilen JSON objesini yakala
        val jsonString = Regex("""FirePlayer\([^,]+,\s*(\{.*?\})\s*,\s*(?:true|false)\)""", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(iSource)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Player konfigurasyonu bulunamadı")

        // 2. String'i JSON'a parse et (CloudStream AppUtils kullanarak)
        val json = AppUtils.parseJson<Map<String, Any>>(jsonString)

        // 3. İhtiyacımız olan parametreleri güvenli bir şekilde cast ederek al
        val videoServer = json["videoServer"]?.toString() ?: "1"
        Log.d("Kekik_${this.name}", "videoServer » $videoServer")
        val videoUrl = (json["videoUrl"]?.toString() ?: "").replace("\\/", "/")
        Log.d("Kekik_${this.name}", "videoUrl » $videoUrl")
        val title = json["title"]?.toString() ?: "Bilinmeyen"

        // hostList bir obje (Map) olduğu için ona göre cast ediyoruz
        val hostList = json["hostList"] as? Map<String, List<String>> ?: emptyMap()

        // 4. İlgili server listesinden domaini seç (İlkini alıyoruz, istersen .random() da yapabilirsin)
        val targetDomains = hostList[videoServer]
            ?: throw ErrorLoadingException("Host listesi bulunamadı (Server: $videoServer)")

        val targetDomain = targetDomains.firstOrNull()
            ?: throw ErrorLoadingException("Hedef domain boş")

        // 5. Final M3U8 linkini inşa et
        val m3uLink = "https://$targetDomain$videoUrl"

        Log.d("Kekik_${this.name}", "Setplay Final Link » $m3uLink")

        callback.invoke(
            newExtractorLink(
                source  = this.name,
                name    = "${this.name} - $title",
                url     = m3uLink,
                type    = ExtractorLinkType.M3U8
            ) {
                quality = Qualities.Unknown.value
                headers = mapOf("Referer" to url)
            }
        )
    }
}
