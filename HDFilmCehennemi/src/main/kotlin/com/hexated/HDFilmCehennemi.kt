// ! https://github.com/hexated/cloudstream-extensions-hexated/blob/master/Hdfilmcehennemi/src/main/kotlin/com/hexated/Hdfilmcehennemi.kt

package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.sh"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}"                                to "Yeni Eklenen Filmler",
        "${mainUrl}/yabancidiziizle-1"              to "Yeni Eklenen Diziler",
        "${mainUrl}/category/tavsiye-filmler-izle2" to "Tavsiye Filmler",
        "${mainUrl}/imdb-7-puan-uzeri-filmler"      to "IMDB 7+ Filmler",
        "${mainUrl}/en-cok-yorumlananlar"           to "En Çok Yorumlananlar",
        "${mainUrl}/en-cok-begenilen-filmleri-izle" to "En Çok Beğenilenler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val home: List<SearchResponse>?

        home = document.select("div.section-content a.poster").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("strong.poster-title")?.text() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    private fun Media.toSearchResponse(): SearchResponse? {
        return newMovieSearchResponse(
            title ?: return null,
            "${mainUrl}/$slugPrefix$slug",
            TvType.TvSeries,
        ) {
            this.posterUrl = "${mainUrl}/uploads/poster/$poster"
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        return app.post(
            "${mainUrl}/search/",
            data    = mapOf("query" to query),
            referer = "${mainUrl}/",
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).parsedSafe<Result>()?.result?.mapNotNull {
            media -> media.toSearchResponse()
        } ?: throw ErrorLoadingException("Invalid Json reponse")
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags        = document.select("div.post-info-genres a").map { it.text() }
        val year        = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType      = if (document.select("div.seasons").isNullOrEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val rating      = document.selectFirst("div.post-info-imdb-rating span")?.text()?.toRatingInt()
        val actors      = document.select("div.post-info-cast a").map {
            Actor(it.text(), it.select("img").attr("data-src"))
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
                val recName      = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
                val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src"))

                newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                    this.posterUrl = recPosterUrl
                }
            }

        return if (tvType == TvType.TvSeries) {
            val trailer  = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }
            val episodes = document.select("div.seasons-tab-content a").map {
                val href    = it.attr("href")
                val name    = it.select("h4").text().trim()
                val episode = it.select("h4").text().let { num ->
                    Regex("Sezon\\s?([0-9]+).").find(num)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
                val season = it.parents()[1].attr("id").substringAfter("-").toIntOrNull()

                Episode(
                    href,
                    name,
                    season,
                    episode,
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.rating          = rating
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.rating          = rating
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private suspend fun invokeLocalSource(source: String, url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit ) {
        val script    = app.get(url, referer = "${mainUrl}/").document.select("script").find { it.data().contains("sources:") }?.data() ?: return
        val videoData = getAndUnpack(script).substringAfter("file_link=\"").substringBefore("\";")
        val subData   = script.substringAfter("tracks: [").substringBefore("]")

        callback.invoke(
            ExtractorLink(
                source,
                source,
                base64Decode(videoData),
                "${mainUrl}/",
                Qualities.Unknown.value,
                true
            )
        )

        tryParseJson<List<SubSource>>("[${subData}]")?.filter { it.kind == "captions" }?.map {
            subtitleCallback.invoke(
                SubtitleFile(it.label.toString(), fixUrl(it.file.toString()))
            )
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit ): Boolean {
        app.get(data).document.select("nav.nav.card-nav.nav-slider a.nav-link").map {
            Pair(it.attr("href"), it.text())
        }.apmap { (url, source) ->
            safeApiCall {
                app.get(url).document.select("div.card-video > iframe").attr("data-src").let {
                    url ->
                    if (url.startsWith(mainUrl)) {
                        invokeLocalSource(source, url, subtitleCallback, callback)
                    } else {
                        loadExtractor(url, "${mainUrl}/", subtitleCallback) { link ->
                            callback.invoke(
                                ExtractorLink(
                                    source,
                                    source,
                                    link.url,
                                    link.referer,
                                    link.quality,
                                    link.type,
                                    link.headers,
                                    link.extractorData
                                )
                            )
                        }
                    }
                }
            }
        }

        return true
    }

    private data class SubSource(
        @JsonProperty("file")  val file: String?  = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind")  val kind: String?  = null
    )

    data class Result(
        @JsonProperty("result") val result: ArrayList<Media>? = arrayListOf()
    )

    data class Media(
        @JsonProperty("title")       val title: String?      = null,
        @JsonProperty("poster")      val poster: String?     = null,
        @JsonProperty("slug")        val slug: String?       = null,
        @JsonProperty("slug_prefix") val slugPrefix: String? = null
    )
}
