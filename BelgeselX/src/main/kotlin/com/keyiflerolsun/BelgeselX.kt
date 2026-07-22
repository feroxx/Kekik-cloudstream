// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import java.util.Locale
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class BelgeselX : MainAPI() {
    override var mainUrl              = "https://belgeselx.com"
    override var name                 = "BelgeselX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Documentary)
	
    override val mainPage = mainPageOf(
        "${mainUrl}/konu/turk-tarihi-belgeselleri" to "Türk Tarihi",
        "${mainUrl}/konu/tarih-belgeselleri"	   to "Tarih",
        "${mainUrl}/konu/seyehat-belgeselleri"	   to "Seyahat",
        "${mainUrl}/konu/seri-belgeseller"		   to "Seri",
        "${mainUrl}/konu/savas-belgeselleri"	   to "Savaş",
        "${mainUrl}/konu/sanat-belgeselleri"	   to "Sanat",
        "${mainUrl}/konu/psikoloji-belgeselleri"   to "Psikoloji",
        "${mainUrl}/konu/polisiye-belgeselleri"	   to "Polisiye",
        "${mainUrl}/konu/otomobil-belgeselleri"	   to "Otomobil",
        "${mainUrl}/konu/nazi-belgeselleri"		   to "Nazi",
        "${mainUrl}/konu/muhendislik-belgeselleri" to "Mühendislik",
        "${mainUrl}/konu/kultur-din-belgeselleri"  to "Kültür Din",
        "${mainUrl}/konu/kozmik-belgeseller"	   to "Kozmik",
        "${mainUrl}/konu/hayvan-belgeselleri"	   to "Hayvan",
        "${mainUrl}/konu/eski-tarih-belgeselleri"  to "Eski Tarih",
        "${mainUrl}/konu/egitim-belgeselleri"	   to "Eğitim",
        "${mainUrl}/konu/dunya-belgeselleri"	   to "Dünya",
        "${mainUrl}/konu/doga-belgeselleri"		   to "Doğa",
        "${mainUrl}/konu/bilim-belgeselleri"	   to "Bilim"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            val categorySlug = request.data.removeSuffix("/").substringAfterLast("/")
            "https://belgeselx.com/ajax_konukat.php?url=$categorySlug&page=$page"
        }

        val document = app.get(url, cacheTime = 60).document
        val home = document.select("div.px-grid > a.px-card").mapNotNull { it.toSearchResult() }
        val parsedItems = home.ifEmpty { document.select("a.px-card").mapNotNull { it.toSearchResult() } }

        return newHomePageResponse(request.name, parsedItems)
    }

    private fun String.toTitleCase(): String {
        val locale = Locale("tr", "TR")
        return this.split(" ").joinToString(" ") { word ->
            word.lowercase(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".px-card-title")?.text()?.trim()?.toTitleCase() ?: return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img.px-card-img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.Documentary) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cx = "016376594590146270301:iwmy65ijgrm" // ! Might change in the future

        val tokenResponse = app.get("https://cse.google.com/cse.js?cx=${cx}")
        val cseLibVersion = Regex("""cselibVersion": "(.*)"""").find(tokenResponse.text)?.groupValues?.get(1)
        val cseToken      = Regex("""cse_token": "(.*)"""").find(tokenResponse.text)?.groupValues?.get(1)
        val fexp      = Regex("""fexp": "[.*]"""").find(tokenResponse.text)?.groupValues?.get(1)

        val response = app.get("https://cse.google.com/cse/element/v1?rsz=filtered_cse&num=100&hl=tr&source=gcsc&cselibv=${cseLibVersion}&cx=${cx}&q=${query}&safe=off&cse_tok=${cseToken}&sort=&exp=cc%2Capo&fexp=${fexp}&callback=google.search.cse.api9969&rurl=https%3A%2F%2Fbelgeselx.com%2F")
        Log.d("BLX", "response » $response")
        val titles     = Regex(""""titleNoFormatting": "(.*)"""").findAll(response.text).map { it.groupValues[1] }.toList()
        val urls       = Regex(""""ogImage": "(.*)"""").findAll(response.text).map { it.groupValues[1] }.toList()
        val posterUrls = Regex(""""ogImage": "(.*)"""").findAll(response.text).map { it.groupValues[1] }.toList()

        val searchResponses = mutableListOf<TvSeriesSearchResponse>()

        for (i in titles.indices) {
            val title     = titles[i].split("İzle")[0].trim().toTitleCase()
            val url       = urls.getOrNull(i) ?: continue
            val posterUrl = posterUrls.getOrNull(i) ?: continue

            if (url.contains("diziresimleri")) {
                val fileName = url.substringAfterLast("/").replace(Regex("\\.(jpe?g|png|webp)$"), "")
                val modifiedUrl = "https://belgeselx.com/belgeseldizi/$fileName"
                searchResponses.add(newTvSeriesSearchResponse(title, modifiedUrl, TvType.Documentary) {
                    this.posterUrl = posterUrl
                })
            }
        }
        return searchResponses
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.px-hero-title")?.text()?.trim()?.toTitleCase() ?: return null
        val description = document.selectFirst("p.px-hero-desc")?.text()?.trim()
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val tags = document.select("a.px-hero-channel span").mapNotNull { it.text().trim().toTitleCase() }

        val episodes = mutableListOf<Episode>()

        document.select("a.px-ep-card, a.px-ep-row").forEach { element ->
            val onClickStr = element.attr("onclick")
            val epName = element.selectFirst(".px-ep-title")?.text()?.trim()
                ?: element.selectFirst(".c-num")?.text()?.trim()
                ?: "Bölüm"

            var epHref = fixUrlNull(element.attr("href")).takeIf { it != "#" && !it.isNullOrBlank() } ?: url

            var epId: String? = null
            var epSeason = 1
            var epEpisode = 1

            if (onClickStr.contains("diziGetir")) {
                val paramsMatch = Regex("""diziGetir\((.*?)\)""").find(onClickStr)
                if (paramsMatch != null) {
                    val params = paramsMatch.groupValues[1].split(",").map { it.trim().removeSurrounding("'").removeSurrounding("\"") }
                    epId = params.getOrNull(0)
                    val titleParam = params.getOrNull(4) ?: epName
                    val seasonParam = params.getOrNull(7)?.toIntOrNull() ?: 1
                    val epParam = params.getOrNull(8)?.toIntOrNull() ?: 1

                    epSeason = seasonParam
                    epEpisode = epParam
                    
                    val suffix = if (epId != null) "?epId=$epId&ic1=${params.getOrNull(1) ?: ""}&ic2=${params.getOrNull(2) ?: ""}&ic3=${params.getOrNull(3) ?: ""}" else ""
                    epHref = "$epHref$suffix"
                }
            } else {
                epId = Regex("""\(\s*['"]?(\d+)['"]?\s*\)""").find(onClickStr)?.groupValues?.get(1)
                val sMeta = element.selectFirst(".px-ep-s")?.text()?.trim() ?: ""
                epSeason  = Regex("""S(\d+)""").find(sMeta)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                epEpisode = Regex("""B(\d+)""").find(sMeta)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                val suffix = if (epId != null) "?epId=$epId" else ""
                epHref = "$epHref$suffix"
            }

            episodes.add(newEpisode(epHref) {
                this.name    = epName
                this.season  = epSeason
                this.episode = epEpisode
            })
        }

        // Single documentary fallback: try to find bolumId directly in JS script
        if (episodes.isEmpty()) {
            val script = document.select("script").find { it.data().contains("bolumId") }?.data()
            val epId = script?.let { Regex("""bolumId\s*:\s*(\d+)""").find(it)?.groupValues?.get(1) }
            if (epId != null) {
                episodes.add(newEpisode("$url?epId=$epId") {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Documentary, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.tags      = tags
        }
    }

    private fun getSrc(ic: String, id: String, sira: Int): String {
        val f = when (ic) {
            "0" -> "new5"
            "2" -> "new1"
            "3" -> "new2"
            "4" -> "new3"
            "5" -> "new4"
            else -> "default"
        }
        return "https://belgeselx.com/video/data/$f.php?id=$id&sira=$sira"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("BLX", "loadLinks data » $data")

        val episodeId = data.substringAfter("?epId=", "").substringBefore("&")
        Log.d("BLX", "Kullanılacak Bölüm ID: $episodeId")

        val refererUrl = data.substringBefore("?epId=")

        val playerUrls = mutableListOf<String>()
        val ic1 = data.substringAfter("&ic1=", "").substringBefore("&")
        val ic2 = data.substringAfter("&ic2=", "").substringBefore("&")
        val ic3 = data.substringAfter("&ic3=", "").substringBefore("&")

        if (ic1.isNotEmpty() && ic1 != "0") playerUrls.add(getSrc(ic1, episodeId, 1))
        if (ic2.isNotEmpty() && ic2 != "0") playerUrls.add(getSrc(ic2, episodeId, 2))
        if (ic3.isNotEmpty() && ic3 != "0") playerUrls.add(getSrc(ic3, episodeId, 3))

        if (playerUrls.isEmpty()) {
            playerUrls.add("https://belgeselx.com/video/data/new4.php?id=$episodeId")
            playerUrls.add("https://belgeselx.com/video/data/new5.php?id=$episodeId")
            playerUrls.add("https://belgeselx.com/video/data/new3.php?id=$episodeId")
            playerUrls.add("https://belgeselx.com/video/data/new2.php?id=$episodeId")
            playerUrls.add("https://belgeselx.com/video/data/new1.php?id=$episodeId")
        }

        var linksFound = false

        playerUrls.forEach { iframeUrl ->
            try {
                val alternatifResp = app.get(iframeUrl, referer = refererUrl).text
                
                // 1. Check for iframe sources
                val iframeSrcMatch = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(alternatifResp)
                if (iframeSrcMatch != null) {
                    var embedUrl = iframeSrcMatch.groupValues[1]
                    if (embedUrl.startsWith("AF1Qip")) {
                        embedUrl = "https://photos.google.com/share/$embedUrl"
                    }
                    
                    if (embedUrl.contains("photos.google.com") || embedUrl.contains("googleusercontent.com")) {
                        try {
                            val photosPage = app.get(embedUrl).text
                            val streamMatch = Regex(""""(https://video-downloads\.googleusercontent\.com/[^"]+)"""").find(photosPage)
                            if (streamMatch != null) {
                                val streamUrl = streamMatch.groupValues[1].replace("\\u003d", "=").replace("\\u0026", "&")
                                callback.invoke(
                                    newExtractorLink(
                                        source = "GooglePhotos",
                                        name = "GooglePhotos",
                                        url = streamUrl,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = refererUrl
                                        this.quality = Qualities.P720.value
                                    }
                                )
                                linksFound = true
                            }
                        } catch (e: Exception) {
                            Log.e("BLX", "Failed to resolve Google Photos stream: ${e.message}")
                        }
                    } else if (embedUrl.startsWith("http")) {
                        if (loadExtractor(embedUrl, refererUrl, subtitleCallback, callback)) {
                            linksFound = true
                        }
                    }
                }

                // 2. Parse video files and labels inside sources object array
                Regex("""\{\s*["']?file["']?\s*:\s*["']([^"']+)["'](?:.*?["']?label["']?\s*:\s*["']([^"']+)["'])?""").findAll(alternatifResp).forEach {
                    val videoUrl = it.groupValues[1]

                    // Filter out empty/incomplete URLs
                    if (videoUrl.endsWith("cid=") || videoUrl.endsWith("googleusercontent.com/") || videoUrl.isBlank()) {
                        return@forEach
                    }

                    var qualityStr = it.groupValues[2].ifEmpty { "720p" }
                    var sourceName = this.name

                    if (qualityStr.equals("FULL", ignoreCase = true)) {
                        qualityStr = "1080p"
                        sourceName = "Google"
                    }

                    // Follow redirects for php stream URLs to get the final playable video URL
                    var finalVideoUrl = videoUrl
                    if (videoUrl.contains("belgeselx.php") || videoUrl.contains("belgeselx2.php") || videoUrl.contains("belgeselx3.php")) {
                        try {
                            val redirectRes = app.get(videoUrl, referer = refererUrl, allowRedirects = true)
                            if (redirectRes.code == 200 && redirectRes.url.isNotEmpty() && !redirectRes.url.contains("belgeselx")) {
                                finalVideoUrl = redirectRes.url
                                Log.d("BLX", "Resolved redirect: $videoUrl -> $finalVideoUrl")
                            }
                        } catch (e: Exception) {
                            Log.e("BLX", "Failed to resolve redirect: ${e.message}")
                        }
                    }

                    val linkType = if (finalVideoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    callback.invoke(
                        newExtractorLink(
                            source = sourceName,
                            name = sourceName,
                            url = finalVideoUrl,
                            type = linkType
                        ) {
                            this.referer = refererUrl
                            this.quality = getQualityFromName(qualityStr)
                        }
                    )
                    linksFound = true
                }
            } catch (e: Exception) {
                Log.e("BLX", "Error loading player links: ${e.message}")
            }
        }

        return linksFound
    }
}
