package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DizipalPlayer : ExtractorApi() {
    override var name = "DizipalPlayer"
    override var mainUrl = "dplayer82.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("DiziPal_Extractor", "--> 1. Extractor Tetiklendi. Gelen URL: $url")

        val response = app.get(url, referer = referer).text
        val openPlayerRegex = """window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""".toRegex()
        val playlistId = openPlayerRegex.find(response)?.groupValues?.get(1)

        Log.d("DiziPal_Extractor", "--> 2. Bulunan PlaylistID: $playlistId")

        if (playlistId != null) {
            val domainRegex = """https?://[^/]+""".toRegex()
            val domain = domainRegex.find(url)?.value ?: "https://dplayer82.site"
            val apiUrl = "$domain/source2.php?v=$playlistId"

            Log.d("DiziPal_Extractor", "--> 3. API'ye İstek Atılıyor: $apiUrl")

            val apiResponse = app.get(apiUrl, referer = url).text

            try {
                val fileRegex = """"file"\s*:\s*"([^"]+)"""".toRegex()
                val fileMatches = fileRegex.findAll(apiResponse)

                var matchCount = 0
                fileMatches.forEach { matchResult ->
                    matchCount++
                    // trim() hayat kurtarır, gizli boşlukları yok ederiz
                    var fileUrl = matchResult.groupValues[1].replace("\\/", "/").trim()

                    Log.d("DiziPal_Extractor", "--> 4. Regex Eşleşmesi [$matchCount] Ham fileUrl: $fileUrl")

                    // Kesin Protokol Doğrulaması (Safe check)
                    if (fileUrl.startsWith("//")) {
                        fileUrl = "https:$fileUrl"
                    } else if (!fileUrl.startsWith("http")) {
                        fileUrl = "https://$fileUrl"
                    }

                    if (fileUrl.contains("m.php")) {
                        fileUrl = fileUrl.replace("m.php", "master.m3u8")
                    }

                    Log.d("DiziPal_Extractor", "--> 5. Cloudstream'e Hazırlanan Final fileUrl: $fileUrl")

                    // Sadece video dosyalarını Cloudstream'e beslediğimizden emin oluyoruz
                    if (fileUrl.contains(".m3u8") || fileUrl.contains(".mp4")) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "DPlayer (Auto)",
                                url = fileUrl,
                                type = if (fileUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                headers = mapOf("Origin" to domain, "Referer" to (referer ?: url))
                                Qualities.Unknown.value
                            }
                        )
                    } else {
                        Log.w("DiziPal_Extractor", "--> 6. UYARI: Bu link video formatında değil, atlanıyor: $fileUrl")
                    }
                }
            } catch (e: Exception) {
                Log.e("DiziPal_Extractor", "--> DPlayer Extractor Hata: ${e.message}")
            }
        }
    }
}