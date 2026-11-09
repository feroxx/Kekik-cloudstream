// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal1595.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 150L
    override var sequentialMainPageScrollDelay = 150L

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/yabanci-dizi-izle"        to "Yeni Diziler",
        "${mainUrl}/film-izle"                 to "Yeni Filmler",
        "${mainUrl}/trendler"                  to "Trendler",
        "${mainUrl}/kesfet"                    to "Keşfet",
        "${mainUrl}/platform-bolumler/netflix" to "Netflix",
        "${mainUrl}/platform-bolumler/prime"   to "Amazon Prime",
        "${mainUrl}/platform-bolumler/max"     to "Max",
        "${mainUrl}/platform-bolumler/disneyplus" to "Disney+",
        "${mainUrl}/platform-bolumler/exxen"   to "Exxen",
        "${mainUrl}/platform-bolumler/apple-tv" to "Apple TV+",
        "${mainUrl}/platform-bolumler/tabii"   to "Tabii",
        "${mainUrl}/platform-bolumler/gain"    to "GAİN",
        "${mainUrl}/platform-bolumler/tod"     to "TOD (beIN)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        } else {
            request.data
        }

        val document = app.get(
            url, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document

        val home = mutableListOf<SearchResponse>()

        // 1. Yeni Next.js arayüzündeki kartlar (a.tc)
        val cards = document.select("a.tc")
        cards.forEach { element ->
            element.toCardSearchResult()?.let { res ->
                if (home.none { it.url == res.url }) {
                    home.add(res)
                }
            }
        }

        // 2. Fallback: Diğer kart ve liste elemanları
        if (home.isEmpty()) {
            document.select("a[href*='/series/'], a[href*='/movies/']").forEach { element ->
                element.toCardSearchResult()?.let { res ->
                    if (home.none { it.url == res.url }) {
                        home.add(res)
                    }
                }
            }
        }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toCardSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/series/") && !href.contains("/movies/") && !href.contains("/bolum/")) return null

        val title = this.selectFirst("span.tt")?.text()?.trim()
            ?: this.selectFirst("span.ft1")?.text()?.trim()
            ?: this.selectFirst("span.tn")?.text()?.trim()
            ?: this.attr("aria-label").substringBefore(",").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val imgEl = this.selectFirst("img")
        val posterUrl = fixUrlNull(imgEl?.attr("src")?.ifEmpty { null } ?: imgEl?.attr("data-src"))

        return when {
            href.contains("/series/") -> {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
            }
            href.contains("/movies/") -> {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
            }
            href.contains("/bolum/") -> {
                val seriesUrl = href.substringBeforeLast("-").replace("/bolum/", "/series/")
                newTvSeriesSearchResponse(title, seriesUrl, TvType.TvSeries) { this.posterUrl = posterUrl }
            }
            else -> null
        }
    }

    private fun SearchItem.toPostSearchResult(): SearchResponse {
        val title     = this.title
        val href      = if (this.slug.startsWith("http")) this.slug else "${mainUrl}/${this.slug}"
        val posterUrl = this.poster

        return if (this.type.equals("series", ignoreCase = true) || href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val jsonPayload = jacksonObjectMapper().writeValueAsString(
                mapOf("action" to "searchContent", "params" to listOf(query))
            )
            val responseRaw = app.post(
                "${mainUrl}/api/mj",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to "${mainUrl}/",
                    "Origin" to mainUrl
                ),
                requestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
            )

            val mapper = jacksonObjectMapper()
            val rootNode = mapper.readTree(responseRaw.text)
            val resultArrayNode = rootNode.get("result")

            if (resultArrayNode == null || !resultArrayNode.isArray) {
                return emptyList()
            }

            val searchItems: List<SearchItem> = mapper.readValue(resultArrayNode.traverse())
            searchItems.map { it.toPostSearchResult() }
        } catch (e: Exception) {
            Log.e("DiziPal", "Arama Hatası: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor, headers = getHeaders(mainUrl)).document
        val html = document.html()

        val isSeries = url.contains("/series/")
        val schemaType = if (isSeries) "TVSeries" else "Movie"
        val jsonLd = extractJsonLd(html, schemaType)

        val title = jsonLd?.optString("name")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("div.flex h2")?.text()?.trim()
            ?: document.selectFirst("span.tt")?.text()?.trim()
            ?: return null

        val poster = jsonLd?.optString("image")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("div.hero img")?.attr("src")
            ?: document.selectFirst("div.fr img")?.attr("src")
            ?: document.selectFirst("div.page-top img[alt]")?.attr("src")

        val plot = jsonLd?.optString("description")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("div.summary p")?.text()?.trim()
            ?: document.selectFirst("p.plot")?.text()?.trim()

        val year = jsonLd?.optString("datePublished")?.take(4)?.toIntOrNull()
            ?: document.selectXpath("//div[text()='Yıl']//following-sibling::div").text().trim().toIntOrNull()
            ?: Regex("""\b(19\d\d|20\d\d)\b""").find(html)?.groupValues?.get(1)?.toIntOrNull()

        if (isSeries) {
            val episodes = mutableListOf<Episode>()

            val containsSeason = jsonLd?.opt("containsSeason")
            if (containsSeason is JSONArray) {
                for (sIdx in 0 until containsSeason.length()) {
                    val seasonObj = containsSeason.optJSONObject(sIdx) ?: continue
                    val sNum = seasonObj.optInt("seasonNumber", sIdx + 1)
                    val epArr = seasonObj.optJSONArray("episode") ?: continue
                    for (eIdx in 0 until epArr.length()) {
                        val epObj = epArr.optJSONObject(eIdx) ?: continue
                        val epUrl = epObj.optString("url")
                        val epName = epObj.optString("name", "${eIdx + 1}. Bölüm")
                        val epNum = epObj.optInt("episodeNumber", eIdx + 1)
                        if (epUrl.isNotBlank()) {
                            episodes.add(
                                newEpisode(epUrl) {
                                    this.name = epName
                                    this.season = sNum
                                    this.episode = epNum
                                    this.description = epObj.optString("description").takeIf { it.isNotBlank() }
                                }
                            )
                        }
                    }
                }
            } else if (containsSeason is JSONObject) {
                val sNum = containsSeason.optInt("seasonNumber", 1)
                val epArr = containsSeason.optJSONArray("episode")
                if (epArr != null) {
                    for (eIdx in 0 until epArr.length()) {
                        val epObj = epArr.optJSONObject(eIdx) ?: continue
                        val epUrl = epObj.optString("url")
                        val epName = epObj.optString("name", "${eIdx + 1}. Bölüm")
                        val epNum = epObj.optInt("episodeNumber", eIdx + 1)
                        if (epUrl.isNotBlank()) {
                            episodes.add(
                                newEpisode(epUrl) {
                                    this.name = epName
                                    this.season = sNum
                                    this.episode = epNum
                                    this.description = epObj.optString("description").takeIf { it.isNotBlank() }
                                }
                            )
                        }
                    }
                }
            }

            // Fallback: DOM üzerindeki bölüm linkleri
            if (episodes.isEmpty()) {
                document.select("a[href*='/bolum/']").forEach { link ->
                    val epHref = fixUrlNull(link.attr("href")) ?: return@forEach
                    val text = link.text().trim()
                    val sNum = Regex("""(\d+)\.\s*Sezon""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""(\d+)x\d+""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    val eNum = Regex("""(\d+)\.\s*Bölüm""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""\d+x(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    val epName = link.selectFirst("h2, span.tn, span.tt")?.text()?.trim() ?: text.ifEmpty { "Bölüm" }

                    if (episodes.none { it.data == epHref }) {
                        episodes.add(
                            newEpisode(epHref) {
                                this.name = epName
                                this.season = sNum
                                this.episode = eNum
                            }
                        )
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = plot
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = plot
            }
        }
    }

    private fun extractJsonLd(html: String, targetType: String): JSONObject? {
        val typePattern = "\"@type\":\"$targetType\""
        val pos = html.indexOf(typePattern)
        if (pos == -1) return null

        var start = pos
        while (start > 0 && html[start] != '{') {
            start--
        }

        var braceCount = 0
        var end = start
        for (i in start until html.length) {
            if (html[i] == '{') braceCount++
            else if (html[i] == '}') {
                braceCount--
                if (braceCount == 0) {
                    end = i + 1
                    break
                }
            }
        }

        return try {
            JSONObject(html.substring(start, end))
        } catch (e: Exception) {
            null
        }
    }

    // 2. LOAD LINKS: Şifre çözme ve Iframe yakalama işlemi
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DiziPal", "--> loadLinks ÇAĞRILDI. Gelen URL: $data")
        val response = app.get(data, interceptor = interceptor, headers = getHeaders(mainUrl))
        val html = response.text

        // 1. Yeni Next.js / React RSC içinden şifreli verileri çek
        val ctMatch   = Regex("""\\*"ciphertext\\*"\s*:\s*\\*"([^\\"]+)\\*"""").find(html)?.groupValues?.get(1)
        val ivMatch   = Regex("""\\*"iv\\*"\s*:\s*\\*"([^\\"]+)\\*"""").find(html)?.groupValues?.get(1)
        val saltMatch = Regex("""\\*"salt\\*"\s*:\s*\\*"([^\\"]+)\\*"""").find(html)?.groupValues?.get(1)
        val keyMatch  = Regex("""\\*"k\\*"\s*:\s*\\*"([^\\"]+)\\*"""").find(html)?.groupValues?.get(1)

        val defaultPassphrase = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"
        val passphrase = keyMatch ?: defaultPassphrase

        var iframeUrl = if (ctMatch != null && ivMatch != null && saltMatch != null) {
            Log.d("DiziPal", "--> Şifreli veri bulundu, decrypt işlemine geçiliyor...")
            decryptDizipalParams(ctMatch, ivMatch, saltMatch, passphrase)
        } else {
            // Fallback: Eski DOM veya doğrudan iframe ara
            val encryptedText = response.document.selectFirst("div[data-rm-k=true]")?.text() ?: ""
            if (encryptedText.isNotEmpty()) {
                decryptDizipalData(encryptedText)
            } else {
                Log.w("DiziPal", "--> DİKKAT: Şifreli veri DOM'da YOK! Fallback iframe aranıyor...")
                response.document.selectFirst("iframe")?.attr("src") ?: ""
            }
        }

        Log.d("DiziPal", "--> Elde edilen Ham Iframe URL: $iframeUrl")

        if (iframeUrl.isNotEmpty()) {
            if (iframeUrl.startsWith("//")) {
                iframeUrl = "https:$iframeUrl"
            } else if (iframeUrl.startsWith("://")) {
                iframeUrl = "https$iframeUrl"
            } else if (!iframeUrl.startsWith("http")) {
                iframeUrl = "https://$iframeUrl"
            }
            Log.d("DiziPal", "--> Extractor'a gönderilen Final URL: $iframeUrl")

            DizipalPlayer().getUrl(
                url = iframeUrl,
                referer = data,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        } else {
            Log.e("DiziPal", "--> HATA: iframeUrl tamamen BOŞ. Video linki bulunamadı!")
        }
        return true
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Hex string çift uzunlukta olmalıdır" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun decryptDizipalParams(ctHexOrB64: String, ivHex: String, saltHex: String, passphrase: String): String {
        return try {
            val salt = saltHex.decodeHex()
            val iv = ivHex.decodeHex()
            val ciphertext = android.util.Base64.decode(ctHexOrB64, android.util.Base64.DEFAULT)

            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec = javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
            val secretKey = factory.generateSecret(spec)
            val secret = javax.crypto.spec.SecretKeySpec(secretKey.encoded, "AES")

            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secret, javax.crypto.spec.IvParameterSpec(iv))

            val decryptedBytes = cipher.doFinal(ciphertext)
            var finalUrl = String(decryptedBytes, Charsets.UTF_8).replace("\\/", "/")

            if (finalUrl.startsWith("//")) {
                finalUrl = "https:$finalUrl"
            } else if (finalUrl.startsWith("://")) {
                finalUrl = "https$finalUrl"
            } else if (!finalUrl.startsWith("http")) {
                finalUrl = "https://$finalUrl"
            }
            finalUrl
        } catch (e: Exception) {
            Log.e("DiziPal", "--> Decryption Hatası: ${e.message}")
            ""
        }
    }

    private fun decryptDizipalData(rawJsonText: String): String {
        return try {
            val passphrase = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"

            val ctMatch = """"ciphertext"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1) 
                ?: return "".also { Log.e("DiziPal", "--> HATA: Regex 'ciphertext' değerini bulamadı!") }

            val ivMatch = """"iv"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1) 
                ?: return "".also { Log.e("DiziPal", "--> HATA: Regex 'iv' değerini bulamadı!") }

            val saltMatch = """"salt"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1) 
                ?: return "".also { Log.e("DiziPal", "--> HATA: Regex 'salt' değerini bulamadı!") }

            val salt = saltMatch.decodeHex()
            val iv = ivMatch.decodeHex()
            val ciphertext = android.util.Base64.decode(ctMatch, android.util.Base64.DEFAULT)

            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec = javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
            val secretKey = factory.generateSecret(spec)
            val secret = javax.crypto.spec.SecretKeySpec(secretKey.encoded, "AES")

            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secret, javax.crypto.spec.IvParameterSpec(iv))

            val decryptedBytes = cipher.doFinal(ciphertext)
            var finalUrl = String(decryptedBytes, Charsets.UTF_8).replace("\\/", "/")

            if (finalUrl.startsWith("://")) {
                finalUrl = "https$finalUrl"
            } else if (finalUrl.startsWith("//")) {
                finalUrl = "https:$finalUrl"
            } else if (!finalUrl.startsWith("http")) {
                finalUrl = "https://$finalUrl"
            }

            finalUrl
        } catch (e: Exception) {
            Log.e("DiziPal", "--> HATA: Decryption sırasında Exception fırlatıldı! Mesaj: ${e.message}")
            ""
        }
    }

    private fun getHeaders(baseUrl: String): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to baseUrl
        )
    }
}
