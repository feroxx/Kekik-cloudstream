package com.keyiflerolsun

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val PRIVATE_AES_KEY = "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI"
private val jacksonMapper = ObjectMapper()

class SelcukFlix : MainAPI() {
    override var mainUrl              = "https://selcukflix.app"
    override var name                 = "SelcukFlix"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(10 * 1024).string())
            if (response.code == 503
                || doc.html().contains("Just a moment")
                || doc.html().contains("verifying")
                || doc.selectFirst("meta[name='cloudflare']") != null
            ) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/film-izle"    to "Yeni Eklenen Filmler",
        //"$mainUrl/seri-filmler" to "Seri Filmler",
        "$mainUrl/dizi-izle"    to "Yeni Diziler",
    )

    private fun decryptAES(encryptedData: String): String? {
        if (encryptedData.isBlank()) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val bytes  = PRIVATE_AES_KEY.toByteArray(Charsets.UTF_8)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(bytes, "AES"), IvParameterSpec(ByteArray(16)))
            String(cipher.doFinal(Base64.decode(encryptedData, 0)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun decodeSecureData(secureData: String): String? {
        return if (secureData.startsWith("eyJ")) {
            try { String(Base64.decode(secureData, 0), Charsets.UTF_8) } catch (_: Exception) { null }
        } else {
            decryptAES(secureData)
        }
    }

    private fun extractSecureData(html: String): String? {
        return try {
            val doc    = Jsoup.parse(html)
            val script = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
            val root   = jacksonMapper.readTree(script)
            root?.get("props")?.get("pageProps")?.get("secureData")?.asText()
        } catch (_: Exception) { null }
    }

    private fun fixPosterUrl(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        var url = raw
            .replace("images-macellan-online.cdn.ampproject.org/i/s/", "")
        url = Regex("file\\.[\\w.]+/").replace(url, "file.macellan.online/")
        url = Regex("images\\.[\\w.]+/").replace(url, "images.macellan.online/")
        url = url.replace("/f/f/", "/630/910/")
        return fixUrlNull(url)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data  = request.data
        val items = mutableListOf<SearchResponse>()

        val url = if (page > 1) "$data?page=$page" else data
        val doc = app.get(url, interceptor = interceptor).document

        val isSeries = data.contains("/dizi-izle") || data.contains("/dizi/")
        val isMovie  = data.contains("/film-izle") || data.contains("/film/")
        val isSeri   = data.contains("/seri-filmler")

        val cardSelector = when {
            isSeries -> "a[href*=/dizi/]"
            isMovie  -> "a[href*=/film/]"
            isSeri   -> "a[href*=/film/], a[href*=/seri-filmler/]"
            else     -> "a[href*=/film/], a[href*=/dizi/]"
        }

        doc.select(cardSelector).forEach { el ->
            val href  = fixUrlNull(el.attr("href")) ?: return@forEach
            if (href == "$mainUrl/film-izle"
                || href == "$mainUrl/dizi-izle"
                || href == "$mainUrl/seri-filmler") return@forEach

            val img   = el.selectFirst("img")
            val title = el.selectFirst("h2,h3")?.text()
                ?: img?.attr("alt")?.replace(Regex("\\d+\\.\\s*(Sezon|Bölüm)|izle", RegexOption.IGNORE_CASE), "")?.trim()
                ?: return@forEach
            if (title.isBlank()) return@forEach

            val poster = fixPosterUrl(
                img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
            )

            if (href.contains("/dizi/")) {
                items.add(newTvSeriesSearchResponse(title, href.substringBefore("/sezon"), TvType.TvSeries) { posterUrl = poster })
            } else {
                items.add(newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster })
            }
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        try {
            val searchUrl = "$mainUrl/api/mj"
            val payload = """{"action":"searchContentFullText","params":[${jacksonMapper.writeValueAsString(query)}]}"""
            val requestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val response = app.post(
                url = searchUrl,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Accept-Language" to "tr,en-GB;q=0.9,en;q=0.8,en-US;q=0.7,tr-TR;q=0.6",
                    "Content-Type" to "application/json",
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/arama-yap?s=${query.replace(" ", "+")}",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin",
                    "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Safari/537.36 Edg/158.0.0.0"
                ),
                requestBody = requestBody,
                referer = "$mainUrl/arama-yap?s=${query.replace(" ", "+")}",
                interceptor = interceptor
            ).text

            val rootNode: JsonNode? = try {
                jacksonMapper.readTree(response)
            } catch (_: Exception) {
                null
            }

            val encryptedData = rootNode?.get("response")?.asText()?.takeIf { it.isNotBlank() }
            val decoded: String? = if (encryptedData != null) {
                try {
                    decryptAES(encryptedData)?.trim()?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

// decoded varsa çözülmüş JSON'u, yoksa ham response/rootNode yapısını kullan
            val targetNode: JsonNode? = when {
                decoded != null -> try {
                    jacksonMapper.readTree(decoded)
                } catch (_: Exception) {
                    null
                }
                rootNode != null -> rootNode.get("response")?.takeIf { !it.isTextual } ?: rootNode
                else -> null
            }

            val itemsArray: JsonNode? = when {
                targetNode == null -> null
                targetNode.isArray -> targetNode
                targetNode.has("result") && targetNode.get("result").isArray -> targetNode.get("result")
                targetNode.has("results") && targetNode.get("results").isArray -> targetNode.get("results")
                targetNode.has("data") && targetNode.get("data").isArray -> targetNode.get("data")
                else -> null
            }

            itemsArray?.forEach { item: JsonNode ->
                val title = item.get("object_name")?.asText()?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                val slug = item.get("used_slug")?.asText()?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                val poster = item.get("object_poster_url")?.asText()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { fixUrl(it) }
                val type = item.get("used_type")?.asText()?.trim() ?: ""
                val href = fixUrl(slug)

                if (!href.contains("/seri-filmler/")) {
                    if (type.equals("Movies", ignoreCase = true) || type.equals("Movie", ignoreCase = true)) {
                        results.add(newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster })
                    } else {
                        results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster })
                    }
                }
            }
        } catch (_: Exception) {}

        if (results.isEmpty()) {
            try {
                val doc = app.get("$mainUrl/arama?q=$query", interceptor = interceptor).document
                doc.select("a[href^=/film/], a[href^=/dizi/]").forEach { el ->
                    val href  = fixUrlNull(el.attr("href")) ?: return@forEach
                    if (href == "$mainUrl/film-izle" || href == "$mainUrl/dizi-izle") return@forEach
                    val img   = el.selectFirst("img")
                    val title = el.selectFirst("h2,h3")?.text()
                        ?: img?.attr("alt")?.replace(" izle", "")?.trim()
                        ?: return@forEach
                    val poster = fixPosterUrl(img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src"))
                    if (href.contains("/dizi/")) {
                        results.add(newTvSeriesSearchResponse(title, href.substringBefore("/sezon"), TvType.TvSeries) { posterUrl = poster })
                    } else {
                        results.add(newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster })
                    }
                }
            } catch (_: Exception) {}
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val html     = app.get(url, interceptor = interceptor).text
        val isSeries = url.contains("/dizi/")

        var title       = Jsoup.parse(html).selectFirst("h1")?.text() ?: ""
        var poster      : String? = null
        var bgPoster    : String? = null
        var description : String? = null
        var year        : Int?    = null
        var tags        : List<String>? = null
        val episodes    = mutableListOf<Episode>()

        val secureDataRaw = extractSecureData(html)
        if (secureDataRaw != null) {
            val jsonText = decodeSecureData(secureDataRaw)
            if (jsonText != null) {
                try {
                    val json: JsonNode = jacksonMapper.readTree(jsonText)

                    val item: JsonNode? = json.get("contentItem")
                    if (item != null) {
                        val origTitle = item.get("original_title")?.asText()
                        if (!origTitle.isNullOrBlank() && origTitle != "null" && title.isBlank()) title = origTitle

                        val pUrl = item.get("poster_url")?.asText()
                        if (!pUrl.isNullOrBlank() && pUrl != "null") poster = pUrl

                        val bUrl = item.get("back_url")?.asText()
                        if (!bUrl.isNullOrBlank() && bUrl != "null") bgPoster = bUrl

                        val desc = item.get("description")?.asText()
                        if (!desc.isNullOrBlank() && desc != "null") {
                            description = desc.replace("\\n", "\n").replace("\\r", "").replace("\\", "")
                        }

                        val yearNode = item.get("release_year")
                        if (yearNode != null && !yearNode.isNull) year = yearNode.asInt().takeIf { it > 0 }

                        val cats = item.get("categories")?.asText()
                        if (!cats.isNullOrBlank() && cats != "null") {
                            tags = cats.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        }
                    }

                    if (isSeries) {
                        val seasons: JsonNode? = json.get("RelatedResults")
                            ?.get("getSerieSeasonAndEpisodes")
                            ?.get("result")
                        seasons?.forEach { season: JsonNode ->
                            val sNum = season.get("season_no")?.asInt() ?: return@forEach
                            season.get("episodes")?.forEach { ep: JsonNode ->
                                val eNum   = ep.get("episode_no")?.asInt() ?: return@forEach
                                val epText = ep.get("episode_text")?.asText()?.takeIf { it.isNotBlank() } ?: "Bölüm $eNum"
                                val epSlug = ep.get("used_slug")?.asText() ?: return@forEach
                                val epUrl  = fixUrl(epSlug)
                                episodes.add(newEpisode(epUrl) {
                                    this.name    = epText
                                    this.season  = sNum
                                    this.episode = eNum
                                })
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        poster   = fixPosterUrl(poster)
        bgPoster = fixPosterUrl(bgPoster)

        if (isSeries && episodes.isEmpty()) {
            Jsoup.parse(html).select("a[href*=/sezon]").forEach { link ->
                val epUrl  = fixUrlNull(link.attr("href")) ?: return@forEach
                val epTxt  = link.text().takeIf { it.isNotEmpty() }
                    ?: link.selectFirst("h2,h3,span")?.text() ?: "Bölüm"
                val sMatch = Regex("/sezon-([0-9]+)").find(epUrl)
                val eMatch = Regex("/bolum-([0-9]+)").find(epUrl)
                val sNum   = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                val eNum   = eMatch?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                episodes.add(newEpisode(epUrl) {
                    this.name    = epTxt
                    this.season  = sNum
                    this.episode = eNum
                })
            }
        }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                posterUrl           = poster
                backgroundPosterUrl = bgPoster ?: poster
                plot                = description
                this.year           = year
                this.tags           = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl           = poster
                backgroundPosterUrl = bgPoster ?: poster
                plot                = description
                this.year           = year
                this.tags           = tags
            }
        }
    }

    override suspend fun loadLinks(
        data             : String,
        isCasting        : Boolean,
        subtitleCallback : (SubtitleFile) -> Unit,
        callback         : (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, interceptor = interceptor).text

        val secureDataRaw = extractSecureData(html) ?: return false
        val jsonText      = decodeSecureData(secureDataRaw) ?: return false
        val json: JsonNode = try { jacksonMapper.readTree(jsonText) } catch (_: Exception) { return false }
        val related: JsonNode = json.get("RelatedResults") ?: return false

        val sourceContent: String? = if (data.contains("/dizi/") || data.contains("/bolum-")) {
            related.get("getEpisodeSources")
                ?.get("result")
                ?.get(0)
                ?.get("source_content")
                ?.asText()
        } else {
            var content: String? = null

            val firstPartId = related.get("getMoviePartsById")
                ?.get("result")?.get(0)?.get("id")?.asInt()

            if (firstPartId != null) {
                content = related.get("getMoviePartSourcesById_$firstPartId")
                    ?.get("result")?.get(0)?.get("source_content")?.asText()
            }
            if (content.isNullOrBlank()) {
                content = related.get("getMoviePartSourcesById")
                    ?.get("result")?.get(0)?.get("source_content")?.asText()
            }
            content
        }

        if (sourceContent.isNullOrBlank()) return false

        val iframeEl  = Jsoup.parse(sourceContent).selectFirst("iframe")
        val iframeUrl = iframeEl?.attr("src") ?: return false
        var finalUrl  = fixUrlNull(iframeUrl) ?: return false

        finalUrl = finalUrl
            .replace("sn.dplayer74.site", "sn.hotlinger.com")
            .replace("sn.dplayer82.site", "sn.hotlinger.com")
            .replace("sn.dplayer.site",   "sn.hotlinger.com")

        loadExtractor(finalUrl, data, subtitleCallback, callback)
        return true
    }
}
