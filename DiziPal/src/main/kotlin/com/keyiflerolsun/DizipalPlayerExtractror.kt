package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

// DiziPal sınıfının dışına (altına) ekle
class DizipalPlayerExtractor : ExtractorApi() {
    override var name = "DPlayer"
    override var mainUrl = "dplayer82.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. Iframe.php'nin içeriğini (Senin attığın HTML'i) çekiyoruz
        val response = app.get(url, referer = referer).text

        // 2. JS içindeki window.openPlayer argümanlarını bul (Uzun şifreli metin)
        val openPlayerRegex = """window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""".toRegex()
        val playlistId = openPlayerRegex.find(response)?.groupValues?.get(1)

        if (playlistId != null) {
            // 3. Iframe içindeki JS kodundan gördüğümüz kadarıyla API şu şekilde:
            // "source2.php?v=" + playList
            // URL'nin başındaki domaini yakalayalım (four.dplayer82.site)
            val domainRegex = """https?://[^/]+""".toRegex()
            val domain = domainRegex.find(url)?.value ?: "https://four.dplayer82.site"

            val apiUrl = "$domain/source2.php?v=$playlistId"

            // 4. source2.php'ye istek at, burası muhtemelen JSON dönüyor
            val apiResponse = app.get(apiUrl, referer = url).text

            android.util.Log.d("DiziPal", "--> DPlayer Extractor: source2.php yanıtı: $apiResponse")

            // DİKKAT: Buradaki apiResponse'un yapısını (JSON mu değil mi, içinde m3u8 nasıl duruyor)
            // tam bilemiyorum. Ancak log'a bastırdığımız için çalıştırdığında ne döndüğünü göreceğiz.
            // Örnek bir JSON işleme (Eğer { "playlist": [ {"sources": [{"file": "..."}] } ] } gibi bir şey dönüyorsa):

            try {
                // Not: Cloudstream'in AppUtils'i içindeki Mapper'ı kullanarak veya Regex ile m3u8'i çekmeliyiz.
                // Eğer direkt m3u8 linkini bulursak:
                val m3u8Regex = """https?://[\\w./\-]+\.m3u8[^\s"']*""".toRegex()
                val m3u8Links = m3u8Regex.findAll(apiResponse).map { it.value }.toList()

                m3u8Links.forEach { link ->
                    // Kaçış karakterlerini temizle (\/)
                    val cleanLink = link.replace("\\/", "/")

                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            cleanLink,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer ?: ""
                            Qualities.Unknown.value
                        }
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("DiziPal", "--> DPlayer Extractor Hata: ${e.message}")
            }
        }
    }
}