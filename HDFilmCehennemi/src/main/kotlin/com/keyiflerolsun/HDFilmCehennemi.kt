// ! https://github.com/hexated/cloudstream-extensions-hexated/blob/master/Hdfilmcehennemi/src/main/kotlin/com/hexated/Hdfilmcehennemi.kt

package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.updateUrl
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// TMDB genel (public) API anahtarı — IMDb ID → başlık çözümlemesi için.
private const val TMDB_API_KEY = "c4ffcab48dfaa7b41625ac13d61aec31"
private const val USER_AGENT   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

class HDFilmCehennemi : MainAPI() {
    // ! 12 Ekim kaydı .biz'i canlı sanıyordu; 18 Ekim 2026'da .biz cPanel
    // ! varsayılan sayfası, hdfilmcehennemi7.com satışta. Eklentinin ayrıştırıcısıyla
    // ! uyumlu canlı site .nl (images/list/poster). Yedekler sırayla denenir; cPanel,
    // ! satış sayfası ve WordPress klonu sabitlenmez.
    override var mainUrl              = "https://www.hdfilmcehennemi.nl"
    private val domainCandidates      = listOf(
        "https://www.hdfilmcehennemi.nl",
        "https://hdfilmcehennemi.nl",
        "https://www.hdfilmcehennemi.la",
        "https://hdfilmcehennemi.la",
        "https://www.hdfilmcehennemi.com",
        "https://www.hdfilmcehennemi.com.tr",
        "https://www.hdfilmcehennemi.biz",
        "https://hdfilmcehennemi.biz",
        "https://hdfilmcehennemi7.com",
    )
    private var domainResolved        = false
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    // ! IMDb ID ile doğrudan içerik açma (Anizium ile aynı davranış).
    override val supportedSyncNames   = setOf(SyncIdName.Imdb)

    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 150L  // ? 0.15 saniye
    override var sequentialMainPageScrollDelay = 150L  // ? 0.15 saniye

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val looksLikeHtml = contentType.contains("text/html") || contentType.contains("application/xhtml+xml")
            if (!looksLikeHtml && response.code !in 403..503) return response

            val html = response.peekBody(256 * 1024).string()
            val lower = html.lowercase()
            val challengePage = lower.contains("just a moment") ||
                lower.contains("cf-browser-verification") ||
                lower.contains("checking your browser") ||
                (response.code in 403..503 && lower.contains("cloudflare"))

            if (!challengePage) return response

            // CloudflareKiller yeniden istekte bulunmadan önce ilk gövdeyi kapat.
            response.close()
            return cloudflareKiller.intercept(chain)
        }
    }

    // ! Yollar göreli: domain sonradan çözülse de istek doğru host'a gider.
    override val mainPage = mainPageOf(
        "/load/page/sayfano/home/"                                       to "Yeni Eklenen Filmler",
        //"/load/page/sayfano/categories/nette-ilk-filmler/"               to "Nette İlk Filmler",
        "/load/page/sayfano/home-series/"                                to "Yeni Eklenen Diziler",
        "/load/page/sayfano/categories/tavsiye-filmler-izle3/"           to "Tavsiye Filmler",
        "/load/page/sayfano/imdb7/"                                      to "IMDB 7+ Filmler",
        "/load/page/sayfano/mostCommented/"                              to "En Çok Yorumlananlar",
        "/load/page/sayfano/mostLiked/"                                  to "En Çok Beğenilenler",
        //"${mainUrl}/load/page/sayfano/genres/aile-filmleri-izleyin-6/"             to "Aile Filmleri",
        //"${mainUrl}/load/page/sayfano/genres/aksiyon-filmleri-izleyin-5/"          to "Aksiyon Filmleri",
        //"${mainUrl}/load/page/sayfano/genres/animasyon-filmlerini-izleyin-5/"      to "Animasyon Filmleri",
        //"${mainUrl}/load/page/sayfano/genres/belgesel-filmlerini-izle-1/"          to "Belgesel Filmleri",
        //"${mainUrl}/load/page/sayfano/genres/bilim-kurgu-filmlerini-izleyin-3/"    to "Bilim Kurgu Filmleri",
        //"${mainUrl}/load/page/sayfano/genres/komedi-filmlerini-izleyin-1/"         to "Komedi Filmleri",
        //"${mainUrl}/load/page/sayfano/genres/korku-filmlerini-izle-4/"             to "Korku Filmleri",
        //"${mainUrl}/load/page/sayfano/genres/romantik-filmleri-izle-2/"            to "Romantik Filmleri"
    )

    private suspend fun resolveDomain() {
        if (domainResolved) return
        for (candidate in domainCandidates) {
            try {
                val res = app.get(
                    candidate,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$candidate/"
                    ),
                    interceptor = interceptor,
                    cacheTime = 60
                )
                if (!res.isSuccessful) continue
                val pinned = originOf(res.url) ?: continue
                if (!siteLooksAlive(
                        pinned,
                        res.text,
                        "hdfilmcehennemi",
                        // ! GÜNCELLEME (2026-10-19): Site SPA'ya geçti, yeni aria-label'lar da canlı kabul edilir.
                        listOf("images/list/poster", "alternative-link", "Alternative tab content", "load/page", "post-info", "Player", "section-title")
                    )
                ) continue
                mainUrl = pinned
                domainResolved = true
                Log.d("HDCH", "domain sabitlendi: $mainUrl")
                return
            } catch (e: Exception) {
                Log.d("HDCH", "domain adayi olmadi: $candidate (${e.message})")
            }
        }
        Log.w("HDCH", "hicbir domain ayakta degil, varsayilan kaldi: $mainUrl")
    }

    /** Eski domaindeki kayıtlı içerik/bölüm adresini çözülen host'a taşır. */
    private fun migrateUrl(url: String): String {
        val host = runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        if (!host.contains("hdfilmcehennemi")) return url
        return try {
            updateUrl(url)
        } catch (e: Exception) {
            url
        }
    }

    private fun originOf(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return null
            val scheme = uri.scheme ?: "https"
            val port = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (e: Exception) {
            null
        }
    }

    private fun siteLooksAlive(finalUrl: String, body: String, marker: String, required: List<String>): Boolean {
        val host = runCatching { java.net.URI(finalUrl).host?.lowercase().orEmpty() }.getOrDefault("")
        if (!host.contains(marker)) return false
        val lower = body.lowercase()
        val dead = listOf(
            "yasal nedenlerle",
            "unavailable for legal reasons",
            "domain for sale",
            "buy this domain",
            "is for sale",
            "godaddy",
            "default web site page",
            "cgi-sys/defaultwebpage",
            "wp-content",
            "wp-login.php",
        )
        if (dead.any { lower.contains(it) }) return false
        if (body.length < 300) return false
        return required.any { lower.contains(it.lowercase()) }
    }

    private fun absoluteUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return mainUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        resolveDomain()
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val url = absoluteUrl(request.data.replace("sayfano", page.toString()))
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "Accept" to "*/*", "X-Requested-With" to "fetch"
        )
        val doc = app.get(url, headers = headers, referer = mainUrl, interceptor = interceptor)
        val home: List<SearchResponse>?
        if (!doc.toString().contains("Sayfa Bulunamadı")) {
            val aa: HDFC = objectMapper.readValue(doc.toString())
            val document = Jsoup.parse(aa.html)

            home = document.select("a").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, home)
        }
        return newHomePageResponse(request.name, emptyList())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title")
        val href = fixUrlNull(this.attr("href"))?.let { migrateUrl(it) } ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        resolveDomain()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val response      = app.get(
            "${mainUrl}/search?q=$encoded",
            headers = mapOf(
                "User-Agent"       to USER_AGENT,
                "X-Requested-With" to "fetch",
                "Content-Type"     to "application/json"
            ),
            referer = "${mainUrl}/",
            interceptor = interceptor
        ).parsedSafe<Results>() ?: return emptyList()
        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val title = document.selectFirst("h4.title")?.text()?.trim()
                ?: document.selectFirst(".title")?.text()?.trim()
                ?: return@forEach
            val anchor = document.selectFirst("a.search-result") ?: document.selectFirst("a")
            val href   = fixUrlNull(anchor?.attr("href"))?.let { migrateUrl(it) } ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src"))
                ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))

            // ! ÖNEMLİ: Diziler /dizi/... adresinde. Önceden TÜM sonuçlar
            // ! TvType.Movie dönüyordu; uygulama dizi aramasında bu kayıtları
            // ! "film" sanıp eliyordu (The Mentalist / Supernatural vb.).
            val isSeries = href.contains("/dizi/")
            if (isSeries) {
                searchResults.add(
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl?.replace("/thumb/", "/list/")
                    }
                )
            } else {
                searchResults.add(
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl?.replace("/thumb/", "/list/")
                    }
                )
            }
        }

        return searchResults
    }

    // ── IMDb ID → içerik sayfası ─────────────────────────────────────────────
    // Uygulama "video izle" akışında önce getLoadUrl(Imdb, "tt1234567") çağırır.
    // IMDb kimliği TMDB üzerinden başlığa çevrilir, site aramasıyla eşleştirilir
    // ve doğrudan içerik sayfası döndürülür. Böylece başlık yazım farklarından
    // (Türkçe/İngilizce ad) kaynaklanan "veri gelmiyor" durumu engellenir.
    private data class TmdbItem(
        @JsonProperty("title")          val title: String?         = null,
        @JsonProperty("name")           val name: String?          = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name")  val originalName: String?  = null
    )

    private data class TmdbFind(
        @JsonProperty("movie_results") val movieResults: List<TmdbItem>? = null,
        @JsonProperty("tv_results")    val tvResults: List<TmdbItem>?    = null
    )

    private suspend fun tmdbTitlesByImdb(imdbId: String): List<String> {
        val body = app.get(
            "https://api.themoviedb.org/3/find/$imdbId" +
                "?api_key=$TMDB_API_KEY&external_source=imdb_id&language=tr-TR"
        ).text
        val parsed  = AppUtils.tryParseJson<TmdbFind>(body) ?: return emptyList()
        val entries = parsed.movieResults.orEmpty() + parsed.tvResults.orEmpty()
        return entries
            .flatMap { listOfNotNull(it.title, it.originalTitle, it.name, it.originalName) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun normalizeTitle(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun titleScore(a: String, b: String): Double {
        val x = normalizeTitle(a)
        val y = normalizeTitle(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.85
        return 0.0
    }

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        if (name != SyncIdName.Imdb || id.isBlank()) return null
        val titles = try { tmdbTitlesByImdb(id) } catch (e: Exception) { emptyList() }
        for (title in titles) {
            val results = try { search(title) } catch (e: Exception) { emptyList() }
            val best = results
                .map { it to titleScore(title, it.name) }
                .filter { it.second >= 0.7 }
                .maxByOrNull { it.second } ?: continue
            return best.first.url
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveDomain()
        val pageUrl = migrateUrl(url)
        val document = app.get(
            pageUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            ),
            referer = "$mainUrl/",
            interceptor = interceptor
        ).document

        // ! GÜNCELLEME (2026-10-19): Site SPA'ya geçti, başlık ve diğer alanlar
        // ! hem eski `.post-info-*` hem de yeni `[aria-label]` ve JSON-LD
        // ! kaynaklarından okunur.
        val title = (
            document.selectFirst("h1.section-title")?.text()
                ?: document.selectFirst("h1")?.text()
                ?: document.selectFirst("meta[property='og:title']")?.attr("content")
                ?: document.selectFirst("script[type='application/ld+json']")?.data()?.let { json ->
                    Regex(""""name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                }
            )?.substringBefore(" izle")?.substringBefore(" Filminin Bilgileri")?.trim()?.takeIf { it.isNotBlank() } ?: return null

        val poster = fixUrlNull(
            document.selectFirst("aside.post-info-poster img.lazyload")?.attr("data-src")
                ?: document.selectFirst("aside.post-info-poster img")?.attr("data-src")
                ?: document.selectFirst(".post-info-poster img")?.attr("data-src")
                ?: document.selectFirst(".post-info-poster img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img.img-fluid")?.attr("src")
        ) ?: fixUrlNull(document.selectFirst("img[src*='/uploads/poster/']")?.attr("src"))

        val tags = (
            document.select("div.post-info-genres a").takeIf { it.isNotEmpty() }
                ?: document.select(".post-info-genres a").takeIf { it.isNotEmpty() }
                ?: document.select("div.mb-0.lh-lg div:nth-child(5) a").takeIf { it.isNotEmpty() }
                ?: document.select("a[href*='/genre/'], a[href*='/tur/']")
            ).map { it.text().trim() }.filter { it.isNotBlank() }

        var year: Int? = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
            ?: document.selectFirst("div.release a")?.text()?.trim()?.toIntOrNull()
        if (year == null) {
            val ldYear = document.selectFirst("script[type='application/ld+json']")?.data()?.let { json ->
                Regex(""""datePublished"\s*:\s*"(\d{4})""").find(json)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex(""""datePublished"\s*:\s*"[^"]*(\d{4})""").find(json)?.groupValues?.get(1)?.toIntOrNull()
            }
            year = ldYear
        }

        // ! TvSeries tespiti: önce eski div.seasons, sonra yeni nav#seasonsTabs / aria-label, sonra URL ve bölüm linkleri
        val hasSeasons = document.select("div.seasons").isNotEmpty()
            || document.select("nav#seasonsTabs").isNotEmpty()
            || document.select("[aria-label*='Season'], [aria-label*='Sezon']").isNotEmpty()
            || document.select("div.seasons-tab-content a, div#seasonsTabs-tabContent div.card-list-item, a[href*='/bolum-']").isNotEmpty()
        val tvType = when {
            hasSeasons -> TvType.TvSeries
            pageUrl.contains("/dizi/") || pageUrl.contains("/sezon-") -> TvType.TvSeries
            else -> TvType.Movie
        }

        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
            ?: document.selectFirst(".post-info-content > p")?.text()?.trim()
            ?: document.selectFirst("article.text-white > p")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: document.selectFirst("script[type='application/ld+json']")?.data()?.let { json ->
                Regex(""""description"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            }

        val actors = mutableListOf<Actor>()
        // Eski: post-info-cast
        document.select("div.post-info-cast a").mapNotNull {
            val actorName = it.selectFirst("strong")?.text()?.trim()?.takeIf { n -> n.isNotEmpty() }
                ?: it.selectFirst("span")?.text()?.trim()?.takeIf { n -> n.isNotEmpty() }
                ?: it.text().trim().takeIf { n -> n.isNotEmpty() }
                ?: return@mapNotNull null
            Actor(actorName, it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src") ?: "")
        }.let { actors.addAll(it) }
        if (actors.isEmpty()) {
            // Yeni: .story-item ve chip
            document.select(".story-item").forEach {
                val img = fixUrlNull(it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src"))
                val name = it.selectFirst("div.story-item-title")?.text()?.trim() ?: it.selectFirst("strong")?.text()?.trim() ?: ""
                if (name.isNotBlank()) actors.add(Actor(name, img ?: ""))
            }
        }
        if (actors.isEmpty()) {
            document.select("div.mb-0.lh-lg div:last-child a.chip, .cast a").mapNotNull {
                val n = it.text().trim().takeIf { t -> t.isNotBlank() } ?: return@mapNotNull null
                Actor(n, it.selectFirst("img")?.attr("src") ?: "")
            }.let { actors.addAll(it) }
        }

        val recommendations = (
            document.select("div.section-slider-container div.slider-slide").takeIf { it.isNotEmpty() }
                ?: document.select("div.swiper-wrapper div.poster.poster-pop").takeIf { it.isNotEmpty() }
                ?: document.select("div.section-slider-container div.slider-slide, .poster-container, .glide__slide")
            ).mapNotNull {
                val recName = it.selectFirst("a")?.attr("title")?.trim()
                    ?: it.selectFirst("h2")?.text()?.trim()
                    ?: it.selectFirst("h2.title")?.text()?.trim()
                    ?: return@mapNotNull null
                if (recName.isBlank()) return@mapNotNull null
                val recHref = fixUrlNull(it.selectFirst("a")?.attr("href"))?.let { h -> migrateUrl(h) } ?: return@mapNotNull null
                val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
                    ?: fixUrlNull(it.selectFirst("img")?.attr("src"))
                newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                    this.posterUrl = recPosterUrl
                }
            }

        return if (tvType == TvType.TvSeries) {
            val trailer = (
                document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/", "")
                    ?: document.selectFirst("button.btn.btn-fragman.btn-danger")?.attr("data-trailer")
                    ?: document.selectFirst("button[data-trailer]")?.attr("data-trailer")
                    ?: document.selectFirst("[data-trailer]")?.attr("data-trailer")
                )?.let { if (it.isNotBlank()) "https://www.youtube.com/watch?v=$it".replace("/embed/", "/watch?v=") else null }

            // Bölüm listesi: önce eski, sonra yeni hexated .de, sonra genel href regex
            // ! GÜNCELLEME: Hem .nl eski (seasons-tab-content) hem .de yeni (card-list-item) hem SPA fallback desteklenir.
            val episodes = when {
                document.select("div.seasons-tab-content a").isNotEmpty() -> {
                    document.select("div.seasons-tab-content a").mapNotNull { el ->
                        val epHref = fixUrlNull(el.attr("href"))?.let { h -> migrateUrl(h) } ?: return@mapNotNull null
                        val epName = el.selectFirst("h4")?.text()?.trim() ?: el.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex("""bolum-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                        val epSeason = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex("""sezon-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        newEpisode(epHref) {
                            this.name = epName
                            this.season = epSeason
                            this.episode = epEpisode
                        }
                    }
                }
                document.select("div#seasonsTabs-tabContent div.card-list-item").isNotEmpty() -> {
                    document.select("div#seasonsTabs-tabContent div.card-list-item").mapNotNull { el ->
                        val href = fixUrlNull(el.selectFirst("a")?.attr("href"))?.let { h -> migrateUrl(h) } ?: return@mapNotNull null
                        val name = el.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                        val epNum = name.substringAfter("Sezon ").substringBefore(". Bölüm").toIntOrNull()
                            ?: Regex("""bolum-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                        val season = el.parents().find { p -> p.id().startsWith("seasons-") || p.id() == "seasonsTabs-tabContent" }?.id()?.substringAfter("seasons-")?.toIntOrNull()
                            ?: Regex("""sezon-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        newEpisode(href) {
                            this.name = name
                            this.season = season
                            this.episode = epNum
                        }
                    }
                }
                else -> {
                    // Genel fallback: sayfadaki tüm bölüm linkleri (SPA)
                    document.select("a[href*='/bolum-']").mapNotNull { el ->
                        val epHref = fixUrlNull(el.attr("href"))?.let { h -> migrateUrl(h) } ?: return@mapNotNull null
                        // Sadece dizi bölüm linkleri (sezon/bolum içeren)
                        if (!epHref.contains("/bolum-")) return@mapNotNull null
                        val epName = el.selectFirst("h4")?.text()?.trim()
                            ?: el.selectFirst("h3")?.text()?.trim()
                            ?: el.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex("""bolum-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                        val epSeason = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex("""sezon-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        newEpisode(epHref) {
                            this.name = epName
                            this.season = epSeason
                            this.episode = epEpisode
                        }
                    }.distinctBy { it.data }
                }
            }.distinctBy { it.data }

            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val trailer = (
                document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/", "")
                    ?: document.selectFirst("button.btn.btn-fragman.btn-danger")?.attr("data-trailer")
                    ?: document.selectFirst("[data-trailer]")?.attr("data-trailer")
                )?.let { if (it.isNotBlank()) "https://www.youtube.com/watch?v=$it".replace("/embed/", "/watch?v=") else null }
            Log.d("HDCH", "Trailer: $trailer")
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    /**
     * Video kaynakları.
     *
     * 1) Sayfadaki dil sekmesi + kaynak düğmeleri (Close / Rapidrame) okunur.
     * 2) Her düğme için `/video/{id}/` API'sinden oynatıcı iframe'i alınır
     *    (Close → hdfilmcehennemi.mobi/video/embed/..., Rapidrame → /rplayer/...).
     * 3) Oynatıcı sayfaları paralel çözülür: [PlayerDecoder] sayfanın kendi
     *    karartılmış çözücüsünü çalıştırıp jwplayer `sources` değerini alır.
     *    (JSON-LD contentUrl / playmix adresi TUZAKTIR, kullanılmaz.)
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        resolveDomain()
        val requestedUrl = migrateUrl(data)
        val response = app.get(
            requestedUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            ),
            referer = "$mainUrl/",
            interceptor = interceptor,
            timeout = 20L
        )
        if (!response.isSuccessful) return false
        val pageUrl = response.url.ifBlank { requestedUrl }
        val document = response.document

        val buttons = HdfcSources.buttons(document)
        Log.d("HDCH", "kaynak düğmeleri: " + buttons.joinToString(" | ") { "${it.label}=${it.videoId}" })

        val embeds = ArrayList<HdfcSources.Embed>()
        val fromApi = coroutineScope {
            buttons.map { button ->
                async { withTimeoutOrNull(20_000L) { fetchEmbed(button, pageUrl) } }
            }.awaitAll()
        }
        fromApi.filterNotNull().forEach { e -> if (embeds.none { it.url == e.url }) embeds.add(e) }

        // API yanıt vermezse: sayfadaki varsayılan oynatıcı + rapidrame_id'den Rapidrame.
        val language = buttons.firstOrNull()?.language
        for (e in HdfcSources.pageEmbeds(document, pageUrl, originOf(pageUrl) ?: mainUrl)) {
            if (embeds.none { it.url == e.url }) {
                embeds.add(if (language.isNullOrBlank()) e else e.copy(label = "${e.label} · $language"))
            }
        }
        Log.d("HDCH", "oynatıcılar: " + embeds.joinToString(" | ") { "${it.label} -> ${it.url}" })
        if (embeds.isEmpty()) return false

        val resolved = coroutineScope {
            embeds.map { e ->
                async { withTimeoutOrNull(35_000L) { PlayerResolver.resolve(e.url, pageUrl, e.label, interceptor) } }
            }.awaitAll()
        }

        var found = false
        val seenSubtitles = HashSet<String>()
        val seenStreams = HashSet<String>()
        for (r in resolved) {
            if (r == null || !seenStreams.add(r.link.url)) continue
            r.subtitles.forEach { if (seenSubtitles.add(it.url)) subtitleCallback(it) }
            callback(r.link)
            found = true
        }
        return found
    }

    /** `/video/{id}/` → oynatıcı iframe adresi. */
    private suspend fun fetchEmbed(button: HdfcSources.Button, pageUrl: String): HdfcSources.Embed? {
        val body = try {
            app.get(
                "${originOf(pageUrl) ?: mainUrl}/video/${button.videoId}/",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "X-Requested-With" to "fetch",
                    "Accept" to "*/*",
                    "Content-Type" to "application/json"
                ),
                referer = pageUrl,
                interceptor = interceptor,
                timeout = 15L
            ).text
        } catch (e: Exception) {
            Log.w("HDCH", "video API hatası (${button.videoId}): ${e.message}")
            return null
        }
        val url = HdfcSources.embedFromApi(body, pageUrl)
        if (url == null) {
            Log.w("HDCH", "video API iframe yok (${button.videoId}): ${body.take(200)}")
            return null
        }
        return HdfcSources.Embed(button.label, url)
    }

    data class Results(
        @JsonProperty("results") val results: List<String> = arrayListOf()
    )
    data class HDFC(
        @JsonProperty("html") val html: String,
        @JsonProperty("meta") val meta: Meta
    )

    data class Meta(
        @JsonProperty("title") val title: String,
        @JsonProperty("canonical") val canonical: String,
        @JsonProperty("keywords") val keywords: Boolean
    )

}
