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
            val html = response.text 

            // 1. JS Deşifre Algoritmasını Dene
            var realUrl = decryptNative(html)

            // 2. Fallback Mekanizması: Eğer JS şifre çözücü başarısız olursa JSON-LD bloğundaki şifresiz contentUrl'i ara
            if (realUrl.isNullOrBlank()) {
                Log.w("Kekik_${this.name}", "Native deşifre başarısız, Fallback JSON-LD aranıyor...")
                val ldJsonMatch = """"contentUrl"\s*:\s*"([^"]+)"""".toRegex().find(html)
                realUrl = ldJsonMatch?.groupValues?.get(1)?.replace("\\/", "/")
            }

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
                Log.e("Kekik_${this.name}", "Real URL bulunamadı veya deşifre edilemedi.")
            }

            processSubtitles(html, subtitleCallback)

        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Hata: ${e.message}")
        }
    }

    private fun decryptNative(html: String): String? {
        try {
            // JS fonksiyonunu hedefle
            val scriptBlockMatch = """<script[^>]*>(.*?dc_[a-zA-Z0-9_]+\(.*?</script>)""".toRegex(RegexOption.DOT_MATCHES_ALL).find(html)
            val scriptContent = scriptBlockMatch?.groupValues?.get(1)
            Log.d("Flmm", "--> scriptContent: $scriptContent")
            if (scriptContent.isNullOrBlank()) return null

            // 1. Şifreli diziyi çıkar: ["==wlVHJZmh", "Ir+dbbKtHj", ...]
            val arrayMatch = """\(\[((?:"[^"]+",?\s*)+)\]\)""".toRegex().find(scriptContent)
            val parts = arrayMatch?.groupValues?.get(1)?.split(",")?.map {
                it.trim().trim('"').replace("\\/", "/")
            }

            // 2. Dinamik Modulo Çarpanlarını Çıkar
            val moduloMatch = """(\d+)\s*%\s*\(i\s*\+\s*(\d+)\)""".toRegex().find(scriptContent)
            val magicNum = moduloMatch?.groupValues?.get(1)?.toLongOrNull() ?: 399756995L
            val magicOffset = moduloMatch?.groupValues?.get(2)?.toIntOrNull() ?: 5

            if (parts.isNullOrEmpty()) return null

            var result = parts.joinToString("")

            // --- GÜNCEL ALGORİTMA SIRALAMASI --- //

            // ADIM 1: Ters Çevir (Reverse)
            result = result.reversed()

            // ADIM 2: Base64 Decode (Kritik: JS atob simülasyonu için ISO_8859_1 şart)
            result = String(Base64.decode(result, Base64.NO_WRAP), Charsets.ISO_8859_1)

            // ADIM 3: ROT13 / Caesar şifrelemesi
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

            // ADIM 4: Modulo Unmix
            val unmix = StringBuilder()
            for (i in result.indices) {
                var charCode = result[i].code.toLong()
                charCode = (charCode - (magicNum % (i + magicOffset)) + 256) % 256

                // Kotlin 1.5+ Tip güvenliği için Long -> Int -> Char dönüşümü
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
            // JWPlayer setup içindeki tracks: [...] JSON bloğu
            val tracksMatch = """tracks\s*:\s*(\[.*?\])""".toRegex(RegexOption.DOT_MATCHES_ALL).find(html)
            tracksMatch?.groupValues?.get(1)?.let { tracksJson ->
                
                val trackPattern = """\{[^}]*\}""".toRegex()
                val fileRegex = """"file"\s*:\s*"([^"]+)"""".toRegex()
                val labelRegex = """"label"\s*:\s*"([^"]+)"""".toRegex()

                trackPattern.findAll(tracksJson).forEach { match ->
                    val block = match.value
                    val file = fileRegex.find(block)?.groupValues?.get(1)?.replace("\\/", "/")
                    val label = labelRegex.find(block)?.groupValues?.get(1) ?: "Altyazı"

                    // file null değilse ve http ile başlıyorsa fırlat
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
