package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.regex.Pattern

class TRasyalog : MainAPI() {
    override var mainUrl        = "https://asyalog.co"
    override var name           = "AsyaLog"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 500L
    override var sequentialMainPageScrollDelay = 500L

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler/ulke/guney-kore/" to "Kore Dizileri",
        "${mainUrl}/diziler/ulke/cin/" to "Çin Dizileri",
        "${mainUrl}/diziler/ulke/tayland/" to "Tayland Dizileri",
        "${mainUrl}/category/japon-dizileri/" to "Japon Diziler",
        "${mainUrl}/diziler/ulke/endonezya/" to "Endonezya Diziler",
        "${mainUrl}/devam-eden-diziler/" to "Devam eden Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/page/$page/").document
        val home = document.select("div.post-container, article.post-item, div.archive-post, div.t-baslik").mapNotNull { 
            it.toMainPageResult() 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // Daha esnek title selector
        val title = this.selectFirst("img")?.attr("alt")
            ?: this.selectFirst("h2 a, h3 a, .post-title a")?.text()
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        
        // Poster selector'ları çoğalttım
        val posterUrl = fixUrlNull(
            this.selectFirst("img[data-src]")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
                ?: this.selectFirst(".thumbnail img, .post-thumbnail img")?.attr("src")
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.trim().replace(" ", "+")
        val document = app.get("${mainUrl}/?s=$encodedQuery").document
        return document.select("div.post-container, article.post-item, .search-result-item").mapNotNull { 
            it.toMainPageResult() 
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
    
        // Daha esnek title selector
        val title = document.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim()
            ?: return null
        
        // Poster için birden fazla selector
        val poster = fixUrlNull(
            document.selectFirst("img.attachment-large, img.wp-post-image, .post-thumbnail img")?.attr("data-src")
                ?: document.selectFirst("img.attachment-large, img.wp-post-image, .post-thumbnail img")?.attr("src")
                ?: document.selectFirst(".series-poster img, .post-featured-image img")?.attr("src")
        )
        
        val description = document.selectFirst("div.entry-content p:first-child, .post-description p, .series-synopsis")?.text()?.trim()
        
        // Tags için daha esnek selector
        val tags = document.select("span.genre, .post-tags a, .series-tags").mapNotNull { 
            it.text()?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct().take(5)

        val episodes = mutableListOf<Episode>()
        val addedEpisodeNumbers = mutableSetOf<Int>()

        // Data URLs'yi daha güvenli şekilde al
        val dataUrls = document.select("[data-url]").mapNotNull { element ->
            val dataUrl = element.attr("data-url").trim()
            if (dataUrl.isNotEmpty()) fixUrlNull(dataUrl) else null
        }.distinct()

        // Toplu bölümler (1-5, 6-10 formatı)
        val groupedPartUrls = dataUrls.filter { 
            "\\d+-\\d+".toRegex().containsMatchIn(it) 
        }

        // Tekli bölümler
        val singlePartUrls = dataUrls.filterNot { it in groupedPartUrls }

        processEpisodeParts(groupedPartUrls, addedEpisodeNumbers, episodes)
        processSingleEpisodes(singlePartUrls, addedEpisodeNumbers, episodes)

        val sortedEpisodes = episodes.sortedBy { it.episode ?: Int.MAX_VALUE }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    private suspend fun processEpisodeParts(
        partUrls: List<String>, 
        addedEpisodes: MutableSet<Int>,
        episodes: MutableList<Episode>
    ) {
        for (partUrl in partUrls) {
            try {
                val partDoc = app.get(partUrl).document
                val tabContents = partDoc.select("div[id^=tab-], .tab-pane[id*=bolum], #bolumler div.tab-content")
                
                for (tab in tabContents) {
                    val tabId = tab.id().lowercase()
                    val isFinal = tabId.contains("final")
                    val episodeMatch = """-(\d+)-?bolum?""".toRegex().find(tabId)
                    val episodeNumber = episodeMatch?.groupValues?.get(1)?.toIntOrNull()

                    if (shouldAddEpisode(episodeNumber, isFinal, addedEpisodes)) {
                        val iframeUrl = extractIframeUrl(tab) ?: continue
                        
                        episodes.add(newEpisode(iframeUrl) {
                            name = if (isFinal) "Final Bölüm" else "${episodeNumber ?: "Bilinmeyen"}. Bölüm"
                            episode = episodeNumber
                        })
                        episodeNumber?.let { addedEpisodes.add(it) }
                    }
                }
            } catch (e: Exception) {
                Log.e("TRASYA", "Part URL error: $partUrl", e)
            }
        }
    }

    private suspend fun processSingleEpisodes(
        epUrls: List<String>,
        addedEpisodes: MutableSet<Int>,
        episodes: MutableList<Episode>
    ) {
        for (epUrl in epUrls) {
            try {
                val epDoc = app.get(epUrl).document
                val iframe = epDoc.selectFirst("iframe, .embed-responsive-item")
                val iframeUrl = extractIframeUrl(iframe) ?: continue

                val isFinal = epUrl.contains("final", ignoreCase = true)
                val episodeMatch = """-(\d+)-?bolum?""".toRegex().find(epUrl)
                val episodeNumber = episodeMatch?.groupValues?.get(1)?.toIntOrNull()

                if (shouldAddEpisode(episodeNumber, isFinal, addedEpisodes)) {
                    episodes.add(newEpisode(iframeUrl) {
                        name = if (isFinal) "Final Bölüm" else "${episodeNumber ?: "Bilinmeyen"}. Bölüm"
                        episode = episodeNumber
                    })
                    episodeNumber?.let { addedEpisodes.add(it) }
                }
            } catch (e: Exception) {
                Log.e("TRASYA", "Single episode error: $epUrl", e)
            }
        }
    }

    private fun shouldAddEpisode(
        episodeNumber: Int?, 
        isFinal: Boolean, 
        addedEpisodes: Set<Int>
    ): Boolean {
        return (episodeNumber != null && episodeNumber !in addedEpisodes) || isFinal
    }

    private fun extractIframeUrl(element: Element?): String? {
        if (element == null) return null
        
        return element.attr("data-src").ifBlank { 
            element.attr("src")
        }.takeIf { it.isNotEmpty() }?.let { url ->
            if (url.startsWith("http")) url else "https:$url"
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("TRASYA", "Loading links: $data")
        loadExtractor(data, "$mainUrl/", subtitleCallback, callback)
        return true
    }
}
