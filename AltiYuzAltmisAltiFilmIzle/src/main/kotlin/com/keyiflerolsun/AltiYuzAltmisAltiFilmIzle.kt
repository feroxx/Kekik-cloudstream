//Bu eklenti @feroxxcs3 için @patr0n tarafından gelistirildi.
package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AltiYuzAltmisAltiFilmIzle : MainAPI() {
    override var mainUrl              = "https://666filmizle.site"
    override var name                 = "666FilmIzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/"                               to "Son Eklenenler",
        "${mainUrl}/tur/aksiyon-filmleri-1/"        to "Aksiyon",
       //${mainUrl}/tur/macera-filmleri/"           to "Macera",
        "${mainUrl}/tur/bilim-kurgu/"               to "Bilim-Kurgu",
        "${mainUrl}/tur/savas-filmleri/"            to "Savaş",
        "${mainUrl}/tur/gerilim-filmleri/"          to "Gerilim",
        "${mainUrl}/tur/komedi-filmleri/"           to "Komedi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/${page}/" else request.data
        val document = app.get(url).document

        val home = document.select("a.film-card__link").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("h3")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } })

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama/?q=${query}").document
        return document.select("a.film-card__link").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h2, .movie-info__title, h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.movie-poster img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } })
        val description = document.selectFirst("div.movie-info__plot.film-aciklama-scroll")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("666FilmIzle", "data » $data")
        val document = app.get(data).document

        
        document.select("button.player-sources__btn").forEach { button ->
            val iframeSrc = button.attr("data-frame")
            if (iframeSrc.isNotBlank()) {
                Log.d("666FilmIzle", "bulunan iframe(data-frame) » $iframeSrc")
                
                if (iframeSrc.contains("rapidplay.website")) {
                    val id = iframeSrc.substringAfterLast("#")
                    if (id.isNotBlank() && id != iframeSrc) {
                        val m3u8Url = "https://p.rapidplay.website/videos/$id/master.m3u8"
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "Rapidplay",
                                url = m3u8Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                referer = "https://p.rapidplay.website/"
                                quality = Qualities.Unknown.value
                            }
                        )
                    }
                } else {
                    loadExtractor(iframeSrc, "${mainUrl}/", subtitleCallback, callback)
                }
            }
        }

        
        document.select("div.player-content iframe, iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank() && !iframeSrc.contains("youtube")) {
                Log.d("666FilmIzle", "Mevcut iframe (fallback) » $iframeSrc")
                loadExtractor(iframeSrc, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }
}
