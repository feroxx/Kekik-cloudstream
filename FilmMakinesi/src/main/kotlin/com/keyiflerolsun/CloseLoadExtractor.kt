package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class CloseLoad : ExtractorApi() {
    override val name = "CloseLoad"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers2 = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

        try {
            val response = app.get(url, referer = mainUrl, headers = headers2)
            val html = response.text // Jsoup objesi yerine saf HTML metni alıyoruz

            // 1. Gerçek URL'yi Native Olarak Deşifre Et (WebView'a veda ediyoruz)
            val realUrl = decryptNative(html)

            if (!realUrl.isNullOrBlank() && realUrl.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = realUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.P1080.value
                        headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to headers2["User-Agent"]!!
                        )
                    }
                )
            } else {
                Log.e("Kekik_${this.name}", "Real URL native deşifre edilemedi.")
            }

            // 2. Altyazıları JWPlayer JSON bloğundan Regex ile çıkar
            processSubtitles(html, subtitleCallback)

        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Hata: ${e.message}")
        }
    }

    private fun decryptNative(html: String): String? {
        try {
            // Sitenin JS fonksiyonunu barındıran script bloğunu hedefliyoruz.
            val scriptBlockMatch = """<script[^>]*>(.*?atob\(.*?charCodeAt.*?)</script>""".toRegex(RegexOption.DOT_MATCHES_ALL).find(html)
            val scriptContent = scriptBlockMatch?.groupValues?.get(1)

            if (scriptContent.isNullOrBlank()) {
                Log.e("Kekik_${this.name}", "Deşifre Başarısız: Gerekli algoritma bloğu bulunamadı.")
                return null
            }

            // 1. Şifreli diziyi çıkar: ["l9WRZGaIrH", "63bUp7jIZN", ...]
            val arrayMatch = """\(\[((?:"[^"]+",?\s*)+)\]\)""".toRegex().find(scriptContent)
            val parts = arrayMatch?.groupValues?.get(1)?.split(",")?.map {
                // Escape karakterlerini düzeltiyoruz (örn: \/ -> /)
                it.trim().trim('"').replace("\\/", "/")
            }

            // 2. Sitenin yarın bir gün sayıları değiştirmesine karşı, modül değerlerini dinamik çıkarıyoruz (örn: 399756995 % (i + 5))
            val moduloMatch = """(\d+)\s*%\s*\(i\s*\+\s*(\d+)\)""".toRegex().find(scriptContent)
            val magicNum = moduloMatch?.groupValues?.get(1)?.toLongOrNull() ?: 399756995L
            val magicOffset = moduloMatch?.groupValues?.get(2)?.toIntOrNull() ?: 5

            if (parts.isNullOrEmpty()) return null

            // 3. Kotlin Üzerinde Deşifre İşlemi (JS'in tam bir replikası)
            val value = parts.joinToString("")
            var result = String(Base64.decode(value, Base64.NO_WRAP), Charsets.ISO_8859_1)

            result = result.reversed() // Ters çevir

            // ROT13 / Caesar şifrelemesi
            val rot13 = StringBuilder()
            for (c in result) {
                if (c in 'a'..'z') {
                    val shifted = c.code + 13
                    rot13.append(if (shifted > 'z'.code) (shifted - 26).toChar() else shifted.toChar())
                } else if (c in 'A'..'Z') {
                    val shifted = c.code + 13
                    rot13.append(if (shifted > 'Z'.code) (shifted - 26).toChar() else shifted.toChar())
                } else {
                    rot13.append(c)
                }
            }
            result = rot13.toString()

            // Modulo Unmix (Değişken anahtarlı çözme)
            val unmix = StringBuilder()
            for (i in result.indices) {
                var charCode = result[i].code.toLong()
                charCode = (charCode - (magicNum % (i + magicOffset)) + 256) % 256
                unmix.append(charCode.toInt().toChar())
            }

            return unmix.toString()

        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Native Çözümleme Hatası: ${e.message}")
            return null
        }
    }

    private fun processSubtitles(html: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            // JWPlayer setup içindeki tracks: [...] bloğunu al
            val tracksMatch = """tracks\s*:\s*(\[.*?\])""".toRegex(RegexOption.DOT_MATCHES_ALL).find(html)
            tracksMatch?.groupValues?.get(1)?.let { tracksJson ->

                // Her bir altyazı objesini {} bazında ayır
                val trackPattern = """\{[^}]*\}""".toRegex()
                val fileRegex = """"file"\s*:\s*"([^"]+)"""".toRegex()
                val labelRegex = """"label"\s*:\s*"([^"]+)"""".toRegex()

                trackPattern.findAll(tracksJson).forEach { match ->
                    val block = match.value
                    val file = fileRegex.find(block)?.groupValues?.get(1)?.replace("\\/", "/")
                    val label = labelRegex.find(block)?.groupValues?.get(1) ?: "Altyazı"

                    if (!file.isNullOrBlank() && file.startsWith("http")) {
                        subtitleCallback.invoke(SubtitleFile(label, file))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Altyazı Çözümleme Hatası: ${e.message}")
        }
    }
}