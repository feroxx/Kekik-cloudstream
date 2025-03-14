// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class OxAx : MainAPI() {
    override var mainUrl              = "https://ythls.kekikakademi.org/oxax/cs3"
    override var name                 = "OxAx"
    override val hasMainPage          = true
    override var lang                 = "ru"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = false
    override val supportedTypes       = setOf(TvType.NSFW, TvType.Live)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/hd"     to "HD",
        "${mainUrl}/porno"  to "Porno",
        "${mainUrl}/erotic" to "Erotic TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = app.get(request.data).parsedSafe<KekikAPI>()!!.channels.map { channel ->
            newLiveSearchResponse(channel.detail.title, "http://oxax.tv/${channel.slug}.html", TvType.Live) {
                this.posterUrl = channel.detail.img
                this.lang = "RU"
            }
        }

        return newHomePageResponse(
            HomePageList(request.name, channels, isHorizontalImages = true),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("${mainUrl}/search/${query}").parsedSafe<KekikAPI>()!!.channels.map { channel ->
            newLiveSearchResponse(channel.detail.title, "http://oxax.tv/${channel.slug}.html", TvType.Live) {
                this.posterUrl = channel.detail.img
                this.lang = "RU"
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val slug   = url.split("/").last().substringBefore(".html")
        val detail = app.get("${mainUrl}/detail/${slug}").parsedSafe<Detail>()!!

        return newLiveStreamLoadResponse(detail.title, url, url) {
            this.posterUrl = detail.img
            this.plot      = "⚠️🔞🔞🔞 » ${detail.title} « 🔞🔞🔞⚠️"
            this.tags = detail.tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("XOX", "data » $data")

        loadExtractor(data, "${mainUrl}/", subtitleCallback, callback)

        return true
    }

    data class KekikAPI(
        @JsonProperty("channels") val channels: List<Channel> = arrayListOf(),
    )

    data class Channel(
        @JsonProperty("slug")    val slug: String       = "",
        @JsonProperty("detail")  val detail: Detail     = Detail()
    )

    data class Detail(
        @JsonProperty("title") val title: String      = "",
        @JsonProperty("img")   val img: String        = "",
        @JsonProperty("tags")  val tags: List<String> = arrayListOf()
    )
}