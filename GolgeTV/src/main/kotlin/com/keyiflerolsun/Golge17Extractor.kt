package com.keyiflerolsun

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.util.regex.Matcher
import java.util.regex.Pattern

open class Golge17 : ExtractorApi() {
    override val name = "Golge17"
    override val mainUrl = "golge17://"
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
        val (link, originAndReferer) = content.link.split(this.mainUrl)[1].split("%7C")
        val headers = mapOf(
            "Connection" to "keep-alive",
            "Cache-Control" to "max-age=0",
            "sec-ch-ua" to """"Chromium";v="130", "Google Chrome";v="130", "Not?A_Brand";v="99"""",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to """"Windows"""",
            "Upgrade-Insecure-Requests" to "1",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-User" to "?1",
            "Sec-Fetch-Dest" to "document",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cookie" to "reklamgosterimx=ok",
            "Origin" to originAndReferer,
            "Referer" to originAndReferer
        )
        val resp = app.get(link, headers = headers).text
        val streamLink = parseHtml(resp)
        Log.d("GOLGE17", "streamLink: $streamLink")
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = content.isim,
                url = streamLink,
                referer = link,
                quality = Qualities.Unknown.value,
                isM3u8 = true,
                headers = mapOf(
                    "origin" to link,
                    "Accept" to "*/*",
                    "Cache-Control" to "max-age=0",
                    "sec-ch-ua-platform" to """"Windows"""",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
                )
            )
        )
    }

    private fun parseHtml(str: String): String {
        val trim = str.replace("\\s+".toRegex(), "").trim { it <= ' ' }
        try {
            val str2 = String(
                Base64.decode(
                    extractDataWithRegex("=window\\['atob'\\]\\(\"(.*?)\"", trim),
                    Base64.DEFAULT
                )
            )
            val extractMultipleDataWithRegex = extractMultipleDataWithRegex(
                extractDataWithRegex("window.stnbnb=\\[(.*?)\\]", trim)!!
            )
            val str3: String? = if (extractMultipleDataWithRegex.isNotEmpty()) String(
                Base64.decode(
                    extractMultipleDataWithRegex[(Math.random() * extractMultipleDataWithRegex.size).toInt()],
                    Base64.DEFAULT
                )
            ) else null
            val str4 = buildString {
                append("https://$str3")
                append(
                    String(
                        Base64.decode(
                            extractDataWithRegex(
                                "window.streamradardomil=\\[atob\\(\"(.*?)\"\\)",
                                trim
                            ), Base64.DEFAULT
                        ),
                        Charsets.UTF_8
                    )
                )
                append("/i/1.1.1.1/")
                append(str2)
                append("/playlist.m3u8")
            }
            return str4
        } catch (e: Exception) {
            throw ErrorLoadingException(e.message)
        }
    }

    private fun extractDataWithRegex(str: String, str2: String): String? {
        val matcher: Matcher = Pattern.compile(str).matcher(str2)
        return if (matcher.find()) matcher.group(1) else ""
    }

    private fun extractMultipleDataWithRegex(str: String): Array<String> {
        val matcher: Matcher = Pattern.compile("atob\\(\"(.*?)\"\\)").matcher(str)
        val arrayList = mutableListOf<String>()
        while (matcher.find()) {
            arrayList.add(matcher.group(1)!!)
        }
        return arrayList.toTypedArray()
    }
}