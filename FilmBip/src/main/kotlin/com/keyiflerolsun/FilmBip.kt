// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class FilmBip : MainAPI() {
    override var mainUrl              = "https://filmbip.com"
    override var name                 = "FilmBip"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler/"                        to "Yeni Filmler",
        "${mainUrl}/film/tur/aile/"                   to "Aile Filmleri",
        "${mainUrl}/film/tur/aksiyon/"                to "Aksiyon Filmleri",
        "${mainUrl}/film/tur/belgesel/"               to "Belgesel Filmleri",
        "${mainUrl}/film/tur/bilim-kurgu/"            to "Bilim Kurgu Filmleri",
        "${mainUrl}/film/tur/dram/"                   to "Dram Filmleri",
        "${mainUrl}/film/tur/fantastik/"              to "Fantastik Filmler",
        "${mainUrl}/film/tur/gerilim/"                to "Gerilim Filmleri",
        "${mainUrl}/film/tur/gizem/"                  to "Gizem Filmleri",
        "${mainUrl}/film/tur/komedi/"                 to "Komedi Filmleri",
        "${mainUrl}/film/tur/korku/"                  to "Korku Filmleri",
        "${mainUrl}/film/tur/macera/"                 to "Macera Filmleri",
        "${mainUrl}/film/tur/muzik/"                  to "Müzik Filmleri",
        "${mainUrl}/film/tur/romantik/"               to "Romantik Filmler",
        "${mainUrl}/film/tur/savas/"                  to "Savaş Filmleri",
        "${mainUrl}/film/tur/suc/"                    to "Suç Filmleri",
        "${mainUrl}/film/tur/tarih/"                  to "Tarih Filmleri",
        "${mainUrl}/film/tur/vahsi-bati/"             to "Western Filmler",
        "${mainUrl}/film/tur/tv-film/"                to "TV Filmleri",
    )

override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/$page"
    val document = app.get(url).document
    val home = document.select("div.poster-long").mapNotNull { it.toSearchResult() }

    return newHomePageResponse(request.name, home)
}

private fun Element.toSearchResult(): SearchResponse? {
    val title = this.selectFirst("a.block img")?.attr("alt")?.trim() ?: return null
    val href = fixUrlNull(this.selectFirst("a.block")?.attr("href")) ?: return null
    
    val imgElement = this.selectFirst("a.block img.lazy")
    if (imgElement == null) {
        Log.d("FLB", "imgElement is null")
        return null
    }

    val posterUrl = when {
		imgElement.hasAttr("data-src") && imgElement.attr("data-src").isNotBlank() -> {
            Log.d("FLB", "Using data-src: ${imgElement.attr("data-src")}")
            fixUrlNull(imgElement.attr("data-src"))
        }
        imgElement.hasAttr("src") && imgElement.attr("src").isNotBlank() -> {
            Log.d("FLB", "Using src: ${imgElement.attr("src")}")
            fixUrlNull(imgElement.attr("src"))
        }
        else -> {
            Log.d("FLB", "No valid src or data-src found")
            null
        }
    } ?: return null

    return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
}

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title  = document.selectFirst("div.page-title h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content")) ?: return null

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
        }
    }

     override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FLB", "data » $data")
        val document = app.get(data).document

        document.select("div#tv-spoox2").forEach {
            val iframe = fixUrlNull(it.selectFirst("iframe")?.attr("src")) ?: return@forEach
            Log.d("FLB", "iframe » $iframe")

            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        return true
    }
}
