// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONArray
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal1542.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
// ! CloudFlare bypass
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 150L  // ? 0.15 saniye
    override var sequentialMainPageScrollDelay = 150L  // ? 0.15 saniye

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
        "${mainUrl}/yabanci-dizi-izle"                 to "Yeni Diziler",
        "${mainUrl}/hd-film-izle"                                  to "Yeni Filmler",
        "${mainUrl}/kanal/netflix"                                 to "Netflix",
        "${mainUrl}/kanal/exxen"                                   to "Exxen",
        "${mainUrl}/kanal/max"                                     to "Max",
        "${mainUrl}/kanal/disney"                                  to "Disney+",
        "${mainUrl}/kanal/amazon"                                  to "Amazon Prime",
        "${mainUrl}/kanal/tod"                                     to "TOD (beIN)",
        "${mainUrl}/kanal/tabii"                                   to "Tabii",
        "${mainUrl}/kanal/hulu"                                    to "Hulu",
        //"${mainUrl}/diziler?kelime=&durum=&tur=26&type=&siralama=" to "Anime",
        //"${mainUrl}/diziler?kelime=&durum=&tur=5&type=&siralama="  to "Bilimkurgu Dizileri",
        //"${mainUrl}/tur/bilimkurgu"                                to "Bilimkurgu Filmleri",
        //"${mainUrl}/diziler?kelime=&durum=&tur=11&type=&siralama=" to "Komedi Dizileri",
        //"${mainUrl}/tur/komedi"                                    to "Komedi Filmleri",
        //"${mainUrl}/diziler?kelime=&durum=&tur=4&type=&siralama="  to "Belgesel Dizileri",
        //"${mainUrl}/tur/belgesel"                                  to "Belgesel Filmleri",
        //"${mainUrl}/diziler?kelime=&durum=&tur=25&type=&siralama=" to "Erotik Diziler",
        //"${mainUrl}/tur/erotik"                                    to "Erotik Filmler",
        // "${mainUrl}/diziler?kelime=&durum=&tur=1&type=&siralama="  to "Aile",
        // "${mainUrl}/diziler?kelime=&durum=&tur=2&type=&siralama="  to "Aksiyon",
        // "${mainUrl}/diziler?kelime=&durum=&tur=3&type=&siralama="  to "Animasyon",
        // "${mainUrl}/diziler?kelime=&durum=&tur=4&type=&siralama="  to "Belgesel",
        // "${mainUrl}/diziler?kelime=&durum=&tur=6&type=&siralama="  to "Biyografi",
        // "${mainUrl}/diziler?kelime=&durum=&tur=7&type=&siralama="  to "Dram",
        // "${mainUrl}/diziler?kelime=&durum=&tur=8&type=&siralama="  to "Fantastik",
        // "${mainUrl}/diziler?kelime=&durum=&tur=9&type=&siralama="  to "Gerilim",
        // "${mainUrl}/diziler?kelime=&durum=&tur=10&type=&siralama=" to "Gizem",
        // "${mainUrl}/diziler?kelime=&durum=&tur=12&type=&siralama=" to "Korku",
        // "${mainUrl}/diziler?kelime=&durum=&tur=13&type=&siralama=" to "Macera",
        // "${mainUrl}/diziler?kelime=&durum=&tur=14&type=&siralama=" to "Müzik",
        // "${mainUrl}/diziler?kelime=&durum=&tur=16&type=&siralama=" to "Romantik",
        // "${mainUrl}/diziler?kelime=&durum=&tur=17&type=&siralama=" to "Savaş",
        // "${mainUrl}/diziler?kelime=&durum=&tur=24&type=&siralama=" to "Yerli",
        // "${mainUrl}/diziler?kelime=&durum=&tur=18&type=&siralama=" to "Spor",
        // "${mainUrl}/diziler?kelime=&durum=&tur=19&type=&siralama=" to "Suç",
        // "${mainUrl}/diziler?kelime=&durum=&tur=20&type=&siralama=" to "Tarih",
        // "${mainUrl}/diziler?kelime=&durum=&tur=21&type=&siralama=" to "Western",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            request.data, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document
        //Log.d("DZP", "Ana sayfa HTML içeriği:\n${document.outerHtml()}")
        val home     = if (request.data.contains("/yabanci-dizi-izle") || request.data.contains("/hd-film-izle")) {
            document.select("div.new-added-list div.bg-\\[\\#22232a\\]").mapNotNull { it.sonBolumler() }
        } else {
            document.select("div.new-added-list div.bg-\\[\\#22232a\\]").mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home, hasNext=true)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name      = this.selectFirst("img")?.attr("alt") ?: return null
        val episode   = this.selectFirst("div.episode")?.text()?.trim()
            ?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: ""
        val title     = if (episode.isNotEmpty()) "$name $episode" else name

        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href.substringBefore("/sezon"), TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title     = this.selectFirst("img")?.attr("alt") ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun SearchItem.toPostSearchResult(): SearchResponse {
        val title     = this.title
        val href      = "${mainUrl}${this.url}"
        val posterUrl = this.poster

        return if (this.type == "series") {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val responseRaw = app.post(
            "${mainUrl}/api/search-autocomplete",
            headers     = mapOf(
                "Accept"           to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer     = "${mainUrl}/",
            data        = mapOf(
                "query" to query
            )
        )

        val searchItemsMap = jacksonObjectMapper().readValue<Map<String, SearchItem>>(responseRaw.text)

        val searchResponses = mutableListOf<SearchResponse>()

        for ((_, searchItem) in searchItemsMap) {
            searchResponses.add(searchItem.toPostSearchResult())
        }

        return searchResponses
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor, headers = getHeaders(mainUrl)).document

        val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val year        = document.selectXpath("//div[text()='Yıl']//following-sibling::div").text().trim().toIntOrNull()
        val description = document.selectFirst("div.summary p")?.text()?.trim()
        val tags        = document.selectXpath("//div[text()='Kategoriler']//following-sibling::div").text().trim().split(" ").map { it.trim() }
        val duration    = Regex("(\\d+)").find(document.selectXpath("//div[text()='Süre']//following-sibling::div").text())?.value?.toIntOrNull()

        if (url.contains("/dizi/")) {
            val title       = document.selectFirst("div.flex h2")?.text() ?: return null
            val episodes    = document.select("ul.episodes").mapNotNull { val epName    = it.selectFirst("div.flex title")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val epEpisode = it.selectFirst("div.episode")?.text()?.trim()?.split(" ")?.get(2)?.replace(".", "")?.toIntOrNull()
                val epSeason  = it.selectFirst("div.episode")?.text()?.trim()?.split(" ")?.get(0)?.replace(".", "")?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = epName
                    this.episode = epEpisode
                    this.season  = epSeason
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.duration  = duration
            }
        } else { 
            val title = document.selectXpath("//div[@class='g-title'][2]/div").text().trim()

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.duration  = duration
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            Log.d("DZP", "loadLinks başladı - Hedef URL: $data")

            // 1. Ana sayfadan cookie'leri al (Anti-bot mekanizmaları için oturum tutmak önemli)
            val mainPageResponse = app.get(mainUrl, headers = getHeaders(mainUrl))
            val cookieString = mainPageResponse.headers.values("Set-Cookie")
                .takeIf { it.isNotEmpty() }
                ?.joinToString("; ") { cookie -> cookie.substringBefore(";") } ?: ""

            val episodeHeaders = getHeaders(mainUrl).toMutableMap()
            if (cookieString.isNotEmpty()) episodeHeaders["Cookie"] = cookieString

            // 2. Bölüm sayfasını çek
            val episodeDocument = app.get(data, headers = episodeHeaders).document

            // 3. Şifreli wpsaData'yı bul (Artık iframe aramak yok, doğrudan data çekiyoruz)
            val wpsaDataElement = episodeDocument.selectFirst("#wpsaData")?.attr("value")
            if (wpsaDataElement.isNullOrEmpty()) {
                Log.e("DZP", "Kritik Hata: #wpsaData input'u bulunamadı.")
                return false
            }

            // 4. JSON'ı Parse Et
            val payloadJson = JSONObject(wpsaDataElement)
            val ciphertext = payloadJson.getString("ciphertext")
            val ivHex = payloadJson.getString("iv")
            val saltStr = payloadJson.getString("salt")

            // 5. Şifreyi Çöz -> Sonuç bize doğrudan iframe URL'sini verecek
            // Regex ile başındaki ve sonundaki fazladan tırnakları vs. temizliyoruz.
            val iframeUrl = decryptPayload(ciphertext, ivHex, saltStr).trim().removeSurrounding("\"")

            if (iframeUrl.isEmpty() || !iframeUrl.startsWith("http")) {
                Log.e("DZP", "Şifre çözme işlemi başarısız veya beklenen URL formatında değil. Çözülen Veri: $iframeUrl")
                return false
            }
            Log.d("DZP", "Başarıyla Çözülen Iframe URL: $iframeUrl")

            // 6. İFRAME SAYFASINI ÇEK
            val iframeHeaders = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                "Referer" to data,
                "Origin" to mainUrl
            )
            if (cookieString.isNotEmpty()) iframeHeaders["Cookie"] = cookieString

            val iframeResponse = app.get(iframeUrl, headers = iframeHeaders)
            val iframeHtml = iframeResponse.text

            // 7. source2.php PARAMETRESİNİ BUL (openPlayer içindeki v parametresi)
            val source2Param = Regex("""openPlayer\('([^']+)'""").find(iframeHtml)?.groupValues?.get(1)
                ?: Regex("""source2\.php\?v=([^"']+)""").find(iframeHtml)?.groupValues?.get(1)
                ?: return false

            Log.d("DZP", "source2Param: $source2Param")

            // 8. source2.php'YE İSTEK AT
            val source2Url = "https://sn.dplayer82.site/source2.php?v=$source2Param"
            val source2Headers = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                "Referer" to iframeUrl,
                "Origin" to "https://sn.dplayer82.site",
                "X-Requested-With" to "XMLHttpRequest"
            )
            if (cookieString.isNotEmpty()) source2Headers["Cookie"] = cookieString

            val source2Response = app.get(source2Url, headers = source2Headers)
            val json = JSONObject(source2Response.text)

            // 9. JSON'DAN VİDEO VE ALTYAZI LİNKLERİNİ ÇEK
            if (json.getBoolean("state") && !json.getBoolean("expired")) {
                val playlist = json.getJSONArray("playlist")

                for (i in 0 until playlist.length()) {
                    val item = playlist.getJSONObject(i)
                    val sources = item.getJSONArray("sources")

                    for (j in 0 until sources.length()) {
                        val source = sources.getJSONObject(j)
                        val videoUrl = source.getString("file")
                        val videoTitle = source.optString("title", "Bilinmeyen")
                        val videoType = source.getString("type")

                        if (videoType == "hls") {
                            callback.invoke(
                                newExtractorLink(
                                    source = "Dizipal",
                                    name = "$videoTitle - Kaynak ${j+1}",
                                    url = videoUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    referer = "https://sn.dplayer82.site/"
                                    quality = when {
                                        videoTitle.contains("1080") -> Qualities.P1080.value
                                        videoTitle.contains("720") -> Qualities.P720.value
                                        videoTitle.contains("480") -> Qualities.P480.value
                                        videoTitle.contains("360") -> Qualities.P360.value
                                        else -> Qualities.Unknown.value
                                    }}
                            )
                        }
                    }
                }

                // 10. ALTYAZILARI ÇEK
                val subtitlesMatch = Regex("""\[(.*?)\]""").findAll(iframeHtml).lastOrNull()
                if (subtitlesMatch != null) {
                    Regex("""\[([^\]]+)\]([^,]+)""").findAll(subtitlesMatch.value).forEach { match ->
                        val lang = match.groupValues[1]
                        val url = match.groupValues[2].trim()

                        subtitleCallback.invoke(
                            SubtitleFile(
                                lang = when (lang.lowercase()) {
                                    "türkçe", "turkish" -> "Türkçe"
                                    "ingilizce", "english" -> "İngilizce"
                                    else -> lang
                                },
                                url = if (url.startsWith("http")) url else "https:$url"
                            )
                        )
                    }
                }
                return true
            }

        } catch (e: Exception) {
            Log.e("DZP", "loadLinks çöktü: ${e.stackTraceToString()}")
        }

        return false
    }

    /**
     * Senior Dokunuşu: Kriptografi Katmanı
     */
    private fun decryptPayload(ciphertextB64: String, ivHex: String, saltStr: String): String {
        try {
            // 1. Yeni Devasa Parola
            val secret = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"

            // 2. IV Hex formatında, ByteArray'e çevir
            val ivBytes = ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

            // 3. KRİTİK: JS kodunda Salt Hex değil, Utf8.parse() ile alınıyor.
            // Bu yüzden JSON'daki salt string'inin doğrudan byte'larını alıyoruz.
            val saltBytes = saltStr.toByteArray(Charsets.UTF_8)

            // 4. PBKDF2 Yapılandırması (Birebir JS karşılığı)
            val iterationCount = 999 // JS'deki 0x3e7
            val keyLength = 256 // AES-256 için

            // JS'de CryptoJS.algo.SHA512 kullanılmış
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec = PBEKeySpec(secret.toCharArray(), saltBytes, iterationCount, keyLength)
            val tmp = factory.generateSecret(spec)
            val secretKeySpec = SecretKeySpec(tmp.encoded, "AES")

            // 5. AES Şifre Çözme (Decryption)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, IvParameterSpec(ivBytes))

            val decodedCiphertext = Base64.getDecoder().decode(ciphertextB64)
            val decryptedBytes = cipher.doFinal(decodedCiphertext)

            return String(decryptedBytes, Charsets.UTF_8)

        } catch (e: Exception) {
            Log.e("DZP", "Decryption İşlemi Patladı: ${e.message}")
            e.printStackTrace()
            return ""
        }
    }

    // --- JSON Ayrıştırma Yardımcıları ---

    private suspend fun parsePlaylistObject(obj: JSONObject, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        val playlist = obj.getJSONArray("playlist")
        for (i in 0 until playlist.length()) {
            val item = playlist.getJSONObject(i)
            val sources = item.optJSONArray("sources") ?: continue

            for (j in 0 until sources.length()) {
                val source = sources.getJSONObject(j)
                val videoUrl = source.getString("file")
                val videoTitle = source.optString("title", "Bilinmeyen Kalite")

                callback.invoke(
                    newExtractorLink(
                        source = "Dizipal (Decrypted)",
                        name = videoTitle,
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                    referer = mainUrl
                    quality = Qualities.Unknown.value
                        }
                )
            }
        }
        // Eğer altyazı objesi (tracks vb.) varsa burada subtitleCallback ile yakalanabilir.
    }

    private suspend fun parseJsonArrayForLinks(array: JSONArray, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        for (i in 0 until array.length()) {
            val source = array.getJSONObject(i)
            val fileUrl = source.optString("file")
            val label = source.optString("label", "Otomatik")

            if (fileUrl.isNotEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        source = "Dizipal (Decrypted)",
                        name = label,
                        url = fileUrl,
                        type = if (fileUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                    {
                        referer = mainUrl
                        quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }

    // Header'ları oluşturan yardımcı fonksiyon - Map<String, String> döndürür
    private fun getHeaders(baseUrl: String): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to baseUrl
        )
    }
}

