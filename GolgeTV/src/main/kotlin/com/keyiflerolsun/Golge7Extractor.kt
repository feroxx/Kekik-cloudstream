package com.keyiflerolsun

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

open class Golge7 : ExtractorApi() {
    override val name = "Golge7"
    override val mainUrl = "golge7://"
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
        val link = content.link.split(this.mainUrl)[1]
    }
}