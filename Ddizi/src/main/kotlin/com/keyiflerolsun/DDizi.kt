package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class DDizi : MainAPI() {
    override var mainUrl              = "https://www.ddizi.im"
    override var name                 = "DDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/yeni-eklenenler1"  to "Son Eklenen Bölümler",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl"                   to "Yerli Diziler",
        "$mainUrl/eski.diziler"      to "Eski Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}/$page" else request.data
        val document = app.get(url, headers = getHeaders(mainUrl)).document
    
        val home = when (request.name) {
            "Yerli Diziler" -> {
                val listItems = document.selectFirst("ul.list_")  // Sadece ilkini al
                    ?.select("li > a")
                    ?.mapNotNull {
                        val title = it.text()?.trim() ?: return@mapNotNull null
                        val rawHref = it.attr("href") ?: return@mapNotNull null
        
                        // İstenmeyen bağlantıları atla
                        if (rawHref.contains("eski.diziler") || rawHref.contains("yabanci-dizi-izle")) {
                            return@mapNotNull null
                        }
        
                        val cleanedHref = fixUrl(rawHref.replace(Regex("-\\d*-?son-bolum-izle/?$"), ""))
        
                        val posterDoc = app.get(cleanedHref, headers = getHeaders(mainUrl)).document
                        val posterUrl = posterDoc.selectFirst("div.afis img, img.afis, img.img-back, img.img-back-cat")
                            ?.let { img -> fixUrlNull(img.attr("data-src") ?: img.attr("src")) }
        
                        newTvSeriesSearchResponse(title, cleanedHref, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                        }
                    }?.take(36)
        
                listItems ?: emptyList()
            }
        
            else -> {
                document.select("div.dizi-boxpost, div.dizi-boxpost-cat")
                    .mapNotNull { it.toSearchResult() }
            }
        }
    
        val hasNextPage = document.selectFirst(".pagination a:contains(Sonraki)") != null
        return newHomePageResponse(request.name, home, hasNextPage)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a") ?: return null
        val title = linkElement.text().trim() ?: return null
        val href = fixUrl(linkElement.attr("href") ?: return null)
        val posterUrl = selectFirst("img.img-back, img.img-back-cat")
            ?.let { fixUrlNull(it.attr("data-src") ?: it.attr("src")) }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("DDizi:", "Searching for $query")
        
        val formData = mapOf("arama" to query)
        
        val document = app.post(
            "$mainUrl/arama/", 
            data = formData, 
            headers = getHeaders(mainUrl)
        ).document
        val results = ArrayList<SearchResponse>()
        
        try {
            val boxCatResults = document.select("div.dizi-boxpost-cat").mapNotNull { it.toSearchResult() }
            if (boxCatResults.isNotEmpty()) {
                Log.d("DDizi:", "Found ${boxCatResults.size} box-cat results")
                results.addAll(boxCatResults)
            }
        } catch (e: Exception) {
            Log.d("DDizi:", "Error parsing box-cat search results: ${e.message}")
        }
        
        if (results.isEmpty()) {
            try {
                val boxResults = document.select("div.dizi-boxpost").mapNotNull { it.toSearchResult() }
                if (boxResults.isNotEmpty()) {
                    Log.d("DDizi:", "Found ${boxResults.size} box results")
                    results.addAll(boxResults)
                }
            } catch (e: Exception) {
                Log.d("DDizi:", "Error parsing box search results: ${e.message}")
            }
        }
        
        if (results.isEmpty()) {
            try {
                val altResults = document.select("div.dizi-listesi a, div.yerli-diziler li a, div.yabanci-diziler li a").mapNotNull { 
                    val title = it.text()?.trim() ?: return@mapNotNull null
                    val href = fixUrl(it.attr("href") ?: return@mapNotNull null)
                    
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = null
                    }
                }
                
                if (altResults.isNotEmpty()) {
                    Log.d("DDizi:", "Found ${altResults.size} alternative results")
                    results.addAll(altResults)
                }
            } catch (e: Exception) {
                Log.d("DDizi:", "Error parsing alternative search results: ${e.message}")
            }
        }
        
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = getHeaders(mainUrl)).document
        val fullTitle = document.selectFirst("h1, h2, div.dizi-boxpost-cat a")?.text()?.trim() ?: ""

        // Başlık ayrıştırma için daha basit bir yöntem
        val (title, season, episode) = parseTitle(fullTitle)
    
        val posterUrl = document.selectFirst("div.afis img, img.afis, img.img-back, img.img-back-cat")
            ?.let { fixUrlNull(it.attr("data-src") ?: it.attr("src")) }
    
        val plot = document.selectFirst("div.dizi-aciklama, div.aciklama, p")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
    
        if (url.contains("/dizi/") || url.contains("/diziler/")) {
            // Dizi sayfasıysa tüm bölümleri topla
            var currentPage = 0
            var hasMorePages = true

            while (hasMorePages) {
                val pageUrl = if (currentPage == 0) url else "$url/sayfa-$currentPage"
                val pageDoc = if (currentPage == 0) document else app.get(pageUrl, headers = getHeaders(mainUrl)).document

                val pageEpisodes = pageDoc.select("div.bolumler a, div.sezonlar a, div.dizi-arsiv a, div.dizi-boxpost-cat a")
                    .mapNotNull { ep ->
                        val name = ep.text().trim()
                        val href = fixUrl(ep.attr("href"))
                        val (epTitle, epSeason, epEpisode) = parseTitle(name)
                        newEpisode(href) {
                            this.name = epTitle
                            this.season = epSeason
                            this.episode = epEpisode
                        }
                    }

                episodes.addAll(pageEpisodes)
                currentPage++
                hasMorePages = pageEpisodes.isNotEmpty() && pageDoc.selectFirst(".pagination a:contains(Sonraki)") != null
            }
        } else {
            // Tek bölüm sayfası
            episodes.add(newEpisode(url) {
                this.name = fullTitle
                this.season = season
                this.episode = episode
                this.description = plot
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    private fun parseTitle(fullTitle: String): Triple<String, Int, Int?> {
        val seasonMatch = Regex("""(\d+)\.?\s*Sezon""", RegexOption.IGNORE_CASE).find(fullTitle)
        val episodeMatch = Regex("""(\d+)\.?\s*Bölüm""", RegexOption.IGNORE_CASE).find(fullTitle)
        val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val episode = episodeMatch?.groupValues?.get(1)?.toIntOrNull()

        val title = fullTitle.replace(Regex("""^\d+\.?\s*|\d+\.?\s*Sezon\s*|\d+\.?\s*Bölüm\s*|Sezon Finali""", RegexOption.IGNORE_CASE), "").trim()
        return Triple(title, season, episode)
    }

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data, headers = getHeaders(mainUrl)).document

    // Check for iframe with YouTube in src
    val iframeSrc = document.selectFirst("iframe")?.attr("src")
    if (iframeSrc?.contains("youtube", ignoreCase = true) == true) {
        // Log the iframe src for debugging
        Log.d("DDizi:", "iframeSrc = $iframeSrc")
        
        // Extract the YouTube URL from the id parameter
        val youtubeUrl = Regex("""id=(https://.*?)(?:&|$)""").find(iframeSrc)?.groupValues?.get(1)
        if (youtubeUrl != null) {
        Log.d("DDizi:", "Calling loadExtractor with $youtubeUrl")
            try {
                loadExtractor(youtubeUrl, "", subtitleCallback, callback)
                return true
            } catch (e: Exception) {
                Log.e("DDizi:", "loadExtractor failed: ${e.message}", e)
            }
    }
}

    // Proceed to og:video extraction if YouTube iframe is not present or fails
    val ogVideo = document.selectFirst("meta[property=og:video]")?.attr("content")
        ?: return loadExtractor(data, data, subtitleCallback, callback) // Fallback to loadExtractor if no og:video

    val playerDoc = app.get(ogVideo, headers = getHeaders(data)).document
    val jwScript = playerDoc.select("script").firstOrNull { it.html().contains("jwplayer") && it.html().contains("sources") }
        ?: return loadExtractor(ogVideo, data, subtitleCallback, callback) // Fallback to loadExtractor if no JW script

    val sourcesRegex = Regex("""sources:\s*\[\s*\{(.*?)\}\s*,?\s*\]""", RegexOption.DOT_MATCHES_ALL)
    val fileRegex = Regex("""file:\s*["'](.*?)["']""")
    val sourcesMatch = sourcesRegex.find(jwScript.html()) ?: return false
    val fileUrl = fileRegex.find(sourcesMatch.groupValues[1])?.groupValues?.get(1) ?: return false
    Log.d("fileurl", fileUrl)
    val isHls = fileUrl.contains(".m3u8")
    val quality = Regex("""label:\s*["'](.*?)["']""").find(sourcesMatch.groupValues[1])?.groupValues?.get(1) ?: "Auto"
    val videoHeaders = if (fileUrl.contains("master.txt")) {
        mapOf(
            "accept" to "*/*",
            "user-agent" to USER_AGENT,
            "referer" to ogVideo
        )
    } else {
        getHeaders(ogVideo)
    }

    callback.invoke(
        newExtractorLink(
            source = name,
            name = "$name - $quality",
            url = fileUrl,
            type = if (isHls) ExtractorLinkType.M3U8 else INFER_TYPE
            ) {
            this.quality = getQualityFromName(quality)
            headers = mapOf("Referer" to ogVideo)
          }
    )

    if (isHls) {
        M3u8Helper.generateM3u8(name, fileUrl, ogVideo, headers = videoHeaders).forEach(callback)
    }

    return true
}

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

        private fun getHeaders(referer: String): Map<String, String> = mapOf(
            "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "user-agent" to USER_AGENT,
            "referer" to referer
        )
    }
}
