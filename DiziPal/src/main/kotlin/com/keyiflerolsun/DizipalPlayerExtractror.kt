package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.newExtractorLink

class DizipalPlayer : ExtractorApi() {
    override var name = "DizipalPlayer"
    override var mainUrl = "dplayer82.site"
    override val requiresReferer = true

    private val cloudflareKiller by lazy { CloudflareKiller() }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val defaultHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Referer" to (referer ?: "https://dizipal1595.com/"),
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
        )

        val response = app.get(
            url,
            referer = referer,
            headers = defaultHeaders,
            interceptor = cloudflareKiller
        ).text

        val domainRegex = """https?://[^/]+""".toRegex()
        val domain = domainRegex.find(url)?.value ?: "https://dplayer82.site"
        val subUrls = mutableSetOf<String>()

        // 1. Yeni NP_CONFIG formatı kontrolü
        val npConfigMatch = Regex("""window\.NP_CONFIG\s*=\s*(\{.*?\});\s*window\.""", RegexOption.DOT_MATCHES_ALL).find(response)
            ?: Regex("""window\.NP_CONFIG\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(response)

        if (npConfigMatch != null) {
            try {
                val jsonStr = npConfigMatch.groupValues[1]
                val npJson = org.json.JSONObject(jsonStr)

                // Gate token hesaplama (404 hatasını engelleyen PoW doğrulaması)
                val gateObj = npJson.optJSONObject("gate")
                val gateN = gateObj?.optString("n")
                val gateB = gateObj?.optInt("b", 12) ?: 12

                val token = if (!gateN.isNullOrEmpty()) {
                    calculateGateToken(response, gateN, gateB)
                } else {
                    ""
                }
                Log.d("DiziPal", "--> Gate token hesaplandı: '$token' (gateN=$gateN, gateB=$gateB)")

                // Altyazıları ayıkla
                val subtitlesArr = npJson.optJSONArray("subtitles")
                if (subtitlesArr != null) {
                    for (i in 0 until subtitlesArr.length()) {
                        val subObj = subtitlesArr.optJSONObject(i) ?: continue
                        var subUrl = subObj.optString("url")
                        val subLabel = subObj.optString("label", "Altyazı")
                        if (subUrl.isNotEmpty()) {
                            if (token.isNotEmpty()) {
                                val sep = if (subUrl.contains("?")) "&" else "?"
                                subUrl += "${sep}p=$token"
                            }
                            if (subUrl !in subUrls) {
                                subUrls.add(subUrl)
                                subtitleCallback.invoke(
                                    newSubtitleFile(
                                        lang = subLabel,
                                        url = fixUrl(subUrl)
                                    ) {
                                        this.headers = mapOf(
                                            "Origin" to domain,
                                            "Referer" to url,
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // Video kaynaklarını ayıkla
                val sourcesArr = npJson.optJSONArray("sources")
                if (sourcesArr != null) {
                    for (i in 0 until sourcesArr.length()) {
                        val srcObj = sourcesArr.optJSONObject(i) ?: continue
                        var masterUrl = srcObj.optString("master")
                        val title = srcObj.optString("title", "DPlayer")
                        if (masterUrl.isNotEmpty()) {
                            if (masterUrl.startsWith("//")) {
                                masterUrl = "https:$masterUrl"
                            } else if (!masterUrl.startsWith("http")) {
                                masterUrl = "$domain/$masterUrl".replace("//", "/").replace("https:/", "https://")
                            }

                            if (token.isNotEmpty()) {
                                val sep = if (masterUrl.contains("?")) "&" else "?"
                                masterUrl += "${sep}p=$token"
                            }

                            Log.d("DiziPal", "--> Çözülen Master Video URL: $masterUrl")

                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name ($title)",
                                    url = masterUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = url
                                    this.headers = mapOf(
                                        "Origin" to domain,
                                        "Referer" to url,
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                                    )
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DiziPal", "--> NP_CONFIG parse hatası: ${e.message}")
            }
        }

        // 2. Eski window.openPlayer formatı kontrolü
        val openPlayerRegex = """window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""".toRegex()

        Regex(""""file":"((?:\\\\\"|[^"])+)","label":"((?:\\\\\"|[^"])+)"""").findAll(response).forEach {
            val (subUrlExt, subLangExt) = it.destructured

            val subUrl = subUrlExt.replace("\\/", "/").replace("\\u0026", "&").replace("\\", "")
            val subLang = subLangExt.replace("\\u0131", "ı").replace("\\u0130", "İ").replace("\\u00fc", "ü").replace("\\u00e7", "ç").replace("\\u011f", "ğ").replace("\\u015f", "ş")

            if (subUrl in subUrls) return@forEach
            subUrls.add(subUrl)

            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = subLang,
                    url = fixUrl(subUrl)
                ) {
                    headers = mapOf(
                        "Referer" to url,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    )
                }
            )
        }

        val playlistId = openPlayerRegex.find(response)?.groupValues?.get(1)
        Log.d("DiziPal", "--> playlistId: $playlistId")
        if (playlistId != null) {
            val domainRegex = """https?://[^/]+""".toRegex()
            val domain = domainRegex.find(url)?.value ?: "https://dplayer82.site"
            val apiUrl = "$domain/source2.php?v=$playlistId"
            Log.d("DiziPal", "--> apiUrl: $apiUrl")
            val apiResponse = app.get(
                apiUrl,
                referer = url,
                headers = defaultHeaders,
                interceptor = cloudflareKiller
            ).text

            try {
                val fileRegex = """"file"\s*:\s*"([^"]+)"""".toRegex()
                val fileMatches = fileRegex.findAll(apiResponse)

                fileMatches.forEach { matchResult ->
                    var fileUrl = matchResult.groupValues[1].replace("\\/", "/")

                    if (fileUrl.startsWith("//")) {
                        fileUrl = "https:$fileUrl"
                    } else if (!fileUrl.startsWith("http")) {
                        fileUrl = "https://$fileUrl"
                    }
                    Log.d("DiziPal", "--> fileUrl: $fileUrl")

                    if (fileUrl.contains("m.php")) {
                        fileUrl = fileUrl.replace("m.php", "master.m3u8")
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "DPlayer (Auto)",
                            url = fileUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.headers = mapOf(
                                "Origin" to domain,
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                            )
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("DiziPal", "--> DPlayer Extractor Hata: ${e.message}")
            }
        }
    }

    private fun calculateGateToken(html: String, gateN: String, gateB: Int): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val gateMatch = Regex("""window\.NP_GATE\s*=\s*function\s*\(([^)]+)\)\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL).find(html)

            var radix = 10
            var wordIdx = 0
            var mask: Int? = null
            var shift = 32 - gateB
            var prefix = "$gateN:"
            var suffix = ""

            if (gateMatch != null) {
                val params = gateMatch.groupValues[1].split(",").map { it.trim() }
                val pN = params.getOrNull(0) ?: "_oh"
                val pHash = params.getOrNull(1) ?: "_cng"
                val body = gateMatch.groupValues[2]

                val saltMatch = Regex("""var\s+([A-Za-z0-9_$]+)\s*=\s*String\.fromCharCode\(([\d,\s]+)\)""").find(body)
                val saltVar = saltMatch?.groupValues?.get(1) ?: ""
                val saltVal = saltMatch?.groupValues?.get(2)?.split(",")
                    ?.mapNotNull { it.trim().toIntOrNull()?.toChar() }
                    ?.joinToString("") ?: ""

                val radixMatch = Regex("""var\s+([A-Za-z0-9_$]+)\s*=\s*[A-Za-z0-9_$]+\.toString\((\d+)\)""").find(body)
                val nonceVar = radixMatch?.groupValues?.get(1) ?: ""
                radix = radixMatch?.groupValues?.get(2)?.toIntOrNull() ?: 10

                val callMatch = Regex(Regex.escape(pHash) + """\(([^)]+)\)\[(\d+)\]""").find(body)
                val exprStr = callMatch?.groupValues?.get(1) ?: "$saltVar+$pN+'.'+$nonceVar"
                wordIdx = callMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0

                mask = Regex("""\]\s*&\s*(\d+)\s*\)\s*===\s*0""").find(body)?.groupValues?.get(1)?.toIntOrNull()
                val shiftMatch = Regex("""\]\s*>>>\s*(\d+)\s*\)\s*===\s*0""").find(body)?.groupValues?.get(1)?.toIntOrNull()
                if (shiftMatch != null) shift = shiftMatch

                val parts = exprStr.split("+").map { it.trim() }
                val preBuilder = StringBuilder()
                val sufBuilder = StringBuilder()
                var seenNonce = false
                for (p in parts) {
                    if (p == nonceVar && nonceVar.isNotEmpty()) {
                        seenNonce = true
                        continue
                    }
                    val resolved = when {
                        p == pN -> gateN
                        p == saltVar && saltVar.isNotEmpty() -> saltVal
                        (p.startsWith("'") && p.endsWith("'")) || (p.startsWith("\"") && p.endsWith("\"")) -> p.substring(1, p.length - 1)
                        else -> ""
                    }
                    if (!seenNonce) preBuilder.append(resolved) else sufBuilder.append(resolved)
                }
                if (seenNonce) {
                    prefix = preBuilder.toString()
                    suffix = sufBuilder.toString()
                }
            }

            val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
            val suffixBytes = suffix.toByteArray(Charsets.UTF_8)
            val byteOffset = (wordIdx.coerceIn(0, 7)) * 4

            for (i in 0 until 10_000_000) {
                val nonce = i.toString(radix)
                md.reset()
                md.update(prefixBytes)
                md.update(nonce.toByteArray(Charsets.UTF_8))
                if (suffixBytes.isNotEmpty()) md.update(suffixBytes)
                val digest = md.digest()
                val word = ((digest[byteOffset].toInt() and 0xFF) shl 24) or
                           ((digest[byteOffset + 1].toInt() and 0xFF) shl 16) or
                           ((digest[byteOffset + 2].toInt() and 0xFF) shl 8) or
                           (digest[byteOffset + 3].toInt() and 0xFF)
                val matched = if (mask != null) (word and mask) == 0 else (word ushr shift) == 0
                if (matched) return nonce
            }
            ""
        } catch (e: Exception) {
            Log.e("DiziPal", "--> Gate token hesaplama hatası: ${e.message}")
            ""
        }
    }
}
