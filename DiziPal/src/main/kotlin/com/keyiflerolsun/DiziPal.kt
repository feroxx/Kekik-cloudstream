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
            // 1. BÖLÜM SAYFASINI ÇEK (dizipal)
            Log.d("DZP", "loadLinks başladı - data: $data")

            // Ana sayfadan cookie'leri almak için önce bir istek yap
            val mainPageResponse = app.get("https://dizipal1542.com/", headers = getHeaders(mainUrl))

            // Cookie'leri sakla - Set-Cookie header'ından al
            val cookieString = mainPageResponse.headers.values("Set-Cookie")
                .takeIf { it.isNotEmpty() }
                ?.joinToString("; ") { cookie ->
                    cookie.substringBefore(";")
                } ?: ""

            Log.d("DZP", "Cookie: $cookieString")

            // Bölüm sayfasını çek (gerekli header ve cookie'lerle)
            val episodeHeaders = getHeaders(mainUrl).toMutableMap()
            if (cookieString.isNotEmpty()) {
                episodeHeaders["Cookie"] = cookieString
            }

            val episodeDocument = app.get(data, headers = episodeHeaders).document

            // 2. İFRAME URL'SİNİ BUL
            val iframeUrl = episodeDocument.selectFirst("iframe")?.attr("src")
                ?: episodeDocument.selectFirst("div#vast_new iframe")?.attr("src")
                ?: episodeDocument.selectFirst(".player iframe")?.attr("src")
                ?: run {
                    // JavaScript içinde openPlayer varsa onu da kontrol et
                    val scriptContent = episodeDocument.selectFirst("script:containsData(openPlayer)")?.data()
                    if (scriptContent != null) {
                        Regex("""openPlayer\('([^']+)'""").find(scriptContent)?.groupValues?.get(1)?.let { param ->
                            "https://sn.dplayer82.site/iframe.php?v=$param"
                        }
                    } else null
                } ?: return false

            Log.d("DZP", "iframeUrl: $iframeUrl")

            // 3. İFRAME SAYFASINI ÇEK
            val iframeHeaders = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                "Referer" to data,
                "Origin" to "https://dizipal1542.com"
            )
            if (cookieString.isNotEmpty()) {
                iframeHeaders["Cookie"] = cookieString
            }

            val iframeResponse = app.get(iframeUrl, headers = iframeHeaders)
            val iframeHtml = iframeResponse.text
            Log.d("DZP", "iframeHtml length: ${iframeHtml.length}")

            // 4. source2.php PARAMETRESİNİ BUL (openPlayer içindeki v parametresi)
            val source2Param = Regex("""openPlayer\('([^']+)'""").find(iframeHtml)?.groupValues?.get(1)
                ?: Regex("""source2\.php\?v=([^"']+)""").find(iframeHtml)?.groupValues?.get(1)
                ?: return false

            Log.d("DZP", "source2Param: $source2Param")

            // 5. source2.php'YE İSTEK AT
            val source2Url = "https://sn.dplayer82.site/source2.php?v=$source2Param"
            val source2Headers = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                "Referer" to iframeUrl,
                "Origin" to "https://sn.dplayer82.site",
                "X-Requested-With" to "XMLHttpRequest"
            )
            if (cookieString.isNotEmpty()) {
                source2Headers["Cookie"] = cookieString
            }

            val source2Response = app.get(source2Url, headers = source2Headers)

            Log.d("DZP", "source2Response: ${source2Response.text}")

            // 6. JSON'DAN VİDEO VE ALTYAZI LİNKLERİNİ ÇEK
            val json = JSONObject(source2Response.text)

            if (json.getBoolean("state") && !json.getBoolean("expired")) {
                val playlist = json.getJSONArray("playlist")

                // Tüm kaynakları dolaş (birden fazla kalite/versiyon olabilir)
                for (i in 0 until playlist.length()) {
                    val item = playlist.getJSONObject(i)
                    val sources = item.getJSONArray("sources")

                    for (j in 0 until sources.length()) {
                        val source = sources.getJSONObject(j)
                        val videoUrl = source.getString("file")
                        val videoTitle = source.optString("title", "Bilinmeyen")
                        val videoType = source.getString("type")

                        if (videoType == "hls") {
                            // M3U8 linkini callback'e gönder
                            callback.invoke(
                                newExtractorLink(
                                    source = "Dizipal",
                                    name = "$videoTitle - Kaynak ${j+1}",
                                    url = videoUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    headers = mapOf(
                                        "Referer" to "https://sn.dplayer82.site/",
                                        "Origin" to "https://sn.dplayer82.site",
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                    )
                                    quality = when {
                                        videoTitle.contains("1080") -> Qualities.P1080.value
                                        videoTitle.contains("720") -> Qualities.P720.value
                                        videoTitle.contains("480") -> Qualities.P480.value
                                        videoTitle.contains("360") -> Qualities.P360.value
                                        else -> Qualities.Unknown.value
                                    }
                                }
                            )
                        }
                    }
                }

                // 7. ALTYAZILARI ÇEK (openPlayer parametrelerinden)
                val subtitlesMatch = Regex("""\[(.*?)\]""").findAll(iframeHtml).lastOrNull()
                if (subtitlesMatch != null) {
                    Regex("""\[([^\]]+)\]([^,]+)""").findAll(subtitlesMatch.value).forEach { match ->
                        val lang = match.groupValues[1]
                        val url = match.groupValues[2].trim()

                        subtitleCallback.invoke(
                            SubtitleFile(
                                lang = when (lang.lowercase()) {
                                    "türkçe" -> "Türkçe"
                                    "ingilizce" -> "İngilizce"
                                    "english" -> "İngilizce"
                                    "turkish" -> "Türkçe"
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
            Log.e("DZP", "loadLinks hata: ${e.message}")
            e.printStackTrace()
        }

        return false
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

