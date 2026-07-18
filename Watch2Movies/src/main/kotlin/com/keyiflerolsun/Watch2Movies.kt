// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.Jsoup

class Watch2Movies : MainAPI() {
    override var mainUrl              = "https://movies2watch.watch"
    override var name                 = "Watch2Movies"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/genre/action?page="           to "Action",
        "${mainUrl}/genre/action-adventure?page=" to "Action & Adventure",
        "${mainUrl}/genre/adventure?page="        to "Adventure",
        "${mainUrl}/genre/animation?page="        to "Animation",
        "${mainUrl}/genre/biography?page="        to "Biography",
        "${mainUrl}/genre/comedy?page="           to "Comedy",
        "${mainUrl}/genre/crime?page="            to "Crime",
        "${mainUrl}/genre/documentary?page="      to "Documentary",
        "${mainUrl}/genre/drama?page="            to "Drama",
        "${mainUrl}/genre/family?page="           to "Family",
        "${mainUrl}/genre/fantasy?page="          to "Fantasy",
        "${mainUrl}/genre/history?page="          to "History",
        "${mainUrl}/genre/horror?page="           to "Horror",
        "${mainUrl}/genre/kids?page="             to "Kids",
        "${mainUrl}/genre/music?page="            to "Music",
        "${mainUrl}/genre/mystery?page="          to "Mystery",
        "${mainUrl}/genre/news?page="             to "News",
        "${mainUrl}/genre/reality?page="          to "Reality",
        "${mainUrl}/genre/romance?page="          to "Romance",
        "${mainUrl}/genre/sci-fi-fantasy?page="   to "Sci-Fi & Fantasy",
        "${mainUrl}/genre/science-fiction?page="  to "Science Fiction",
        "${mainUrl}/genre/soap?page="             to "Soap",
        "${mainUrl}/genre/talk?page="             to "Talk",
        "${mainUrl}/genre/thriller?page="         to "Thriller",
        "${mainUrl}/genre/tv-movie?page="         to "TV Movie",
        "${mainUrl}/genre/war?page="              to "War",
        "${mainUrl}/genre/war-politics?page="     to "War & Politics",
        "${mainUrl}/genre/western?page="          to "Western",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home     = document.select("div.flw-item").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("h3 a")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return if (href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/search/${query}").document

        return document.select("div.flw-item").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title           = document.selectFirst("div.dp-i-content h2 a")?.text()?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description     = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val year            = document.select("div.row-line")
            .firstOrNull { it.text().contains("Released:") }
            ?.text()
            ?.substringAfter("Released:")
            ?.trim()
            ?.split("-")?.firstOrNull()
            ?.toIntOrNull()

        val tags            = document.select("div.row-line a[href*='/genre/']").map { it.text() }
        val duration = document.select("div.row-line")
            .firstOrNull { it.text().contains("Duration:") }
            ?.text()
            ?.substringAfter("Duration:")
            ?.replace("min", "")
            ?.trim()
            ?.toIntOrNull()
        val recommendations = document.select("div.flw-item").mapNotNull { it.toRecommendationResult() }
        val actors          = document.select("div.row-line a[href*='/cast/']").map { Actor(it.text()) }
        val trailer         = document.selectFirst("iframe#iframe-trailer")?.attr("data-src")

        if (url.contains("/series/")) {
            val scriptContent = document.select("script").map { it.data() }.firstOrNull { it.contains("current_url") }
            val currentUrl = scriptContent?.let { Regex("""current_url\s*=\s*['\"]([^'\"]+)['\"];""").find(it)?.groupValues?.get(1) }
            
            val episodes = mutableListOf<Episode>()
            if (currentUrl != null) {
                val epHtml = app.get(currentUrl, referer = url).text
                val epDoc = Jsoup.parse(epHtml)
                epDoc.select("a.eps-item").forEach { element ->
                    val epName = element.text().trim()
                    val epHref = fixUrlNull(element.attr("href")) ?: return@forEach
                    val match = Regex("""(\d+)-(\d+)/?$""").find(epHref)
                    val epSeason = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val epEpisode = match?.groupValues?.get(2)?.toIntOrNull() ?: 1

                    episodes.add(newEpisode(epHref) {
                        this.name = epName
                        this.season = epSeason
                        this.episode = epEpisode
                    })
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.plot            = description
                this.year            = year
                this.tags            = tags
                this.duration        = duration
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.plot            = description
            this.year            = year
            this.tags            = tags
            this.duration        = duration
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title     = this.selectFirst("h3 a")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return if (href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("W2M", "loadLinks data » $data")
        val document = app.get(data).document
        val scriptContent = document.select("script").map { it.data() }.firstOrNull { it.contains("pl_url") }
        val plUrl = scriptContent?.let { Regex("""pl_url\s*=\s*['\"]([^'\"]+)['\"];""").find(it)?.groupValues?.get(1) } ?: return false

        Log.d("W2M", "plUrl » $plUrl")
        val srvHtml = app.get(plUrl, referer = data).text
        val srvDoc = Jsoup.parse(srvHtml)

        var linksFound = false
        srvDoc.select("a.sv-item").forEach { element ->
            val srvUrl = element.attr("data-id")
            Log.d("W2M", "srvUrl: $srvUrl")

            if (srvUrl.isNotEmpty()) {
                loadExtractor(srvUrl, "$mainUrl/", subtitleCallback, callback)
                linksFound = true
            }
        }

        return linksFound
    }
}