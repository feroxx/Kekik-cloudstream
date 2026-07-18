package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
        "${mainUrl}/diziler/ulke/japonya/" to "Japon Diziler",
        "${mainUrl}/diziler/ulke/endonezya/" to "Endonezya Diziler",
        "${mainUrl}/devam-eden-diziler/" to "Devam eden Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/page/$page/").document
        val home = document.select("div.frag-k").mapNotNull { 
            it.toMainPageResult() 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("a.baslik span")?.text()?.trim()
            ?: this.selectFirst("a.resim")?.attr("title")?.trim() 
            ?: return null
        
        val href = fixUrlNull(this.selectFirst("a.resim")?.attr("href") 
            ?: this.selectFirst("a.baslik")?.attr("href")) 
            ?: return null

        val posterUrl = this.selectFirst("a.resim img")?.let { img ->
            fixUrlNull(
                img.attr("src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-src")
            )
        }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.trim().replace(" ", "+")
        val document = app.get("${mainUrl}/?s=$encodedQuery").document
        return document.select("div.frag-k, div.post-container, .sag-liste li").mapNotNull { 
            it.toMainPageResult() 
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(".ssag h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.title().split("|", "-").firstOrNull()?.trim()
            ?: return null

        val posterElement = document.selectFirst(".afis img")
        val poster = fixUrlNull(posterElement?.attr("src")?.takeIf { it.isNotEmpty() }
            ?: posterElement?.attr("data-src"))

        val description = document.selectFirst(".ozet, .aciklama")?.text()?.trim()
        val tags = document.select(".kategori a, .post-tags a, span.genre").mapNotNull { it.text()?.trim() }.distinct()

        val episodes = mutableListOf<Episode>()

        // 1. Parse standard list structure and range-based bundles
        val episodeElements = document.select("li.sec_bolum a, .dizi-bolumler a[href*=/bolum/], .bolum-listesi a, #bolumler a, a[href*=-bolum]")
        episodeElements.forEach { element ->
            val href = fixUrlNull(element.attr("href")) ?: return@forEach
            if (!href.contains("/bolum/")) return@forEach
            if (href.contains("fragman", ignoreCase = true)) return@forEach

            val epName = element.attr("title").trim().ifEmpty { element.text().trim() }
            val cleanPath = href.removeSuffix("/").substringAfterLast("/")
            
            val rangeMatch = """(\d+)-(\d+)-bolum""".toRegex().find(cleanPath)
            if (rangeMatch != null) {
                val start = rangeMatch.groupValues[1].toInt()
                val end = rangeMatch.groupValues[2].toInt()
                for (i in start..end) {
                    val epUrl = if (i == start) {
                        href
                    } else {
                        val partNum = i - start + 1
                        if (href.endsWith("/")) "${href}${partNum}/" else "${href}/${partNum}/"
                    }
                    episodes.add(newEpisode(epUrl) {
                        this.name = "$i. Bölüm"
                        this.episode = i
                    })
                }
            } else {
                val singleMatch = """(\d+)-bolum""".toRegex().find(cleanPath)
                val epNum = singleMatch?.groupValues?.get(1)?.toIntOrNull()
                    ?: """(\d+)""".toRegex().find(epName)?.groupValues?.get(1)?.toIntOrNull()
                
                episodes.add(newEpisode(href) {
                    this.name = if (epNum != null) "$epNum. Bölüm" else epName
                    this.episode = epNum
                })
            }
        }

        // 2. Fallback to tab headers if episodes list is still empty (old/alternative design)
        if (episodes.isEmpty()) {
            val tabHeaders = document.select("ul.sekme-baslik li")
            tabHeaders.forEach { tab ->
                val targetId = tab.attr("rel") ?: tab.selectFirst("a")?.attr("rel") ?: return@forEach
                if (!targetId.startsWith("bolum")) return@forEach
                val epName = tab.text().trim()
                val rangeMatch = """(\d+)\s*-\s*(\d+)""".toRegex().find(epName)

                if (rangeMatch != null) {
                    val start = rangeMatch.groupValues[1].toInt()
                    val end = rangeMatch.groupValues[2].toInt()
                    for (i in start..end) {
                        val episodeDataUrl = "$url#$targetId?ep=$i"
                        episodes.add(newEpisode(episodeDataUrl) {
                            this.name = "$i. Bölüm"
                            this.episode = i
                        })
                    }
                } else {
                    val epNum = """(\d+)""".toRegex().find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    episodes.add(newEpisode("$url#$targetId") {
                        this.name = epName
                        this.episode = epNum
                    })
                }
            }
        }

        val sortedEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fragmentParts = data.split("#")
        val pageUrl = fragmentParts[0]

        val rawFragment = if (fragmentParts.size > 1) fragmentParts[1] else null
        val targetTabId = rawFragment?.substringBefore("?")

        val document = app.get(pageUrl).document

        val targetElement = if (!targetTabId.isNullOrEmpty()) {
            document.selectFirst("div#$targetTabId") ?: document
        } else {
            document
        }

        targetElement.select("iframe").forEach { element ->
            val src = element.attr("src").ifBlank {
                element.attr("data-url").ifBlank { element.attr("data-src") }
            }.trim()

            if (src.isNotEmpty() && !src.startsWith("javascript")) {
                val fixedUrl = if (src.startsWith("//")) "https:$src" else src
                loadExtractor(fixedUrl, pageUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
