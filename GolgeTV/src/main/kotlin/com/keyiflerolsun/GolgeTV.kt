package com.keyiflerolsun

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

class GolgeTV : MainAPI() {
    override var name = "GolgeTV"
    override var mainUrl = "https://panel.cloudgolge.shop/appMainGetData.php"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        this.mainUrl to "ULUSAL",
        this.mainUrl to "SPOR",
        this.mainUrl to "HABER",
        this.mainUrl to "BELGESEL",
        this.mainUrl to "SİNEMA",
        this.mainUrl to "ÇOCUK",
        this.mainUrl to "MÜZİK",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = app.post(
            request.data,
            headers = mapOf(
                "x-requested-with" to "com.golge.golgetv2",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:80.0) Gecko/20100101 Firefox/79.0"
            ),
            data = mapOf(
                "ormoxRoks" to "D8C42BC6CD20C00E85659003F62B1F4A7A882DCB",
                "ormxArmegedEryxc" to "",
                "asize" to "oZT%2BZxn8zjH8LOwj%2FUwiYA%3D%3D",
                "serverurl" to "https://raw.githubusercontent.com/sevdaliyim/sevdaliyim/refs/heads/main/ssl2.key",
                "glg1Key" to "1FbcLGctAooQU7L6LQ2YaDtpNHNryPGMde7wUd47Jc53lOikXegk4LKREvfKqZYk"
            )
        )

        val contents = mutableListOf<SearchResponse>()
        AppUtils.tryParseJson<MainPageResp>(home.text)!!.ormoxChnlx
            .filter {
                if (it.kategori != request.name || it.player == "m3u") return@filter false
                if (it.player == "iframe" && !it.link.contains("golge")) return@filter false
                return@filter true
            }
            .forEach {
                val toDict = jacksonObjectMapper().writeValueAsString(it)
                contents.add(newLiveSearchResponse(it.isim, toDict, TvType.Live) {
                    this.posterUrl = it.resim
                })
            }
        return newHomePageResponse(request.name, contents)
    }

    override suspend fun load(url: String): LoadResponse? {
        val content = AppUtils.tryParseJson<OrmoxChnlx>(url) ?: return null
        return newLiveStreamLoadResponse(content.isim, url, url) {
            this.posterUrl = content.resim
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val content = AppUtils.tryParseJson<OrmoxChnlx>(data) ?: return false
        if (content.player == "iframe") {
            val golgeMatch = Regex("^(golge(?:2|3|4|5|6|7|8|9|1[0-9])://).*").find(content.link)
            if (golgeMatch != null) {
                val (golgeProtocol) = golgeMatch.destructured
                loadExtractor("$golgeProtocol||$data", subtitleCallback, callback)
                return true
            }
            return false
        }

        var headers = mapOf(
            content.h1Key to content.h1Val,
            content.h2Key to content.h2Val,
            content.h3Key to content.h3Val,
            content.h4Key to content.h4Val,
            content.h5Key to content.h5Val
        )
        headers = headers.filterKeys { it != "0" }
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = content.isim,
                url = content.link,
                referer = headers["Referer"] ?: "",
                quality = Qualities.Unknown.value,
                headers = headers,
                isM3u8 = true
            )
        )
        return true
    }
}

