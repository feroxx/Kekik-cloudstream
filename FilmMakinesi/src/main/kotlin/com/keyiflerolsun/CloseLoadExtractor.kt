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
            // JS fonksiyon bloğunu komple hedefle
            val scriptBlockMatch = """<script[^>]*>(.*?dc_[a-zA-Z0-9_]+\(.*?</script>)""".toRegex(RegexOption.DOT_MATCHES_ALL).find(html)
            val scriptContent = scriptBlockMatch?.groupValues?.get(1) ?: return null

            // 1. Şifreli diziyi çıkar
            val arrayMatch = """\(\[((?:"[^"]+",?\s*)+)\]\)""".toRegex().find(scriptContent)
            val parts = arrayMatch?.groupValues?.get(1)?.split(",")?.map { 
                it.trim().trim('"').replace("\\/", "/") 
            } ?: return null

            // 2. Dinamik Modulo Çarpanlarını Çıkar
            val moduloMatch = """(\d+)\s*%\s*\(i\s*\+\s*(\d+)\)""".toRegex().find(scriptContent)
            val magicNum = moduloMatch?.groupValues?.get(1)?.toLongOrNull() ?: 399756995L
            val magicOffset = moduloMatch?.groupValues?.get(2)?.toIntOrNull() ?: 5

            // JS Fonksiyonunun gövdesini izole et
            val functionBodyMatch = """function\s+dc_[a-zA-Z0-9_]+\s*\([^)]*\)\s*\{([^}]+)\}""".toRegex().find(scriptContent)
            val functionBody = functionBodyMatch?.groupValues?.get(1) ?: scriptContent

            // --- İŞTE SİHİR BURADA: OPERASYON SIRASINI DİNAMİK OKU --- //
            val reverseIdx = functionBody.indexOf(".reverse()")
            val atobIdx = functionBody.indexOf("atob(")
            val rot13Idx = functionBody.indexOf(".replace(/[a-zA-Z]/g")

            // Hangi işlemin JS'de hangi sırada yazıldığını bul ve sırala
            val operations = listOf(
                Pair(reverseIdx, "reverse"),
                Pair(atobIdx, "atob"),
                Pair(rot13Idx, "rot13")
            ).filter { it.first != -1 }.sortedBy { it.first }

            var result = parts.joinToString("")

            // İşlemleri sitenin belirlediği sıraya göre dinamik olarak çalıştır
            for (op in operations) {
                when (op.second) {
                    "reverse" -> {
                        result = result.reversed()
                    }
                    "atob" -> {
                        // JS atob() simülasyonu: UTF-8 yerine ISO_8859_1 korunmalı
                        result = String(Base64.decode(result, Base64.NO_WRAP), Charsets.ISO_8859_1)
                    }
                    "rot13" -> {
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
                    }
                }
            }

            // --- SON ADIM: Modulo Unmix (Daima en sonda çalışır) --- //
            val unmix = StringBuilder()
            for (i in result.indices) {
                val charCode = result[i].code.toLong()
                val decryptedCode = (charCode - (magicNum % (i + magicOffset)) + 256) % 256
                
                // Kotlin 1.5+ Tip güvenliği için Long -> Int -> Char
                unmix.append(decryptedCode.toInt().toChar())
            }

            return unmix.toString()

        } catch (e: Exception) {
            Log.e("Kekik_Extractor", "Native Çözümleme Hatası: ${e.message}")
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
