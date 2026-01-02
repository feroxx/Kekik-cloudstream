// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec


class Dizilla : MainAPI() {
    override var mainUrl = "https://dizilla.to"
    override var name = "Dizilla"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 150L  // ? 0.15 saniye
    override var sequentialMainPageScrollDelay = 150L  // ? 0.15 saniye

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    private val privateAESKey = "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI"

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

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
        "${mainUrl}/tum-bolumler" to "Yeni Eklenen Bölümler",
        "${mainUrl}/arsiv" to "Yeni Eklenen Diziler",
        "${mainUrl}/arsiv?page=1&tab=1&sort=date_desc&filterType=2&imdbMin=5&imdbMax=10&yearMin=1900&yearMax=2024&categories=15" to "Aile",
        "${mainUrl}/dizi-turu/aksiyon" to "Aksiyon",
        "${mainUrl}/dizi-turu/bilim-kurgu" to "Bilim Kurgu",
        "${mainUrl}/dizi-turu/dram" to "Dram",
        "${mainUrl}/dizi-turu/fantastik" to "Fantastik",
        "${mainUrl}/dizi-turu/gerilim" to "Gerilim",
        "${mainUrl}/dizi-turu/komedi" to "Komedi",
        "${mainUrl}/dizi-turu/korku" to "Korku",
        "${mainUrl}/dizi-turu/macera" to "Macera",
        "${mainUrl}/dizi-turu/romantik" to "Romantik",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var document = Jsoup.parse(app.get(request.data, interceptor = interceptor).body.string())
        val home = if (request.data.contains("dizi-turu")) {
            document.select("span.watchlistitem-").mapNotNull { it.diziler() }
        } else if (request.data.contains("/arsiv")) {
            val response = app.get("${request.data}?page=$page", interceptor = interceptor)
            val document = response.document
    
            val script = document.selectFirst("script#__NEXT_DATA__")?.data()
            val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    
            val secureData = objectMapper.readTree(script)
                .get("props")?.get("pageProps")?.get("secureData")?.asText()
                ?: return newHomePageResponse(request.name, emptyList())
    
            val decodedData = decryptDizillaResponse(secureData)
                ?: return newHomePageResponse(request.name, emptyList())
    
            val json = objectMapper.readTree(decodedData)
            val relatedResults = json.get("RelatedResults") ?: return newHomePageResponse(request.name, emptyList())
            val discoverArchive = relatedResults.get("getDiscoverArchive") ?: return newHomePageResponse(request.name, emptyList())
            val resultArray = discoverArchive.get("result") ?: return newHomePageResponse(request.name, emptyList())
    
            val home = resultArray.mapNotNull {
                val title = it.get("title")?.asText() ?: return@mapNotNull null
                val slug = it.get("slug")?.asText() ?: return@mapNotNull null
                val poster = fixUrlNull(it.get("poster")?.asText())
    
                newTvSeriesSearchResponse(title, fixUrl("/$slug"), TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
            return newHomePageResponse(request.name, home)
        } else {
            document.select("div.col-span-3 a").mapNotNull { it.sonBolumler() }
        }
    
        return newHomePageResponse(request.name, home)
    }

    private fun Element.diziler(): SearchResponse {
        val title = this.selectFirst("span.font-normal")?.text() ?: "return null"
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: "return null"
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.yeniEklenenler(): SearchResponse {
        val title = this.selectFirst("h2")?.text() ?: "return null"
        val href = fixUrlNull(this.attr("href")) ?: "return null"
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }
    private suspend fun Element.sonBolumler(): SearchResponse {
        val name = this.selectFirst("h2")?.text() ?: ""
        val epName = this.selectFirst("div.opacity-80")!!.text().replace(". Sezon ", "x")
            .replace(". Bölüm", "")

        val title = "$name - $epName"

        val epDoc = fixUrlNull(this.attr("href"))?.let { Jsoup.parse(app.get(it).body.string()) }

        val href = fixUrlNull(epDoc?.selectFirst("div.poster a")?.attr("href")) ?: "return null"

        val posterUrl = fixUrlNull(epDoc?.selectFirst("div.poster img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        return newTvSeriesSearchResponse(
            title ?: return null,
            "${mainUrl}/${slug}",
            TvType.TvSeries,
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchReq = app.post(
            "${mainUrl}/api/bg/searchContent?searchterm=$query",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "en-US,en;q=0.5",
                "X-Requested-With" to "XMLHttpRequest",
                "Sec-Fetch-Site" to "same-origin",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty",
                "Referer" to "${mainUrl}/"
            ),
            referer = "${mainUrl}/",
        )
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val searchResult: SearchResult = objectMapper.readValue(searchReq.toString())
        val decodedSearch = base64Decode(searchResult.response.toString())
        val contentJson: SearchData = objectMapper.readValue(decodedSearch)
        if (contentJson.state != true) {
            throw ErrorLoadingException("Invalid Json response")
        }
        val veriler = mutableListOf<SearchResponse>()
        contentJson.result?.forEach {
            val name = it.title.toString()
            val link = fixUrl(it.slug.toString())
            val posterLink = it.poster.toString()
            val toSearchResponse = toSearchResponse(name, link, posterLink)
            veriler.add(toSearchResponse)
        }
        return veriler
    }

    private fun toSearchResponse(ad: String, link: String, posterLink: String): SearchResponse {
        return newTvSeriesSearchResponse(
            ad,
            link,
            TvType.TvSeries,
        ) {
            this.posterUrl = posterLink
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val mainReq = app.get(url, interceptor = interceptor)
        val document = mainReq.document
        val title = document.selectFirst("div.poster.poster h2")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.w-full.page-top.relative img")?.attr("src"))
        val year =
            document.select("div.w-fit.min-w-fit")[1].selectFirst("span.text-sm.opacity-60")?.text()
                ?.split(" ")?.last()?.toIntOrNull()
        val description = document.selectFirst("div.mt-2.text-sm")?.text()?.trim()
        val tags = document.selectFirst("div.poster.poster h3")?.text()?.split(",")?.map { it }
        val actors = document.select("div.global-box h5").map {
            Actor(it.text())
        }

        val episodeses = mutableListOf<Episode>()

        for (sezon in document.select("div.flex.items-center.flex-wrap.gap-2.mb-4 a")) {
            val sezonhref = fixUrl(sezon.attr("href"))
            val sezonReq = app.get(sezonhref)
            val split = sezonhref.split("-")
            val season = split[split.size-2].toIntOrNull()
            val sezonDoc = sezonReq.document
            val episodes = sezonDoc.select("div.episodes")
            for (bolum in episodes.select("div.cursor-pointer")) {
                val epName = bolum.select("a").last()?.text() ?: continue
                val epHref = fixUrlNull(bolum.select("a").last()?.attr("href")) ?: continue
                val epEpisode = bolum.selectFirst("a")?.text()?.trim()?.toIntOrNull()
                val newEpisode = newEpisode(epHref) {
                    this.name = epName
                    this.season = season
                    this.episode = epEpisode
                }
                episodeses.add(newEpisode)
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeses) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = interceptor).document
        val script = document.selectFirst("script#__NEXT_DATA__")?.data()
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val secureData = objectMapper.readTree(script).get("props").get("pageProps").get("secureData")
        val decodedData = decryptDizillaResponse(secureData.toString().replace("\"", ""))
        val source = objectMapper.readTree(decodedData).get("RelatedResults")
            .get("getEpisodeSources").get("result").get(0).get("source_content").toString()
            .replace("\"", "").replace("\\", "")
        val iframe = fixUrlNull(Jsoup.parse(source).select("iframe").attr("src")) ?: return false
        loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        return true
    }

    private fun decryptDizillaResponse(response: String): String? {
        try {
            val algorithm = "AES/CBC/PKCS5Padding"
            val keySpec = SecretKeySpec(privateAESKey.toByteArray(), "AES")

            val iv = ByteArray(16)
            val ivSpec = IvParameterSpec(iv)

            val cipher1 = Cipher.getInstance(algorithm)
            cipher1.init(Cipher.DECRYPT_MODE, keySpec,ivSpec)
            val firstIterationData =
                cipher1.doFinal(Base64.decode(response, Base64.DEFAULT))

            val jsonString = String(firstIterationData)

            return jsonString
        } catch (e: Exception) {
            Log.e("Dizilla", "Decryption failed: ${e.message}")
            return null
        }
    }
}
