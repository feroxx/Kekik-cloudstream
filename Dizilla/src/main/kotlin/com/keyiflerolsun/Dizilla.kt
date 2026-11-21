// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.newSubtitleFile


class Dizilla : MainAPI() {
    override var mainUrl = "https://dizilla.la"
    override var name = "Dizilla"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override var sequentialMainPage =
        true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay = 150L  // ? 0.15 saniye
    override var sequentialMainPageScrollDelay = 150L  // ? 0.15 saniye

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("verifying")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val supportedSyncNames = setOf(
        SyncIdName.Simkl
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/arsiv" to "Yeni Eklenen Diziler",
        "${mainUrl}/yerli-dizi-izle" to "Yerli Dizi",
        "${mainUrl}/yabanci-dizi-izle" to "Yabancı Dizi",
        "${mainUrl}/arsiv?sirala=imdb" to "IMDB Puanına göre"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            println("Dizilla DEBUG - getMainPage: ${request.data}, page: $page")
            if (request.data.contains("/arsiv?sirala=imdb")) {
                val document = app.get("${request.data}&page=$page", interceptor = interceptor).document;
                val home = document.select(".v4-pgrid li").mapNotNull { it.toMainPageResult() }
                return newHomePageResponse(request.name, home)
            } else {
                val document = app.get("${request.data}?page=$page", interceptor = interceptor).document;
                val home = document.select(".v4-pgrid li").mapNotNull { it.toMainPageResult() }
                return newHomePageResponse(request.name, home)
            }
        } catch (e: Exception) {
            println("Dizilla DEBUG - getMainPage exception: ${e.message}")
            e.printStackTrace()
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("span.mvk-t")?.text() ?: return null
        val imgUrl = this.selectFirst("img")?.attr("src") ?: return null
        val href = this.selectFirst("a.pcard")?.attr("href") ?: return null

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = imgUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            "${mainUrl}/ara?q=$query",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36 Edg/156.0.0.0",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            )
        ).document;
        val home = document.select(".v4-pgrid li").mapNotNull { it.toMainPageResult() }

        return home
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document;
        val title = document.selectFirst("div h1")?.text() ?: return null
        val poster = document.selectFirst("section.mobhero img")?.attr("src")
        val year = document.select("dl.frows > div")
            .firstOrNull { it.selectFirst("dt")?.text() == "İlk yayın" }
            ?.selectFirst("dd")
            ?.text()
            ?.toIntOrNull()
        val description = document.selectFirst("section#hakkinda h2#hk-b + p ")?.text()?.trim()

        val episodeses = mutableListOf<Episode>()

        val script = document.select("script")
            .firstOrNull {
                it.html().contains("episodes")
            }
            ?.html()
            ?: return null

// Script içindeki JSON string'ini al
        val raw = script.substringAfter("self.__next_f.push([1,\"")
            .substringBefore("\"])")

// Kaçış karakterlerini çöz
        val decoded = raw
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")

// episodes dizisini bul
        val episodesJson = decoded.substringAfter("\"episodes\":[")
            .substringBefore("],\"seasons\"")

        val episodes = org.json.JSONArray("[$episodesJson]")

// Bölümleri listele
        for (i in 0 until episodes.length()) {
            val episode = episodes.getJSONObject(i)

            val title = episode.optString("title")
            val link = episode.optString("url")
            val season = episode.optInt("s")
            val episodeNumber = episode.optInt("n")
            val description = episode.optString("summary")
            val poster = episode.optString("still")
            val score = episode.optString("tmdb")
            val releaseMs = episode.optString("releaseMs")

            val newEpisode = newEpisode(link) {
                this.name = title
                this.season = season
                this.episode = episodeNumber
                this.posterUrl = poster
                this.description = description
                this.score = Score.from10(score)
                this.date = releaseMs?.toLongOrNull()
            }
            episodeses.add(newEpisode)
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeses) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(
            data,
            interceptor = interceptor,
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36 Edg/156.0.0.0",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            )
        )
        val document = response.document

        val script = document.select("script")
            .firstOrNull {
                it.html().contains("iframe")
            }
            ?.html()
            ?: return false

// Script içindeki JSON string'ini al
        val raw = script.substringAfter("self.__next_f.push([1,\"")
            .substringBefore("\"])")

// Kaçış karakterlerini çöz
        val decoded = raw
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")

// sources dizisini bul
        val sourcesJson = decoded
            .substringAfter("\"sources\":[", "")
            .substringBefore("],\"hosts\"", "")

        if (sourcesJson.isBlank()) return false

        val sources = org.json.JSONArray("[$sourcesJson]")

        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)

            val name = source.optString("name")
            val src = source.optString("src")
            if (src.isBlank()) continue

            val iframeResponse = app.get(
                src,
                interceptor = interceptor,
                headers = mapOf(
                    "Referer" to response.url,
                    "User-Agent" to
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/156.0.0.0 Safari/537.36 Edg/156.0.0.0",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                )
            )
            val iframeHtml = iframeResponse.text
            val domainRegex = """https?://[^/]+""".toRegex()
            val domain = domainRegex.find(iframeResponse.url)?.value ?: "https://four.pichive.online"

            val configJson = Regex("""window\.NP_CONFIG\s*=\s*(\{.*?\});\s*window\.""", RegexOption.DOT_MATCHES_ALL)
                .find(iframeHtml)
                ?.groupValues
                ?.get(1)
                ?: Regex("""window\.NP_CONFIG\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
                    .find(iframeHtml)
                    ?.groupValues
                    ?.get(1)
                ?: continue

            val config = org.json.JSONObject(configJson)
            val gateObj = config.optJSONObject("gate")
            val gateN = gateObj?.optString("n")
            val gateB = gateObj?.optInt("b", 12) ?: 12
            val token = if (!gateN.isNullOrEmpty()) {
                calculateGateToken(iframeHtml, gateN, gateB)
            } else {
                ""
            }

            val subtitles = config.optJSONArray("subtitles")
            if (subtitles != null) {
                for (j in 0 until subtitles.length()) {
                    val subtitle = subtitles.optJSONObject(j) ?: continue

                    val subtitleLang = subtitle.optString("label", "Altyazı")
                    var subtitleUrl = subtitle.optString("url")
                    if (subtitleUrl.isBlank()) continue
                    if (token.isNotEmpty()) {
                        val sep = if (subtitleUrl.contains("?")) "&" else "?"
                        subtitleUrl += "${sep}p=$token"
                    }

                    subtitleCallback.invoke(
                        newSubtitleFile(
                            lang = subtitleLang,
                            url = subtitleUrl
                        ) {
                            this.headers = mapOf(
                                "Origin" to domain,
                                "Referer" to iframeResponse.url,
                                "User-Agent" to
                                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/156.0.0.0 Safari/537.36 Edg/156.0.0.0",
                                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                            )
                        }
                    )
                }
            }

            val innerSources = config.optJSONArray("sources") ?: continue
            for (j in 0 until innerSources.length()) {
                val srcObj = innerSources.optJSONObject(j) ?: continue
                var apiUrl = srcObj.optString("master")
                if (apiUrl.isBlank()) continue
                if (token.isNotEmpty()) {
                    val sep = if (apiUrl.contains("?")) "&" else "?"
                    apiUrl += "${sep}p=$token"
                }

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = name,
                        url = apiUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = iframeResponse.url
                        headers = mapOf(
                            "Origin" to domain,
                            "Referer" to iframeResponse.url,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36 Edg/156.0.0.0",
                            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
                        )
                    }
                )
            }
        }
        return true;
    }

    private fun calculateGateToken(html: String, gateN: String, gateB: Int): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val gateMatch = Regex("""window\.NP_GATE\s*=\s*function\s*\(([^)]+)\)\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL).find(html)

            var radix = 10
            var wordIdx = 0
            var mask: Int? = null
            var shift = 32 - gateB
            var prefix = "$gateN:"
            var suffix = ""

            if (gateMatch != null) {
                val params = gateMatch.groupValues[1].split(",").map { it.trim() }
                val pN = params.getOrNull(0) ?: "_ri"
                val pHash = params.getOrNull(1) ?: "_mor"
                val body = gateMatch.groupValues[2]

                val saltMatch = Regex("""var\s+([A-Za-z0-9_$]+)\s*=\s*String\.fromCharCode\(([\d,\s]+)\)""").find(body)
                val saltVar = saltMatch?.groupValues?.get(1) ?: ""
                val saltVal = saltMatch?.groupValues?.get(2)?.split(",")
                    ?.mapNotNull { it.trim().toIntOrNull()?.toChar() }
                    ?.joinToString("") ?: ""

                val radixMatch = Regex("""var\s+([A-Za-z0-9_$]+)\s*=\s*[A-Za-z0-9_$]+\.toString\((\d+)\)""").find(body)
                val nonceVar = radixMatch?.groupValues?.get(1) ?: ""
                radix = radixMatch?.groupValues?.get(2)?.toIntOrNull() ?: 10

                val callMatch = Regex(Regex.escape(pHash) + """\(([^)]+)\)\[(\d+)\]""").find(body)
                val exprStr = callMatch?.groupValues?.get(1) ?: "$pN+$saltVar+$nonceVar+$saltVar"
                wordIdx = callMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0

                mask = Regex("""\]\s*&\s*(\d+)\s*\)\s*===\s*0""").find(body)?.groupValues?.get(1)?.toIntOrNull()
                val shiftMatch = Regex("""\]\s*>>>\s*(\d+)\s*\)\s*===\s*0""").find(body)?.groupValues?.get(1)?.toIntOrNull()
                if (shiftMatch != null) shift = shiftMatch

                val parts = exprStr.split("+").map { it.trim() }
                val preBuilder = StringBuilder()
                val sufBuilder = StringBuilder()
                var seenNonce = false
                for (p in parts) {
                    if (p == nonceVar && nonceVar.isNotEmpty()) {
                        seenNonce = true
                        continue
                    }
                    val resolved = when {
                        p == pN -> gateN
                        p == saltVar && saltVar.isNotEmpty() -> saltVal
                        (p.startsWith("'") && p.endsWith("'")) || (p.startsWith("\"") && p.endsWith("\"")) -> p.substring(1, p.length - 1)
                        else -> ""
                    }
                    if (!seenNonce) preBuilder.append(resolved) else sufBuilder.append(resolved)
                }
                if (seenNonce) {
                    prefix = preBuilder.toString()
                    suffix = sufBuilder.toString()
                }
            }

            val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
            val suffixBytes = suffix.toByteArray(Charsets.UTF_8)
            val byteOffset = (wordIdx.coerceIn(0, 7)) * 4

            for (i in 0 until 10_000_000) {
                val nonce = i.toString(radix)
                md.reset()
                md.update(prefixBytes)
                md.update(nonce.toByteArray(Charsets.UTF_8))
                if (suffixBytes.isNotEmpty()) md.update(suffixBytes)
                val digest = md.digest()
                val word = ((digest[byteOffset].toInt() and 0xFF) shl 24) or
                           ((digest[byteOffset + 1].toInt() and 0xFF) shl 16) or
                           ((digest[byteOffset + 2].toInt() and 0xFF) shl 8) or
                           (digest[byteOffset + 3].toInt() and 0xFF)
                val matched = if (mask != null) (word and mask) == 0 else (word ushr shift) == 0
                if (matched) return nonce
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }
}