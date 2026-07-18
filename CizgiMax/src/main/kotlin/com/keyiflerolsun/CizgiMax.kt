// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class CizgiMax : MainAPI() {
    override var mainUrl              = "https://cizgimax.online"
    override var name                 = "ÇizgiMax"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Cartoon, TvType.Anime, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler/page/"       to "Diziler",
        "${mainUrl}/tur/turkce-dublaj/page/" to "Türkçe Dublaj",
        "${mainUrl}/tur/turkce-altyazi/page/" to "Türkçe Altyazılı",
        "${mainUrl}/tur/cartoon/page/"   to "Çizgi Filmler",
        "${mainUrl}/tur/anime/page/"     to "Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val requestUrl = if (request.data.contains("/page/")) {
            request.data.replace("/page/", "/page/$page/")
        } else {
            "${request.data}$page/"
        }

        val document = app.get(requestUrl).document
        val home     = document.select("div.films-list div.flw-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("a.film-name")?.text()?.trim() 
            ?: this.selectFirst("img.film-poster-img")?.attr("alt")?.trim()
            ?: return null
        var href      = fixUrlNull(this.selectFirst("a.film-name")?.attr("href") 
            ?: this.selectFirst("a.film-poster-ahref")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img.film-poster-img")?.attr("data-src") 
            ?: this.selectFirst("img.film-poster-img")?.attr("src"))

        val isMovie = href.contains("/film/")
        val type = if (isMovie) TvType.Movie else TvType.Cartoon

        return if (isMovie) {
            newMovieSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama/?q=${query}").document
        return document.select("div.films-list div.flw-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h2.heading-name")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("img.film-poster-img")?.attr("src") ?: document.selectFirst("img.film-poster-img")?.attr("data-src"))
        val description = document.selectFirst("div.description")?.text()?.trim()
        val tags        = document.select("div.elements div.row-line").find { it.text().contains("Tür:") }?.select("a")?.map { it.text().trim() } ?: emptyList()

        val episodes = mutableListOf<Episode>()

        document.select("div.ep-grid-numbers").forEach { pane ->
            val seasonName = pane.attr("data-season-pane")
            val season = Regex("""\d+""").find(seasonName)?.value?.toIntOrNull() ?: 1

            pane.select("a.ep-num-btn").forEach { element ->
                val epHref = fixUrlNull(element.attr("href")) ?: return@forEach
                val epName = element.attr("title")?.trim() ?: "Bölüm"
                val epEpisode = Regex("""\d+""").find(element.text())?.value?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.season = season
                        this.episode = epEpisode
                    }
                )
            }
        }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = description
                this.tags      = tags
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
                this.posterUrl = poster
                this.plot      = description
                this.tags      = tags
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("CZGM", "data » $data")
        val document = app.get(data).document

        val script = document.select("script").find { it.data().contains("var servers") }?.data() ?: return false
        
        val serversList = mutableListOf<ServerItem>()

        // 1. Parse 'servers' array
        val serversMatch = Regex("""servers\s*=\s*JSON\.parse\(atob\((["'])(.*?)\1\)\)""").find(script)
        if (serversMatch != null) {
            val serversB64 = serversMatch.groupValues[2]
            var paddedB64 = serversB64
            while (paddedB64.length % 4 != 0) paddedB64 += "="
            try {
                val decodedJson = String(android.util.Base64.decode(paddedB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                val parsed = AppUtils.tryParseJson<List<ServerItem>>(decodedJson)
                if (parsed != null) serversList.addAll(parsed)
            } catch (e: Exception) {
                Log.e("CZGM", "Error parsing servers: ${e.message}")
            }
        }

        // 2. Parse 'serversByLang' map
        val serversByLangMatch = Regex("""serversByLang\s*=\s*JSON\.parse\(atob\((["'])(.*?)\1\)\)""").find(script)
        if (serversByLangMatch != null) {
            val serversByLangB64 = serversByLangMatch.groupValues[2]
            var paddedB64 = serversByLangB64
            while (paddedB64.length % 4 != 0) paddedB64 += "="
            try {
                val decodedJson = String(android.util.Base64.decode(paddedB64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                val parsedMap = AppUtils.tryParseJson<Map<String, List<ServerItem>>>(decodedJson)
                parsedMap?.values?.forEach { list ->
                    serversList.addAll(list)
                }
            } catch (e: Exception) {
                Log.e("CZGM", "Error parsing serversByLang: ${e.message}")
            }
        }

        val uniqueServers = serversList.distinctBy { it.streamUrl ?: it.resolveUrl ?: it.src ?: "" }

        uniqueServers.forEach { server ->
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
            } else if (!server.streamUrl.isNullOrEmpty()) {
                val streamUrl = if (server.streamUrl.startsWith("http")) server.streamUrl else "${mainUrl}${server.streamUrl}"
                var label = server.label ?: "ÇizgiMax"
                if (server.type != null) {
                    label = "$label (${server.type})"
                }
                callback.invoke(
                    newExtractorLink(
                        source = label,
                        name = label,
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        quality = Qualities.Unknown.value
                        headers = mapOf("Referer" to "$mainUrl/")
                    }
                )
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

    data class ServerItem(
        val type: String?,
        val label: String?,
        val resolveUrl: String?,
        val streamUrl: String?,
        val src: String?,
        val embedId: Any?
    )

    data class ResolveResponse(
        val id: String?
    )

    data class TauResponse(
        val urls: List<TauUrl>?
    )

    data class TauUrl(
        val label: String?,
        val url: String
    )
}
