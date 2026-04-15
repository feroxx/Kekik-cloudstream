// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.YoutubeExtractor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.jsoup.Jsoup
import okhttp3.Interceptor
import okhttp3.Response

class FilmBip : MainAPI() {
    override var mainUrl              = "https://filmbip.com"
    override var name                 = "FilmBip"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

	override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 150L  // ? 0.15 saniye
    override var sequentialMainPageScrollDelay = 150L  // ? 0.15 saniye

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
	}
	
    override val mainPage = mainPageOf(
        "${mainUrl}/filmler/"                        to "Yeni Filmler",
        "${mainUrl}/film/tur/aile/"                   to "Aile Filmleri",
        "${mainUrl}/film/tur/aksiyon/"                to "Aksiyon Filmleri",
        "${mainUrl}/film/tur/belgesel/"               to "Belgesel Filmleri",
        "${mainUrl}/film/tur/bilim-kurgu/"            to "Bilim Kurgu Filmleri",
        "${mainUrl}/film/tur/dram/"                   to "Dram Filmleri",
        "${mainUrl}/film/tur/fantastik/"              to "Fantastik Filmler",
        "${mainUrl}/film/tur/gerilim/"                to "Gerilim Filmleri",
        "${mainUrl}/film/tur/gizem/"                  to "Gizem Filmleri",
        "${mainUrl}/film/tur/komedi/"                 to "Komedi Filmleri",
        "${mainUrl}/film/tur/korku/"                  to "Korku Filmleri",
        "${mainUrl}/film/tur/macera/"                 to "Macera Filmleri",
        "${mainUrl}/film/tur/muzik/"                  to "Müzik Filmleri",
        "${mainUrl}/film/tur/romantik/"               to "Romantik Filmler",
        "${mainUrl}/film/tur/savas/"                  to "Savaş Filmleri",
        "${mainUrl}/film/tur/suc/"                    to "Suç Filmleri",
        "${mainUrl}/film/tur/tarih/"                  to "Tarih Filmleri",
        "${mainUrl}/film/tur/vahsi-bati/"             to "Western Filmler",
        "${mainUrl}/film/tur/tv-film/"                to "TV Filmleri",
    )

override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/$page"
    val document = app.get(url, timeout = 10000, interceptor = interceptor).document
    val home = document.select("div.poster-long").mapNotNull { it.toSearchResult() }

    return newHomePageResponse(request.name, home)
}

    private fun Element.toSearchResult(): SearchResponse? {
        // 1. Title'ı img alt yerine doğrudan h2'den (veya fallback olarak img alt'tan) alıyoruz
        val title = this.selectFirst("h2.truncate")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        // 2. Class bağımlılığını kaldırdık. Direkt div içindeki ilk 'a' etiketinin href'ine bakıyoruz.
        val href = fixUrlNull(this.selectFirst("a[href]")?.attr("href")) ?: return null

        // 3. Yine class bağımlılığı olmadan doğrudan img'yi çekiyoruz.
        // poster-long div'i içinde zaten aradığımız tek bir ana resim var.
        val imgElement = this.selectFirst("img")
        if (imgElement == null) {
            Log.d("FLB", "imgElement is null for title: $title")
            return null
        }

        // 4. Kotlin'in nimetlerinden faydalanarak daha temiz bir url seçimi (data-src öncelikli)
        val posterUrl = fixUrlNull(
            imgElement.attr("data-src").takeIf { it.isNotBlank() }
                ?: imgElement.attr("src").takeIf { it.isNotBlank() }
        )

        if (posterUrl == null) {
            Log.d("FLB", "Geçerli bir resim URL'si bulunamadı: $title")
            return null
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val responseRaw = app.post(
            "$mainUrl/search",
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            ),
            data = mapOf("query" to query)
        )

        val json = responseRaw.parsedSafe<Map<String, Any>>()
        if (json?.get("success") != true) {
            Log.d("FLB", "Search failed: ${json?.get("success")}")
            return emptyList()
        }

        val theme = json["theme"] as? String ?: return emptyList()
        val document = Jsoup.parse(theme)
        val items = document.select("li")

        return items.mapNotNull { item ->
            val title = item.selectFirst("a.block.truncate")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrlNull(item.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val posterUrl = fixUrlNull(item.selectFirst("img.lazy")?.attr("data-src"))

            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title  = document.selectFirst("div.page-title h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content")) ?: return null
        val trailerId = document.selectFirst("div.series-profile-trailer")?.attr("data-yt")
        val trailerUrl = trailerId?.takeIf { it.isNotEmpty() }?.let { "https://www.youtube.com/watch?v=$it" }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            addTrailer(trailerUrl)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FLB", "data » $data")
        val document = app.get(data, interceptor = interceptor).document

        // ID tekil olduğu için forEach yerine doğrudan hedefi seçiyoruz
        val iframeSrc = fixUrlNull(document.selectFirst("#tv-spoox2 iframe")?.attr("src"))

        if (iframeSrc != null) {
            Log.d("FLB", "iframeSrc » $iframeSrc")
            loadExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback)
        } else {
            Log.d("FLB", "tv-spoox2 div'i veya içindeki iframe bulunamadı.")
        }

        return true
    }
}
