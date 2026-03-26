// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import org.json.JSONObject
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class DiziPalOriginal : MainAPI() {
    override var mainUrl              = "https://dizipal2038.com"
    override var name                 = "DiziPalOriginal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    // override var sequentialMainPageDelay       = 250L // ? 0.25 saniye
    // override var sequentialMainPageScrollDelay = 250L // ? 0.25 saniye

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler/bolumler"                  to "Son Bölümler",
        "${mainUrl}/diziler"                                       to "Yeni Diziler",
        "${mainUrl}/filmler"                                       to "Yeni Filmler",
        "${mainUrl}/platform/netflix"                              to "Netflix",
        "${mainUrl}/platform/exxen"                                to "Exxen",
        "${mainUrl}/platform/blutv"                                to "BluTV",
        "${mainUrl}/platform/disney-plus"                          to "Disney+",
        "${mainUrl}/platform/prime-video"                          to "Amazon Prime",
        "${mainUrl}/platform/tabii"                                to "Tabii",
        "${mainUrl}/platform/gain"                                 to "Gain",
        "${mainUrl}/platform/max"                                  to "Max",
        //"${mainUrl}/diziler?kelime=&durum=&tur=26&type=&siralama=" to "Anime",
        //"${mainUrl}/diziler?kelime=&durum=&tur=5&type=&siralama="  to "Bilimkurgu Dizileri",
        "${mainUrl}/kategori/bilimkurgu"                                to "Bilimkurgu Filmleri",
        //"${mainUrl}/diziler?kelime=&durum=&tur=11&type=&siralama=" to "Komedi Dizileri",
        "${mainUrl}/kategori/komedi"                                    to "Komedi Filmleri",
        //"${mainUrl}/diziler?kelime=&durum=&tur=4&type=&siralama="  to "Belgesel Dizileri",
        "${mainUrl}/kategori/belgesel"                                  to "Belgesel Filmleri",
        //"${mainUrl}/diziler?kelime=&durum=&tur=25&type=&siralama=" to "Erotik Diziler",
        //"${mainUrl}/kategori/erotik"                                    to "Erotik Filmler",
        // "${mainUrl}/diziler?kelime=&durum=&tur=1&type=&siralama="  to "Aile",            // ! Fazla kategori olduğu için geç yükleniyor..
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
            request.data,
        ).document
        val home     = if (request.data.contains("/diziler/bolumler")) {
            document.select("div.episodes-list-grid > div").mapNotNull { it.sonBolumler() }
        } else {
            document.select("ul.content-grid > li").mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home, hasNext=false)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name      = this.selectFirst("div.episode-meta .ep-title")?.text() ?: return null
        val episode   = this.selectFirst("div.episode-meta .ep-info")?.text()?.trim()?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: return null
        val title     = "$name $episode"

        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href.substringBefore("/sezon"), TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title     = this.selectFirst("div.card-info h3")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun DizipalSearchResult.toPostSearchResult(): SearchResponse? {
        // Zorunlu alanların kontrolü (Early return)
        val title = this.title ?: return null
        val href  = this.url ?: return null

        return if (this.type.equals("Dizi", ignoreCase = true)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = this@toPostSearchResult.poster
                this.year      = this@toPostSearchResult.year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = this@toPostSearchResult.poster
                this.year      = this@toPostSearchResult.year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Arama URL'sini doğrudan parametre ile oluşturuyoruz
        val searchUrl = "$mainUrl/ajax-search?q=$query"

        val responseRaw = app.get(
            searchUrl,
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/"
        )

        // JSON'ı yeni data class yapımızla parse ediyoruz
        val jsonResponse = AppUtils.parseJson<DizipalSearchData>(responseRaw.text)

        val searchResponses = mutableListOf<SearchResponse>()

        // Eğer results null dönerse veya boşsa güvenli şekilde geçiyoruz
        jsonResponse.results?.forEach { item ->
            val title = item.title ?: return@forEach
            val url = item.url ?: return@forEach
            val poster = item.poster

            // Dizi mi Film mi olduğunu API'den gelen "type" alanına göre belirliyoruz
            if (item.type == "Dizi") {
                searchResponses.add(
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = item.year
                    }
                )
            } else {
                searchResponses.add(
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = item.year
                    }
                )
            }
        }

        return searchResponses
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
    // 1. BÖLÜM LİNKİ YÖNLENDİRMESİ
    if (url.contains("/bolum/")) {
        val seriesUrl = url.replace("/bolum/", "/dizi/")
            .replace(Regex("-\\d+-sezon.*"), "")
        return load(seriesUrl)
    }

    val document = app.get(url).document

    // Genel Meta Bilgileri
    val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
    
    // .info-row içindeki span yapısından veriyi çekiyoruz
    val year = document.selectFirst("div.info-row:contains(Yıl) span.info-value")?.text()?.trim()?.toIntOrNull()
    val description = document.selectFirst("p.series-description")?.text()?.trim()
    
    // "Kategoriler" altındaki tüm <a> tag'lerini çekip listeye çeviriyoruz
    val tags = document.select("div.info-row:contains(Kategoriler) span.info-value.categories a").map { it.text().trim() }
    
    // HTML'de süre bilgisi mevcut değil, gelirse diye hazırlıklı bırakıyorum:
    // val durationText = document.selectFirst("div.info-row:contains(Süre) span.info-value")?.text()
    // val duration = Regex("(\\d+)").find(durationText ?: "")?.value?.toIntOrNull()
    val duration: Int? = null 

    if (url.contains("/dizi/")) {
        // Yeni DOM yapısında başlık h1 tag'inde class ile tutuluyor
        val title = document.selectFirst("h1.series-title")?.text()?.trim() ?: return null

        val episodes = document.select("div.detail-episode-item-wrap").mapNotNull { wrap ->
            val anchor = wrap.selectFirst("a.detail-episode-item") ?: return@mapNotNull null
            val epHref = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val epName = anchor.selectFirst("div.detail-episode-title")?.text()?.trim() ?: return@mapNotNull null
            
            // Format: "1. Sezon 1. Bölüm" -> Regex ile güvenli parse işlemi
            val subtitle = anchor.selectFirst("div.detail-episode-subtitle")?.text()?.trim() ?: ""
            val match = Regex("""(\d+)\.\s*[Ss]ezon\s*(\d+)\.\s*[Bb]ölüm""").find(subtitle)
            
            val epSeason = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            val epEpisode = match?.groupValues?.getOrNull(2)?.toIntOrNull()

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
        // Film detay sayfası HTML'i elimizde olmadığı için en olası selector'ları fallback ile yazdım.
        // Gerekirse og:title meta tag'inden de çekebilirsin.
        val title = document.selectFirst("h1.series-title, h1.movie-title")?.text()?.trim() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" izle")?.trim() 
            ?: ""

        if (title.isEmpty()) return null

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
        Log.d("DZP", "Oynatılacak Bölüm Linki » $data")

        // Sunucunun bizi bot sanmaması ve iki isteği eşleştirebilmesi için sabit bir User-Agent
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        // 1. AŞAMA: GET isteği atıp hem Token'ı hem de ÇEREZLERİ alıyoruz
        val getResponse = app.get(
            url = data,
            headers = mapOf(
                "User-Agent"    to userAgent,
                "Cache-Control" to "no-cache",
                "Pragma"        to "no-cache"
            )
        )

        val document = getResponse.document
        val configToken = document.selectFirst("#videoContainer")?.attr("data-cfg")?.trim()

        if (configToken.isNullOrEmpty()) {
            Log.e("DZP", "Sayfadan video config token'ı (data-cfg) alınamadı!")
            return false
        }

        // KRİTİK NOKTA: Sunucunun verdiği çerezleri (PHPSESSID, _ct vb.) tek bir string'de birleştiriyoruz
        val cookies = getResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        Log.d("DZP", "Bulunan Token » $configToken")
        Log.d("DZP", "Yakalanan Çerezler » $cookies")

        // 2. AŞAMA: Token'ı API'ye Çerezlerle (Cookies) birlikte POST et
        val configResponseRaw = app.post(
            url = "$mainUrl/ajax-player-config",
            headers = mapOf(
                "User-Agent"       to userAgent,
                "Accept"           to "*/*",
                "Content-Type"     to "application/x-www-form-urlencoded",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin"           to mainUrl,
                "Cookie"           to cookies // Yakaladığımız oturumu buraya basıyoruz
            ),
            referer = data,
            data = mapOf("cfg" to configToken) // '=' işareti burada otomatik eklenir
        ).text

        Log.d("DZP", "API Yanıtı » $configResponseRaw")

        // JSON'dan embed linkini yakala (v parametresi)
        val embedUrlRaw = Regex(""""v"\s*:\s*"([^"]+)"""").find(configResponseRaw)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")

        if (embedUrlRaw.isNullOrEmpty()) {
            Log.e("DZP", "Embed URL config'den alınamadı! Dönen yanıt: $configResponseRaw")
            return false
        }

        val embedUrl = fixUrl(embedUrlRaw)
        Log.d("DZP", "Çözülen Embed URL » $embedUrl")

        // 3. AŞAMA: Embed Sayfasına Git ve JWPlayer Verilerini Ayıkla
        val embedSource = app.get(
            url = embedUrl,
            referer = data,
            headers = mapOf("User-Agent" to userAgent)
        ).text

        val m3u8Match = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+\.m3u8.*?)["']""").find(embedSource)
            ?: Regex("""file\s*:\s*["']([^"']+\.m3u8.*?)["']""").find(embedSource)

        val m3u8Url = m3u8Match?.groupValues?.getOrNull(1)

        if (m3u8Url == null) {
            Log.e("DZP", "Embed kaynağında M3U8 bulunamadı!")
            return false
        }

        Log.d("DZP", "Bulunan M3U8 » $m3u8Url")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Dizipal (Ana Sunucu)",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                referer = embedUrl
                quality = Qualities.Unknown.value
            }
        )

        // 4. AŞAMA: Altyazıları (Tracks) Yakala
        val tracksBlockMatch = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(embedSource)

        tracksBlockMatch?.groupValues?.getOrNull(1)?.let { tracksBlock ->
            val trackItemRegex = Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)

            trackItemRegex.findAll(tracksBlock).forEach { itemMatch ->
                val itemStr = itemMatch.groupValues[1]

                val fileMatch = Regex("""file\s*:\s*["']([^"']+)["']""").find(itemStr)
                val labelMatch = Regex("""label\s*:\s*["']([^"']+)["']""").find(itemStr)

                val fileUrl = fileMatch?.groupValues?.getOrNull(1)
                val label = labelMatch?.groupValues?.getOrNull(1) ?: "Unknown"

                if (fileUrl != null && (fileUrl.endsWith(".vtt") || fileUrl.endsWith(".srt"))) {
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = label,
                            url = fixUrl(fileUrl)
                        )
                    )
                }
            }
        }

        return true
    }
}
