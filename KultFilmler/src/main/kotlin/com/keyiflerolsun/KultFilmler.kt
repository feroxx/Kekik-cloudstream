// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import android.util.Base64
import org.jsoup.Jsoup
import java.util.regex.Pattern

class KultFilmler : MainAPI() {
    override var mainUrl              = "https://kultfilmler.net"
    override var name                 = "KultFilmler"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)
	
    // ! CloudFlare bypass
    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 150L
    override var sequentialMainPageScrollDelay = 150L

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.text().contains("Just a moment...")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/category/aile-filmleri-izle"		    to "Aile",
        "${mainUrl}/category/aksiyon-filmleri-izle"	        to "Aksiyon",
        "${mainUrl}/category/animasyon-filmleri-izle"	    to "Animasyon",
        "${mainUrl}/category/belgesel-izle"			        to "Belgesel",
        "${mainUrl}/category/bilim-kurgu-filmleri-izle"     to "Bilim Kurgu",
        "${mainUrl}/category/biyografi-filmleri-izle"	    to "Biyografi",
        "${mainUrl}/category/dram-filmleri-izle"		    to "Dram",
        "${mainUrl}/category/fantastik-filmleri-izle"	    to "Fantastik",
        "${mainUrl}/category/gerilim-filmleri-izle"	        to "Gerilim",
        "${mainUrl}/category/gizem-filmleri-izle"		    to "Gizem",
        "${mainUrl}/category/kara-filmleri-izle"		    to "Kara",
        "${mainUrl}/category/kisa-film-izle"			    to "Kısa Metrajlı",
        "${mainUrl}/category/komedi-filmleri-izle"		    to "Komedi",
        "${mainUrl}/category/korku-filmleri-izle"		    to "Korku",
        "${mainUrl}/category/macera-filmleri-izle"		    to "Macera",
        "${mainUrl}/category/muzik-filmleri-izle"		    to "Müzik",
        "${mainUrl}/category/polisiye-filmleri-izle"	    to "Polisiye",
        "${mainUrl}/category/politik-filmleri-izle"	        to "Politik",
        "${mainUrl}/category/romantik-filmleri-izle"	    to "Romantik",
        "${mainUrl}/category/savas-filmleri-izle"		    to "Savaş",
        "${mainUrl}/category/spor-filmleri-izle"		    to "Spor",
        "${mainUrl}/category/suc-filmleri-izle"		        to "Suç",
        "${mainUrl}/category/tarih-filmleri-izle"		    to "Tarih",
        "${mainUrl}/category/yerli-filmleri-izle"		    to "Yerli"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "${request.data.removeSuffix("/")}/page/$page/"
        } else {
            request.data
        }
        val document = app.get(url).document
        val movieBoxes = document.select("a.mcard")
        val home = movieBoxes.mapNotNull { 
            it.toSearchResult() 
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.mtx h3")?.text()?.trim()
            ?: this.selectFirst("img.pimg")?.attr("alt")?.takeIf { it.isNotEmpty() }
            ?: return null

        val href = this.attr("href")?.let { fixUrlNull(it) } ?: return null
        val posterUrl = this.selectFirst("img.pimg")?.attr("src")?.let { fixUrlNull(it) }
            ?: this.selectFirst("img.pimg")?.attr("data-src")?.let { fixUrlNull(it) }

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document
        return document.select("a.mcard").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1.sec-h")?.text()?.trim()
            ?: document.selectFirst("h2.sec-h")?.text()?.trim()
            ?: document.selectFirst("[property='og:title']")?.attr("content")?.substringBefore(" - Kült Filmler")?.trim()
            ?: return null
        val title = rawTitle.removeSuffix("İzle").removeSuffix("izle").trim()

        val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("#desc")?.text()?.trim() ?: document.selectFirst("div.description")?.text()?.trim()
        val tags        = document.select("div.genres a").map { it.text() }
        
        val year        = document.selectFirst("a[href*=/yapim/]")?.text()?.trim()?.toIntOrNull()

        val durationText = document.select("div.irow").firstOrNull { 
            it.selectFirst("div.ilabel")?.text()?.contains("Süre", ignoreCase = true) == true 
        }?.selectFirst("div.ivalue")?.text()
        val duration = durationText?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val actors = document.select("a.cmember").mapNotNull {
            val name = it.selectFirst("h5")?.text()?.trim() ?: return@mapNotNull null
            val actorPoster = it.selectFirst("div.av img")?.attr("src")?.let { fixUrlNull(it) }
            Actor(name, actorPoster)
        }

        if (url.contains("/dizi/")) {
            val episodes = document.select("a.ep").mapNotNull {
                val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epText = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epSeason = it.parent()?.attr("data-season")?.toIntOrNull() 
                    ?: Regex("""(\d+)\.\s*Sezon""").find(epText)?.groupValues?.get(1)?.toIntOrNull() 
                    ?: 1
                val epEpisode = Regex("""(\d+)\.\s*Bölüm""").find(epText)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = epText
                    this.season  = epSeason
                    this.episode = epEpisode
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.duration        = duration
                addActors(actors)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.duration        = duration
            addActors(actors)
        }
    }

    private fun extractSubtitleUrl(sourceCode: String): String? {
        val pattern = Pattern.compile("(https?://[^\"\\s]+\\.srt)")
        val matcher = pattern.matcher(sourceCode)
        
        if (matcher.find()) {
            return matcher.group(1)
        }
        return null
    }

    private suspend fun extractSubtitleFromIframe(iframeUrl: String): String? {
        if (iframeUrl.isEmpty()) return null
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
                "Referer" to mainUrl
            )
            val iframeResponse = app.get(iframeUrl, headers=headers)
            return extractSubtitleUrl(iframeResponse.text)
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("KLT", "data » $data")
        val document = app.get(data).document
        val iframes  = mutableSetOf<String>()

        fun cleanUrl(url: String): String {
            if (url.startsWith("//")) return "https:$url"
            return url
        }

        // 1. Default playing iframes
        document.select("div#player iframe").firstOrNull()?.attr("src")?.let { 
            iframes.add(fixUrl(cleanUrl(it))) 
        }
        document.select("div.kf-embed iframe").firstOrNull()?.attr("src")?.let { 
            iframes.add(fixUrl(cleanUrl(it))) 
        }

        // 2. Extract from JSON inside script#kf-srcdata
        val srcJson = document.selectFirst("script#kf-srcdata")?.html()
        if (srcJson != null) {
            Regex("""src=\\?"([^"\\]+)""").findAll(srcJson).forEach { 
                val url = fixUrl(cleanUrl(it.groupValues[1].replace("\\/", "/")))
                if (url.startsWith("http")) {
                    iframes.add(url)
                }
            }
        }

        var foundLinks = false

        for (iframe in iframes) {
            Log.d("KLT", "iframe » $iframe")
            if (iframe.contains("vidmoly")) {
                val headers  = mapOf(
                    "User-Agent"     to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
                    "Sec-Fetch-Dest" to "iframe"
                )
                try {
                    val iSource = app.get(iframe, headers=headers, referer="${mainUrl}/").text
                    val m3uLink = Regex("""file:"([^"]+)""").find(iSource)?.groupValues?.get(1)

                    if (m3uLink != null) {
                        Log.d("Kekik_VidMoly", "m3uLink » $m3uLink")
                        callback.invoke(
                            newExtractorLink(
                                source  = "VidMoly",
                                name    = "VidMoly",
                                url     = m3uLink,
                                type    = INFER_TYPE
                            ) {
                                referer = mainUrl
                                quality = Qualities.Unknown.value
                            }
                        )
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    Log.d("KLT", "VidMoly error: ${e.message}")
                }
            } else if (iframe.contains("vidpapi.xyz") || iframe.contains("vidpapi.com")) {
                val videoId = iframe.split("/").lastOrNull() ?: continue
                try {
                    val iframeResponse = app.get(iframe, headers=mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
                        "Referer" to mainUrl
                    ))
                    
                    val fpCookie = iframeResponse.cookies["fireplayer_player"] ?: ""
                    Log.d("KLT", "Vidpapi cookie: $fpCookie")

                    val apiURL = "https://vidpapi.xyz/player/index.php?data=$videoId&do=getVideo"
                    val apiHeaders = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
                        "Referer" to iframe,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Cookie" to "fireplayer_player=$fpCookie"
                    )

                    val apiResponse = app.post(apiURL, headers=apiHeaders, data=mapOf("data" to videoId, "do" to "getVideo"))
                    val securedLink = Regex("""securedLink":"([^"]+)""").find(apiResponse.text)?.groupValues?.get(1)?.replace("\\/", "/")
                    
                    if (securedLink != null && securedLink.isNotBlank()) {
                        Log.d("KLT", "Found M3U8: $securedLink")
                        callback.invoke(
                            newExtractorLink(
                                source = "Vidpapi",
                                name = "Vidpapi",
                                url = securedLink,
                                type = ExtractorLinkType.M3U8
                            ) {
                                referer = mainUrl
                            }
                        )
                        foundLinks = true
                    }

                    val subtitleUrl = extractSubtitleUrl(iframeResponse.text)
                    if (subtitleUrl != null) {
                        @Suppress("DEPRECATION")
                        subtitleCallback.invoke(SubtitleFile("Türkçe", subtitleUrl))
                    }
                } catch (e: Exception) {
                    Log.d("KLT", "Vidpapi error: ${e.message}")
                }
            } else {
                val subtitleUrl = extractSubtitleFromIframe(iframe)
                if (subtitleUrl != null) {
                    @Suppress("DEPRECATION")
                    subtitleCallback.invoke(SubtitleFile("Türkçe", subtitleUrl))
                }
                if (loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)) {
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }
}
