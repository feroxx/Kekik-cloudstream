package com.keyiflerolsun

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.StringUtils.decodeUri
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DiziBox : MainAPI() {
    override var mainUrl              = "https://www.dizibox.live"
    override var name                 = "DiziBox"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

    private val baseCookies = mapOf(
        "LockUser"      to "true",
        "isTrustedUser" to "true",
        "dbxu"          to "1744054959089"
    )

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller, userAgent) }

    private val posterHeaders by lazy {
        mapOf(
            "User-Agent" to userAgent,
            "Referer"    to "$mainUrl/",
            "Cookie"     to baseCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        )
    }

    private fun getImage(element: Element?): String? {
        val img = element?.selectFirst("img.main-cover, img.wp-post-image, img.afis, figure img, img:not([class*='language'])")
            ?: element?.selectFirst("img") ?: return null
        val raw = img.attr("data-src").takeIf { it.isNotBlank() }
            ?: img.attr("data-original").takeIf { it.isNotBlank() }
            ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        return fixUrlNull(raw)
    }

    class CloudflareInterceptor(
        private val cloudflareKiller: CloudflareKiller,
        private val userAgent: String
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .build()
            val response   = chain.proceed(request)
            val bodySample = response.peekBody(10 * 1024).string()
            if (
                bodySample.contains("Güvenlik taramasından geçiriliyorsunuz")
                || bodySample.contains("cf-browser-verification")
                || bodySample.contains("Checking your browser")
                || bodySample.contains("just a moment", ignoreCase = true)
                || response.code in listOf(403, 503, 429)
            ) {
                response.close()
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler/page/SAYFA/"                        to "Son Bölümler",
        "${mainUrl}/tum-bolumler/page/SAYFA/?tip=populer"            to "Popüler Diziler",
        "${mainUrl}/dizi-arsivi/page/SAYFA/"                         to "Yeni Eklenenler",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?ulke[]=turkiye&yil=&imdb" to "Yerli Diziler",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=aksiyon&yil&imdb" to "Aksiyon",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=bilimkurgu&yil&imdb" to "Bilimkurgu",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=komedi&yil&imdb"  to "Komedi",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=dram&yil&imdb"    to "Dram",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=fantastik&yil&imdb" to "Fantastik",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=gerilim&yil&imdb" to "Gerilim",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=korku&yil&imdb"   to "Korku",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=romantik&yil&imdb" to "Romantik",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur[0]=animasyon&yil&imdb" to "Animasyon"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rawUrl = request.data
        val url = if (page == 1) {
            if (rawUrl.contains("?")) {
                rawUrl.substringBefore("/page/SAYFA/") + "/?" + rawUrl.substringAfter("?")
            } else {
                rawUrl.substringBefore("/page/SAYFA/") + "/"
            }
        } else {
            rawUrl.replace("SAYFA", "$page")
        }

        val document = app.get(
            url,
            cookies     = baseCookies,
            interceptor = interceptor,
            cacheTime   = 60
        ).document

        val home = document.select("article.detailed-article, article.article-episode-card")
            .mapNotNull { it.toMainPageResult() }

        val isHorizontal = request.name.contains("Bölümler") || request.name.contains("Popüler")
        return newHomePageResponse(listOf(HomePageList(request.name, home, isHorizontal)), home.isNotEmpty())
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val titleEl = this.selectFirst("h3 a, a.episode-card-title, a.season-episode")
        val title   = titleEl?.attr("title")?.takeIf { it.isNotBlank() }
            ?: titleEl?.text()?.trim()
            ?: this.selectFirst("img.main-cover, img.afis, img.wp-post-image, figure img, img")?.attr("alt")?.trim()
            ?: return null
        val imgUrl  = getImage(this)
        val href    = fixUrlNull(titleEl?.attr("href") ?: this.selectFirst("a.figure-link")?.attr("href") ?: this.selectFirst("a")?.attr("href") ?: this.attr("href")) ?: return null
        val rating  = this.selectFirst("span.label-imdb b")?.text()?.trim()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl     = imgUrl
            this.posterHeaders = posterHeaders
            this.score         = Score.from10(rating)
        }
    }

    data class SearchApiResponse(@JsonProperty("results") val results: List<SearchResult> = emptyList())
    data class SearchResult(
        @JsonProperty("post_title")          val postTitle           : String  = "",
        @JsonProperty("permalink")           val permalink           : String  = "",
        @JsonProperty("attachment_thumbnail") val attachmentThumbnail: String? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val url      = "$mainUrl/wp-admin/admin-ajax.php?s=$query&action=dwls_search"
        val response = app.get(
            url,
            headers     = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer"          to "$mainUrl/?s=$query"
            ),
            cookies     = baseCookies,
            interceptor = interceptor
        )
        if (response.isSuccessful) {
            val json = tryParseJson<SearchApiResponse>(response.text)
            if (json != null && json.results.isNotEmpty()) {
                return json.results.mapNotNull { result ->
                    val thumbnail = result.attachmentThumbnail?.replace("50x50", "200x290")
                    newTvSeriesSearchResponse(result.postTitle, result.permalink, TvType.TvSeries) {
                        this.posterUrl     = fixUrlNull(thumbnail)
                        this.posterHeaders = posterHeaders
                    }
                }
            }
        }

        val doc = app.get(
            "$mainUrl/?s=$query",
            cookies     = baseCookies,
            interceptor = interceptor
        ).document

        return doc.select("article.detailed-article").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        var document = app.get(
            url,
            cookies     = baseCookies,
            interceptor = interceptor
        ).document

        val archiveLink = document.selectFirst("div#archive-box a.archive-title")?.attr("href")
        if (!archiveLink.isNullOrBlank()) {
            val redirectUrl = fixUrlNull(archiveLink) ?: url
            document = app.get(
                redirectUrl,
                cookies     = baseCookies,
                interceptor = interceptor
            ).document
        }

        val title    = document.selectFirst("div.tv-overview h1 a")?.text()?.trim() ?: return null
        val poster   = getImage(document.selectFirst("div.tv-overview figure"))?.replace("220x140", "200x290")?.replace("50x50", "200x290")
        val plot     = document.selectFirst("div.tv-story p")?.text()?.trim()
        val year     = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val tags     = document.select("a[href*='/tur/']").map { it.text() }
        val actors   = document.select("a[href*='/oyuncu/']").map { Actor(it.text()) }
        val trailer  = document.selectFirst("div.tv-overview iframe")?.attr("src")
        val rating   = document.selectFirst("span.label-imdb b")?.text()?.trim()

        val seasonLinks = document.select("div#seasons-list a").mapNotNull {
            fixUrlNull(it.attr("href"))
        }

        val episodeList = mutableListOf<Episode>()
        seasonLinks.forEach { seasonUrl ->
            val seasonDoc = app.get(
                seasonUrl,
                cookies     = baseCookies,
                interceptor = interceptor
            ).document

            seasonDoc.select("article.grid-box").forEach epLoop@{ epElem ->
                val epTitleEl = epElem.selectFirst("div.post-title a.season-episode") ?: return@epLoop
                val epRawTitle = epTitleEl.text().trim()
                val epHref     = fixUrlNull(epTitleEl.attr("href")) ?: return@epLoop

                val dateEl   = epElem.selectFirst("small.date")
                val dateText = dateEl?.text()?.trim()?.let { d ->
                    d.replace("Ocak", "01").replace("Şubat", "02").replace("Mart", "03")
                     .replace("Nisan", "04").replace("Mayıs", "05").replace("Haziran", "06")
                     .replace("Temmuz", "07").replace("Ağustos", "08").replace("Eylül", "09")
                     .replace("Ekim", "10").replace("Kasım", "11").replace("Aralık", "12")
                }

                val sNum = Regex("""(\d+)\.? ?Sezon""").find(epRawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val eNum = Regex("""(\d+)\.? ?Bölüm""").find(epRawTitle)?.groupValues?.get(1)?.toIntOrNull()

                episodeList.add(newEpisode(epHref) {
                    this.name      = "Bölüm"
                    this.season    = sNum
                    this.episode   = eNum
                    this.posterUrl = poster
                    if (dateText != null) {
                        val parts = dateText.split(" ")
                        if (parts.size >= 3) {
                            val formatted = "${parts[2]}-${parts[1].padStart(2, '0')}-${parts[0].padStart(2, '0')}"
                            addDate(formatted)
                        }
                    }
                })
            }
        }

        val sortedEpisodes = episodeList.sortedWith(compareBy({ it.season }, { it.episode }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
            this.posterUrl     = poster
            this.posterHeaders = posterHeaders
            this.plot          = plot
            this.year          = year
            this.tags          = tags
            this.score         = Score.from10(rating)
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data             : String,
        isCasting        : Boolean,
        subtitleCallback : (SubtitleFile) -> Unit,
        callback         : (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            cookies     = baseCookies,
            interceptor = interceptor
        ).document

        val iframeUrl = document.selectFirst("div#video-area iframe")?.attr("src")
            ?: return false

        processIframe(iframeUrl, data, data, subtitleCallback, callback)

        document.select("div.video-toolbar option[value]").forEach { opt ->
            val altUrl = fixUrlNull(opt.attr("value")) ?: return@forEach
            if (altUrl == data) return@forEach

            val sourceDoc = app.get(
                altUrl,
                cookies     = baseCookies,
                interceptor = interceptor
            ).document

            val srcIframe = sourceDoc.selectFirst("div#video-area iframe")?.attr("src")
                ?: return@forEach

            processIframe(srcIframe, altUrl, data, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun processIframe(
        iframeUrl        : String,
        sourceUrl        : String,
        referer          : String,
        subtitleCallback : (SubtitleFile) -> Unit,
        callback         : (ExtractorLink) -> Unit
    ) {
        when {
            iframeUrl.contains("/player/moly/moly.php") || iframeUrl.contains("/player/haydi.php") -> {
                val molyUrl = when {
                    iframeUrl.contains("moly.php") -> iframeUrl.replace("moly.php?h=", "moly.php?wmode=opaque&h=")
                    else                           -> iframeUrl.replace("haydi.php?v=", "haydi.php?wmode=opaque&v=")
                }
                var subDoc = app.get(
                    molyUrl,
                    referer     = referer,
                    cookies     = baseCookies,
                    interceptor = interceptor
                ).document

                val atobData = Regex("""unescape\("(.*)"\)""").find(subDoc.html())?.groupValues?.get(1)
                if (atobData != null) {
                    val decoded = String(Base64.decode(atobData.decodeUri(), Base64.DEFAULT), Charsets.UTF_8)
                    subDoc = Jsoup.parse(decoded)
                }

                val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src") ?: return
                loadExtractor(subFrame, "$mainUrl/", subtitleCallback, callback)
            }

            iframeUrl.contains("/player/king/king.php") || iframeUrl.contains("php?v=") -> {
                val playerUrl = iframeUrl.replace("php?v=", "php?wmode=opaque&v=")
                val playerDoc = app.get(
                    playerUrl,
                    referer     = sourceUrl,
                    cookies     = baseCookies,
                    interceptor = interceptor
                ).document

                val finalEmbed = playerDoc.selectFirst("div#Player iframe")?.attr("src")
                    ?: return

                if (finalEmbed.contains("dbx.molystream")) {
                    val sheila   = finalEmbed.replace("/embed/", "/embed/sheila/")
                    val m3u8Data = app.get(
                        sheila,
                        referer     = finalEmbed,
                        interceptor = interceptor
                    ).text
                    val m3u8Url = m3u8Data.lineSequence().firstOrNull { it.startsWith("http") }
                    if (m3u8Url != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name   = name,
                                url    = m3u8Url,
                                type   = ExtractorLinkType.M3U8
                            ) {
                                this.headers = mapOf("Referer" to finalEmbed)
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return
                    }

                    val iDoc      = app.get(finalEmbed, referer = "$mainUrl/", interceptor = interceptor).text
                    val cryptData = Regex("""CryptoJS\.AES\.decrypt\("(.*)",""").find(iDoc)?.groupValues?.get(1) ?: return
                    val cryptPass = Regex("""","(.*)"\);""").find(iDoc)?.groupValues?.get(1) ?: return
                    val decrypted = CryptoJS.decrypt(cryptPass, cryptData)
                    val vidUrl    = Regex("""file: '(.*)',""").find(Jsoup.parse(decrypted).html())?.groupValues?.get(1) ?: return

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name   = name,
                            url    = vidUrl,
                            type   = ExtractorLinkType.M3U8
                        ) {
                            this.headers = mapOf("Referer" to vidUrl)
                            this.quality = Qualities.P1080.value
                        }
                    )
                } else {
                    val cleanEmbed = finalEmbed.replace("vidmoly.me", "vidmoly.net")
                    loadExtractor(cleanEmbed, sourceUrl, subtitleCallback, callback)
                }
            }

            else -> {
                loadExtractor(iframeUrl, sourceUrl, subtitleCallback, callback)
            }
        }
    }
}
