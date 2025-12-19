// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer


class FilmMakinesi : MainAPI() {
    override var mainUrl              = "https://filmmakinesi.to"
    override var name                 = "FilmMakinesi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage            = true // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 50L  // ? 0.05 saniye
    override var sequentialMainPageScrollDelay = 50L  // ? 0.05 saniye

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler-1/sayfa/"                                to "Son Filmler",
        "${mainUrl}/film-izle/olmeden-izlenmesi-gerekenler-fm1/sayfa/" to "Ölmeden İzle",
        "${mainUrl}/tur/aksiyon-fm1/film/sayfa/"                       to "Aksiyon",
        "${mainUrl}/tur/bilim-kurgu-fm2/film/sayfa/"                   to "Bilim Kurgu",
        "${mainUrl}/tur/macera-fm1/film/sayfa/"                        to "Macera",
        "${mainUrl}/tur/komedi-fm1/film/sayfa/"                        to "Komedi",
        "${mainUrl}/tur/romantik-fm1/film/sayfa/"                      to "Romantik",
        "${mainUrl}/tur/belgesel/film/sayfa/"                      to "Belgesel",
        "${mainUrl}/tur/fantastik-fm1/film/sayfa/"                     to "Fantastik",
        "${mainUrl}/tur/polisiye/film/sayfa/"                      to "Polisiye Suç",
        "${mainUrl}/tur/korku-fm1/film/sayfa/"                         to "Korku",
        // "${mainUrl}/tur/savas/film/sayfa/"                      to "Tarihi ve Savaş",
        // "${mainUrl}/film-izle/gerilim-filmleri-izle/sayfa/"     to "Gerilim Heyecan",
        // "${mainUrl}/film-izle/gizemli/sayfa/"                   to "Gizem",
        // "${mainUrl}/film-izle/aile-filmleri/sayfa/"             to "Aile",
        // "${mainUrl}/film-izle/animasyon-filmler/sayfa/"         to "Animasyon",
        // "${mainUrl}/film-izle/western/sayfa/"                   to "Western",
        // "${mainUrl}/film-izle/biyografi/sayfa/"                 to "Biyografik",
        // "${mainUrl}/film-izle/dram/sayfa/"                      to "Dram",
        // "${mainUrl}/film-izle/muzik/sayfa/"                     to "Müzik",
        // "${mainUrl}/film-izle/spor/sayfa/"                      to "Spor"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
val cleanedUrl = request.data.removeSuffix("/")
val url = if (page > 1) {
    "$cleanedUrl/$page"
} else {
    cleanedUrl.replace(Regex("/sayfa/?$"), "")
}

val document = app.get(url, headers = mapOf(
    "User-Agent" to USER_AGENT,
    "Referer" to mainUrl
)).document

    val home = document.select("div.film-list div.item-relative")
        .mapNotNull { it.toSearchResult() }

    Log.d("FLMM", "Toplam film: ${home.size}")
    return newHomePageResponse(request.name, home)
}

private fun Element.toSearchResult(): SearchResponse? {
    val aTag = selectFirst("a.item") ?: return null
    val title = aTag.attr("data-title").takeIf { it.isNotBlank() } ?: return null
    val href = fixUrlNull(aTag.attr("href")) ?: return null
    val posterUrl = fixUrlNull(aTag.selectFirst("img")?.attr("src"))

    Log.d("FLMM", "Film: $title, Href: $href, Poster: $posterUrl")

    return newMovieSearchResponse(title, href, TvType.Movie) {
        this.posterUrl = posterUrl
    }
}
    private fun Element.toRecommendResult(): SearchResponse? {
        val title     = this.select("a").last()?.text() ?: return null
        val href      = fixUrlNull(this.select("a").last()?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama/?s=${query}").document

        return document.select("div.film-list div.item-relative").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title           = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description     = document.select("div.info-description p").last()?.text()?.trim()
        val tags            = document.selectFirst("dt:contains(Tür:) + dd")?.text()?.split(", ")
        val year            = document.selectFirst("dt:contains(Yapım Yılı:) + dd")?.text()?.trim()?.toIntOrNull()

        val durationElement = document.select("dt:contains(Film Süresi:) + dd time").attr("datetime")
        // ? ISO 8601 süre formatını ayrıştırma (örneğin "PT129M")
        val duration        = if (durationElement.startsWith("PT") && durationElement.endsWith("M")) {
            durationElement.drop(2).dropLast(1).toIntOrNull() ?: 0
        } else {
            0
        }

        val recommendations = document.select("div.film-list div.item-relative").mapNotNull { it.toRecommendResult() }
        val actors          = document.selectFirst("dt:contains(Oyuncular:) + dd")?.text()?.split(", ")?.map {
            Actor(it.trim())
        }

        val trailer = document.selectFirst("div.left a.trailer-button")?.attr("data-video_url")?.substringAfter("embed/", "")?.let { 
    if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null 
}

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.duration        = duration
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }


    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FLMM", "data » $data")
        val document      = app.get(data).document
        val iframeSrc = document.selectFirst("iframe")?.attr("data-src") ?: ""

        val videoUrls = document.select(".video-parts a[data-video_url]").map { it.attr("data-video_url") }

        val allUrls = (if (iframeSrc.isNotEmpty()) listOf(iframeSrc) else emptyList()) + videoUrls

        allUrls.forEach { url ->
            Log.d("FLMM", "Processing URL: $url")
            loadExtractor(url, "${mainUrl}/", subtitleCallback, callback)
        }
        return allUrls.isNotEmpty()
}
}
