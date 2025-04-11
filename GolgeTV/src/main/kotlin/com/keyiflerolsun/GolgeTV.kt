package com.keyiflerolsun

import com.fasterxml.jackson.databind.DeserializationFeature
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
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Log

class GolgeTV : MainAPI() {
    override var name = "GolgeTV"
    override var mainUrl = "https://panel202.cloudgolge.shop/appMainGetData.php"
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

    // JSON veri modelleri
    data class MainPageResp(
        val icerikler: List<Any>?,
        val ormoxChnlx: List<OrmoxChnlx>?,
        val menuPaylas: String?,
        val menuInstagram: String?,
        val menuTelegram: String?,
        val onlineTime: String?,
        val onlineDurum: String?
    )

    data class OrmoxChnlx(
        val id: String?,
        val isim: String?,
        val resim: String?,
        val link: String?,
        val kategori: String?,
        val player: String?,
        val tip: String?,
        val userAgent: String?,
        val h1Key: String?,
        val h1Val: String?,
        val h2Key: String?,
        val h2Val: String?,
        val h3Key: String?,
        val h3Val: String?,
        val h4Key: String?,
        val h4Val: String?,
        val h5Key: String?,
        val h5Val: String?
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("GolgeTV", "getMainPage called with request: ${request.name}")

        val home = app.post(
            request.data,
            headers = mapOf(
                "x-requested-with" to "com.golge.golgetv2",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:80.0) Gecko/20100101 Firefox/79.0"
            ),
            data = mapOf(
                "ormoxRoks" to "D8C42BC6CD20C00E85659003F62B1F4A7A882DCB",
                "ormxArmegedEryxc" to "",
                "asize" to "rgbpAMjIWQ+QjCewjvFCRw==",
                "serverurl" to "https://raw.githubusercontent.com/sevdaliyim/sevdaliyim/refs/heads/main/ssl2.key",
                "glg1Key" to "1FbcLGctAooQU7L6LQ2YaDtpNHNryPGMde7wUd47Jc53lOikXegk4LKREvfKqZYk",
                "kategori" to request.name
            )
        )
        Log.d("GolgeTV", "Response from POST: ${home.text}")

        if (home.text.isEmpty()) {
            Log.e("GolgeTV", "Response is empty")
            throw Exception("Server returned empty response")
        }

        val mapper = jacksonObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
        val jsonResponse = try {
            mapper.readValue(home.text, MainPageResp::class.java)
        } catch (e: Exception) {
            Log.e("GolgeTV", "Failed to parse JSON: ${home.text}, Error: ${e.message}")
            throw Exception("Failed to parse JSON response: ${e.message}")
        }

        val channels = jsonResponse.ormoxChnlx
        if (channels == null) {
            Log.e("GolgeTV", "ormoxChnlx is null in JSON response: ${home.text}")
            throw Exception("ormoxChnlx is null")
        }

        val contents = mutableListOf<SearchResponse>()
        channels
            .filter { channel ->
                if (channel.kategori != request.name || channel.player == "m3u") return@filter false
                if (channel.player == "iframe" && channel.link?.contains("golge") != true) return@filter false
                true
            }
            .forEach { channel ->
                val toDict = jacksonObjectMapper().writeValueAsString(channel)
                contents.add(newLiveSearchResponse(channel.isim ?: "Unknown", toDict, TvType.Live) {
                    this.posterUrl = channel.resim
                })
            }
        Log.d("GolgeTV", "Contents size: ${contents.size}")
        return newHomePageResponse(request.name, contents)
    }

    override suspend fun load(url: String): LoadResponse? {
        val content = AppUtils.tryParseJson<OrmoxChnlx>(url) ?: return null
        return newLiveStreamLoadResponse(content.isim ?: "Unknown", url, url) {
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
        Log.d("GolgeTV", "Parsed content: $content")

        if (content.player == "iframe") {
            val golgeMatch = Regex("^(golge(?:2|3|4|5|6|7|8|9|1[0-9])://).*").find(content.link ?: "")
            if (golgeMatch != null) {
                val (golgeProtocol) = golgeMatch.destructured
                loadExtractor("$golgeProtocol||$data", subtitleCallback, callback)
                return true
            }
            return false
        }

        val headers = mapOf(
            content.h1Key to content.h1Val,
            content.h2Key to content.h2Val,
            content.h3Key to content.h3Val,
            content.h4Key to content.h4Val,
            content.h5Key to content.h5Val
        ).filter { (key, value) -> key != null && value != null && key != "0" }
            .mapKeys { it.key!! }
            .mapValues { it.value!! }

        if (content.link.isNullOrEmpty() || content.isim.isNullOrEmpty()) {
            Log.e("GolgeTV", "Invalid data - Link: ${content.link}, Name: ${content.isim}")
            return false
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = content.isim,
                url = content.link,
				type = ExtractorLinkType.M3U8
			) {
                quality = Qualities.Unknown.value
				}
            )
        return true
    }
}
