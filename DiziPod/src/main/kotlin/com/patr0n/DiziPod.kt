package com.patr0n

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziPod : MainAPI() {
    override var mainUrl        = "https://dizipod.com"
    override var name           = "DiziPod"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val ajaxUrl = "$mainUrl/wp/wp-admin/admin-ajax.php"

    override val mainPage = mainPageOf(
        "$mainUrl/diziler/page/SAYFA/"                          to "Son Eklenen Diziler",
        "$mainUrl/filmler/page/SAYFA/"                          to "Son Eklenen Filmler",
        "$mainUrl/tur/aksiyon/page/SAYFA/"                      to "Aksiyon",
        "$mainUrl/tur/dram/page/SAYFA/"                         to "Dram",
        "$mainUrl/tur/komedi/page/SAYFA/"                       to "Komedi",
        "$mainUrl/tur/gerilim/page/SAYFA/"                      to "Gerilim",
        "$mainUrl/tur/korku/page/SAYFA/"                        to "Korku",
        "$mainUrl/tur/bilim-kurgu/page/SAYFA/"                  to "Bilim Kurgu",
        "$mainUrl/tur/fantastik/page/SAYFA/"                    to "Fantastik",
        "$mainUrl/tur/animasyon/page/SAYFA/"                    to "Animasyon",
        "$mainUrl/tur/macera/page/SAYFA/"                       to "Macera",
        "$mainUrl/tur/romantik/page/SAYFA/"                     to "Romantik",
        "$mainUrl/tur/belgesel/page/SAYFA/"                     to "Belgesel",
        "$mainUrl/tur/mini-seri/page/SAYFA/"                    to "Mini Seri",
        "$mainUrl/tur/anime/page/SAYFA/"                        to "Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data.replace("/page/SAYFA/", "/")
        } else {
            request.data.replace("SAYFA", "$page")
        }

        val document = app.get(url, cacheTime = 60).document
        val home = document.select("a[href]").mapNotNull { el ->
            val href = el.attr("abs:href")
            if (!isValidContentUrl(href)) return@mapNotNull null
            extractCardFromLink(el)
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun isValidContentUrl(href: String): Boolean {
        val slugRegex = Regex("""^https://dizipod\.com/(diziler|film)/[^/]+/$""")
        return slugRegex.containsMatchIn(href)
    }

    private fun extractCardFromLink(el: Element): SearchResponse? {
        val href = el.attr("abs:href").takeIf { it.isNotBlank() } ?: return null
        if (!isValidContentUrl(href)) return null

        val isDizi = href.contains("/diziler/")
        val isFilm = href.contains("/film/")

        val title = el.attr("title").takeIf { it.isNotBlank() }
            ?: el.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: el.text().trim().takeIf { it.isNotBlank() }
            ?: el.parent()?.selectFirst("[class*='title'], h2, h3, h4")?.text()?.trim()
            ?: return null

        val imgEl = el.selectFirst("img") ?: el.parent()?.selectFirst("img") ?: el.parent()?.parent()?.selectFirst("img")
        val poster = fixUrlNull(
            imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgEl?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: imgEl?.attr("src")?.takeIf { !it.contains("data:image") }
        )

        return if (isFilm) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = withContext(Dispatchers.IO) {
            URLEncoder.encode(query, "UTF-8")
        }
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        val document = app.get(searchUrl, referer = "$mainUrl/").document

        return document.select("div.item").mapNotNull { item ->
            val anchor = item.parent()?.takeIf { it.tagName() == "a" }
                ?: item.selectFirst("a[href]")
            val href = anchor?.attr("abs:href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!isValidContentUrl(href)) return@mapNotNull null

            val title = item.selectFirst(".content .title")?.text()?.trim()
                ?: item.selectFirst(".title")?.text()?.trim()
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null

            val rawPoster = item.selectFirst(".img img")?.let { img ->
                img.attr("abs:data-src").ifBlank {
                    img.attr("abs:src").ifBlank {
                        img.attr("data-src").ifBlank { img.attr("src") }
                    }
                }
            }?.trim()?.takeIf { it.isNotBlank() }

            val posterUrl = rawPoster?.let { fixUrl(it) }

            val year = item.selectFirst(".meta .year")?.text()?.trim()?.toIntOrNull()
            val score = item.selectFirst(".meta .imdb span")?.text()?.trim()?.toDoubleOrNull()

            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score?.let { Score.from10(it) }
            }
        }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val isFilm = url.contains("/film/") && !url.contains("/filmler/")

        val title  = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst(".poster img")?.attr("src")
                ?: document.selectFirst("img[class*='poster']")?.attr("src")
                ?: document.selectFirst("[class*='serie-cover'] img")?.attr("src")
                ?: document.selectFirst("[class*='movie-cover'] img")?.attr("src")
        )

        val plotEl = document.selectFirst("[class*='description'], [class*='synopsis'], [class*='ozet'], [class*='plot']")
            ?: document.selectFirst("meta[name='description']")
        val plot = plotEl?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: plotEl?.attr("content")?.trim()

        val year = document.selectFirst("[class*='year']")?.text()?.trim()?.toIntOrNull()

        val tags = document.select("a[href*='/tur/']").map { it.text().trim() }.filter { it.isNotBlank() }

        val actors = document.select("a[href*='/oyuncu/']").map { Actor(it.text().trim()) }

        val trailerEl = document.selectFirst("iframe[src*='youtube']")
        val trailer   = trailerEl?.attr("src")?.takeIf { it.isNotBlank() }

        val ratingText = document.selectFirst("[class*='score']")?.text()?.trim()
            ?: document.selectFirst("span[class*='imdb']")?.text()?.replace("IMDb", "")?.trim()

        if (isFilm) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
                this.score     = Score.from10(ratingText)
                addActors(actors)
                if (!trailer.isNullOrBlank()) addTrailer(trailer)
            }
        }

        val episodeList  = mutableListOf<Episode>()
        val episodeLinks = document.select("div[class*='episode'] a[href*='bolum']")

        episodeLinks.forEach { epLink ->
            val epHref  = fixUrlNull(epLink.attr("href")) ?: return@forEach
            val rawText = epLink.text().trim()

            val sNum = Regex("""(\d+)\.\s*Sezon""").find(rawText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val eNum = Regex("""(\d+)\.\s*Bölüm""").find(rawText)?.groupValues?.get(1)?.toIntOrNull()

            val epTitle = rawText.substringAfterLast(".").trim().takeIf { it.isNotBlank() }

            episodeList.add(newEpisode(epHref) {
                this.name    = epTitle
                this.season  = sNum
                this.episode = eNum
            })
        }

        if (episodeList.isEmpty()) {
            val allEpLinks = document.select("a[href*='bolum']")
            allEpLinks.forEach { epLink ->
                val epHref = fixUrlNull(epLink.attr("href")) ?: return@forEach
                if (!epHref.contains(mainUrl)) return@forEach
                val rawText = epLink.text().trim()
                val sNum    = Regex("""(\d+)\.\s*Sezon""").find(rawText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val eNum    = Regex("""(\d+)\.\s*Bölüm""").find(rawText)?.groupValues?.get(1)?.toIntOrNull()
                episodeList.add(newEpisode(epHref) {
                    this.season  = sNum
                    this.episode = eNum
                })
            }
        }

        val sortedEps = episodeList
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEps) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = year
            this.tags      = tags
            this.score     = Score.from10(ratingText)
            addActors(actors)
            if (!trailer.isNullOrBlank()) addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback : (SubtitleFile) -> Unit,
        callback         : (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val container = document.selectFirst("#episode-player-container") ?: return false
        val postId    = container.attr("data-post-id").takeIf { it.isNotBlank() } ?: return false

        val resp = app.get(
            ajaxUrl,
            params  = mapOf("action" to "get_episode_player", "post_id" to postId),
            referer = data,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        )

        val playerData = tryParseJson<PlayerResponse>(resp.text) ?: return false
        if (!playerData.success) return false

        val innerHtml  = playerData.data
        var iframeSrc  = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            .find(innerHtml)?.groupValues?.get(1) ?: return false

        if (iframeSrc.startsWith("//")) {
            iframeSrc = "https:$iframeSrc"
        }

        val m3u8 = extractM3u8FromDizipodPlayer(iframeSrc, data) ?: return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name   = name,
                url    = m3u8,
                type   = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
            ) {
                this.headers = mapOf("Referer" to iframeSrc)
                this.quality = com.lagradost.cloudstream3.utils.Qualities.P720.value
            }
        )
        return true
    }
}
