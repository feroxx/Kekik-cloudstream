// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class IzleAI : MainAPI() {
    override var mainUrl              = "https://selcukflix.com"
    override var name                 = "720PizleAI"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 50L  // ? 0.05 saniye
    override var sequentialMainPageScrollDelay = 50L  // ? 0.05 saniye

    override val mainPage = mainPageOf(
        "${mainUrl}/kategori/aile-filmleri"        to "Aile",
        "${mainUrl}/kategori/aksiyon-filmleri"     to "Aksiyon",
        "${mainUrl}/kategori/animasyon-filmleri"   to "Animasyon",
        "${mainUrl}/kategori/belgesel-filmleri"    to "Belgesel",
        "${mainUrl}/kategori/bilim-kurgu-filmleri" to "Bilim Kurgu",
        "${mainUrl}/kategori/dram-filmleri"        to "Dram",
        "${mainUrl}/kategori/fantastik-filmleri"   to "Fantastik",
        "${mainUrl}/kategori/film-noir-filmleri"   to "Film-Noir",
        "${mainUrl}/kategori/gerilim-filmleri"     to "Gerilim",
        "${mainUrl}/kategori/gizem-filmleri"       to "Gizem",
        "${mainUrl}/kategori/kisa-filmleri"        to "Kısa Film",
        "${mainUrl}/kategori/komedi-filmleri"      to "Komedi",
        "${mainUrl}/kategori/korku-filmleri"       to "Korku",
        "${mainUrl}/kategori/macera-filmleri"      to "Macera",
        "${mainUrl}/kategori/muzik-filmleri"       to "Müzik",
        "${mainUrl}/kategori/romantik-filmleri"    to "Romantik",
        "${mainUrl}/kategori/savas-filmleri"       to "Savaş",
        "${mainUrl}/kategori/spor-filmleri"        to "Spor",
        "${mainUrl}/kategori/suc-filmleri"         to "Suç",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home     = document.select("a.ambilight").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("h2")?.text() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        return newMovieSearchResponse(
            title ?: return null,
            "${mainUrl}/${slug}",
            TvType.Movie,
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val mainReq  = app.get(mainUrl)
        val mainPage = mainReq.document
        val cKey     = mainPage.selectFirst("input[name='cKey']")?.attr("value") ?: return emptyList()
        val cValue   = mainPage.selectFirst("input[name='cValue']")?.attr("value") ?: return emptyList()

        val veriler   = mutableListOf<SearchResponse>()

        val searchReq = app.post(
            "${mainUrl}/bg/searchcontent",
            data = mapOf(
                "cKey"       to cKey,
                "cValue"     to cValue,
                "searchterm" to query
            ),
            headers = mapOf(
                "Accept"           to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "${mainUrl}/",
            cookies = mapOf(
                "showAllDaFull"   to "true",
                "PHPSESSID"       to mainReq.cookies["PHPSESSID"].toString(),
            )
        ).parsedSafe<SearchResult>()

        if (searchReq?.data?.state != true) {
            throw ErrorLoadingException("Invalid Json response")
        }

        searchReq.data.result?.forEach { searchItem ->
            val title = searchItem.title ?: return@forEach
            if (title.endsWith("Serisi") || title.endsWith("Series")) {
                return@forEach
            }

            veriler.add(searchItem.toSearchResponse() ?: return@forEach)
        }

        return veriler
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div.gap-3.pt-5 h2")?.text() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.col-span-2 img")?.attr("data-src"))
        val year        = document.selectFirst("a[href*='/yil/']")?.text()?.toIntOrNull()
        val description = document.selectFirst("div.mv-det-p")?.text()?.trim() ?: document.selectFirst("div.w-full div.text-base")?.text()?.trim()
        val tags        = document.select("[href*='kategori']").map { it.text() }
        val rating      = document.selectFirst("a[href*='imdb.com'] span.font-bold")?.text()?.trim().toRatingInt()
        val duration    = document.selectXpath("//span[contains(text(), ' dk.')]").text().trim().split(" ").first().toIntOrNull()
        val trailer     = document.selectFirst("iframe[data-src*='youtube.com/embed/']")?.attr("data-src")
        val actors      = document.select("div.flex.overflow-auto [href*='oyuncu']").map {
            Actor(it.selectFirst("span span")!!.text(), it.selectFirst("img")?.attr("data-srcset")?.split(" ")?.first())
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
            this.duration  = duration
            addTrailer(trailer)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("IAI", "data » $data")
        val document = app.get(data).document
        val iframe   = fixUrlNull(document.selectFirst("div.player iframe")?.attr("src")) ?: return false
        Log.d("IAI", "iframe » $iframe")

        loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)

        return true
    }
}