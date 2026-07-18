// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CizgiMax : MainAPI() {
    override var mainUrl              = "https://cizgimax.online"
    override var name                 = "CizgiMax"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Cartoon)

    override val mainPage = mainPageOf(
        "/yeni-eklenenler/"    to "Son Eklenenler",
        "/tur/aile/"           to "Aile",
        "/tur/aksiyon/"         to "Aksiyon",
        "/tur/bilim-kurgu/"    to "Bilim Kurgu",
        "/tur/cocuk/"          to "Çocuklar",
        "/tur/komedi/"         to "Komedi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cleanData = request.data.removePrefix("/").removeSuffix("/")
        val url = if (cleanData.isEmpty()) {
            "${mainUrl}/?page=${page}"
        } else {
            "${mainUrl}/${cleanData}/?page=${page}"
        }
        val document = app.get(url).document
        val home     = document.select(".film-list .film-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("a.film-name")?.text()?.trim()
            ?: this.attr("data-anime-name").takeIf { it.isNotBlank() }
            ?: return null
        val href      = fixUrlNull(this.selectFirst("a.film-name")?.attr("href") ?: this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.Cartoon) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("${mainUrl}/api/search/suggest/?q=${query}").parsedSafe<SearchResult>()?.animes ?: return listOf()

        return response.mapNotNull { result ->
            newTvSeriesSearchResponse(
                result.name,
                fixUrl(result.url),
                TvType.Cartoon
            ) {
                this.posterUrl = fixUrlNull(result.poster)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1 a.anime-title-link")?.text()
            ?: document.selectFirst("h1.page-title")?.text()
            ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.anime-poster img")?.attr("src") ?: document.selectFirst("div.anime-poster img")?.attr("data-src")) ?: return null
        val description = document.selectFirst("p#anime-desc")?.text()?.trim()
        val tags        = document.select("li.meta-genres a").map { it.text().trim() }.filter { it.isNotEmpty() }

        val episodes = document.select("div.ep-grid-numbers").flatMap { pane ->
            val season = pane.attr("data-season-pane").toIntOrNull() ?: 1
            pane.select("a.ep-num-btn").mapNotNull { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val epName = a.attr("title").trim()
                val epEpisode = a.selectFirst("span.ep-num-label")?.text()?.trim()?.toIntOrNull()

                newEpisode(epHref) {
                    this.name = epName
                    this.season = season
                    this.episode = epEpisode
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.tags      = tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("CZGM", "data » $data")
        val document = app.get(data).document

        // Extract base64 servers array
        val script = document.select("script").find { it.data().contains("var servers") }?.data() ?: return false
        val serversMatch = Regex("""var servers\s*=\s*JSON\.parse\(atob\((["'])(.*?)\1\)\);""").find(script) ?: return false
        val serversB64 = serversMatch.groupValues[2]
        val decodedJson = String(android.util.Base64.decode(serversB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
        val servers = AppUtils.tryParseJson<List<ServerItem>>(decodedJson) ?: return false

        servers.forEach { server ->
            if (!server.resolveUrl.isNullOrEmpty()) {
                try {
                    val resolveUrl = if (server.resolveUrl.startsWith("http")) server.resolveUrl else "${mainUrl}${server.resolveUrl}"
                    val resolveRes = app.get(resolveUrl, referer = data).parsedSafe<ResolveResponse>()
                    val embedId = resolveRes?.id
                    if (!embedId.isNullOrEmpty()) {
                        val tauRes = app.get("https://tau-video.xyz/api/video/$embedId").parsedSafe<TauResponse>()
                        tauRes?.urls?.forEach { tauUrl ->
                            callback.invoke(
                                 newExtractorLink(
                                     source = server.label ?: "ÇizgiMax",
                                     name = "${server.label ?: "ÇizgiMax"} - ${tauUrl.label ?: "Hızlı"}",
                                     url = tauUrl.url,
                                     type = ExtractorLinkType.VIDEO
                                 ) {
                                     quality = getQualityFromName(tauUrl.label)
                                     headers = mapOf("Referer" to "$mainUrl/")
                                 }
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CZGM", "Resolve error: ${e.message}")
                }
            } else if (!server.src.isNullOrEmpty()) {
                val iframeSrc = server.src
                loadExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }

    private fun getQualityFromName(qualityName: String?): Int {
        return when (qualityName?.lowercase()?.trim()) {
            "1080p" -> Qualities.P1080.value
            "720p" -> Qualities.P720.value
            "480p" -> Qualities.P480.value
            "360p" -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
