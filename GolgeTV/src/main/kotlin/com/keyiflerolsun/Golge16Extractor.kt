package com.keyiflerolsun

import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

open class Golge16 : ExtractorApi() {
    override val name = "Golge16"
    override val mainUrl = "golge16://"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val data = url.split("||")[1]
        val content = AppUtils.tryParseJson<OrmoxChnlx>(data)
            ?: throw ErrorLoadingException("can't parse json")
        val link = content.link.split(this.mainUrl)[1].split("%7C")[0]
        val firstResp = app.get(link).parsedSafe<Golge16FirstResponse>()
            ?: throw ErrorLoadingException("can't reach url")

        val headers = mapOf(
            "User-Agent" to firstResp.headers.userAgent,
            "Referer" to firstResp.headers.referer,
            "Origin" to firstResp.headers.origin,
            "X-Requested-With" to firstResp.headers.xRequestedWith,
            "X-Forwarded-For" to firstResp.headers.xForwardedFor,
            "Content-Type" to firstResp.headers.contentType,
        )
        val secondResp = app.post(firstResp.apiUrl, headers = headers, json = firstResp.jsonData)
            .parsedSafe<Golge16SecondResponse>() ?: throw ErrorLoadingException("can't reach url")

        val secondHeaders = mapOf(
            "watched-sig" to secondResp.addonSig,
            "mediahubmx-signature" to secondResp.addonSig,
            "user-agent" to firstResp.headers.userAgent,
            "X-Requested-With" to "com.golge.golgetv",
            "Content-Type" to "application/json"
        )
        val body = mapOf(
            "language" to "tr",
            "region" to "TR",
            "url" to firstResp.medyaurl
        )
        val thirdResp = app.post(firstResp.proxyurl, headers = secondHeaders, json = body).text
        val (streamLink) = Regex(""""url":"(.*?)"""").find(thirdResp)!!.destructured
        Log.d("GOLGE16", "streamLink: $streamLink")
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = content.isim,
                url = streamLink,
                referer = "",
                quality = Qualities.Unknown.value,
                isM3u8 = true,
            )
        )
    }
}
