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
        "${mainUrl}/diziler/cizgi-film/" to "Çizgi Filmler",
        "${mainUrl}/diziler/dizi/"       to "Diziler",
        "${mainUrl}/diziler/anime/"      to "Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val requestUrl = if (page > 1) {
            val separator = if (request.data.contains("?")) "&" else "?"
            "${request.data.removeSuffix("/")}${separator}page=$page"
        } else {
            request.data
        }

        val document = app.get(requestUrl).document
        val home     = document.select("div.film-list div.film-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val nameEl = this.selectFirst("a.film-name") ?: return null
        val title  = nameEl.text().trim()
        val href   = fixUrlNull(nameEl.attr("href")) ?: return null
        
        val imgEl  = this.selectFirst("a.poster img")
        val posterUrl = fixUrlNull(imgEl?.attr("src") ?: imgEl?.attr("data-src"))

        val isMovie = href.contains("/film/")
        val type = if (isMovie) TvType.Movie else TvType.Cartoon

        return if (isMovie) {
            newMovieSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/ara/?q=${query}").document
        return document.select("div.film-list div.film-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val titleEl     = document.selectFirst("h1 a.anime-title-link") ?: document.selectFirst("h1")
        val title       = titleEl?.text()?.trim() ?: return null
        
        val imgEl       = document.selectFirst("div.anime-poster img")
        val poster      = fixUrlNull(imgEl?.attr("src") ?: imgEl?.attr("data-src"))
        
        val description = document.selectFirst("p.anime-desc")?.text()?.trim()
        val tags        = document.select("li.meta-genres strong a").map { it.text().trim() }
        
        val year        = document.select("ul.anime-meta-col li").firstOrNull { 
            it.text().contains("Yayın Yılı", ignoreCase = true) 
        }?.selectFirst("strong")?.text()?.toIntOrNull()

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
                this.year      = year
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
                this.posterUrl = poster
                this.plot      = description
                this.tags      = tags
                this.year      = year
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
            val serversB64 = serversMatch.groupValues[2].replace("\\", "")
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
            val serversByLangB64 = serversByLangMatch.groupValues[2].replace("\\", "")
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
                var finalUrl = streamUrl
                try {
                    val headRes = app.get(streamUrl, headers = mapOf("Referer" to "$mainUrl/"), allowRedirects = false)
                    if (headRes.code == 302 || headRes.code == 301) {
                        val redirectLoc = headRes.headers["location"] ?: headRes.headers["Location"]
                        if (!redirectLoc.isNullOrEmpty()) {
                            finalUrl = redirectLoc
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CZGM", "Redirect error: ${e.message}")
                }

                var label = server.label ?: "ÇizgiMax"
                if (server.type != null) {
                    label = "$label (${server.type})"
                }

                val headersMap = if (finalUrl.contains("sibnet.ru")) {
                    mapOf("Referer" to "https://video.sibnet.ru/")
                } else {
                    mapOf("Referer" to "$mainUrl/")
                }

                callback.invoke(
                    newExtractorLink(
                        source = label,
                        name = label,
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        quality = Qualities.Unknown.value
                        headers = headersMap
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
