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
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

open class Golge19 : ExtractorApi() {
    override val name = "Golge19"
    override val mainUrl = "golge19://"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val data = url.split("||")[1]
        val content = AppUtils.tryParseJson<OrmoxChnlx>(data) ?: throw ErrorLoadingException("can't parse json")
        val headers = mapOf(
            "origin" to "https://google.com",
            "Referer" to "https://google.com/",
            "x-requested-with" to "XMLHttpRequest",
            "x-forwarded-for" to "1.1.1.1",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        )
        val link = content.link.split(this.mainUrl)[1].split("%7C%7C")[0]
        val resp = app.get(link, headers = headers).text
        val chUrl = getChUrl(resp)
        Log.d("GOLGE19", "chUrl: $chUrl")
        callback.invoke(ExtractorLink(
            source = this.name,
            name = content.isim,
            url = chUrl,
            referer = "https://google.com",
            quality = Qualities.Unknown.value,
            isM3u8 = true,
            headers = mapOf("origin" to "https://google.com")
        ))
    }

    private fun getChUrl(data: String): String {
        val split = data.split(":")
        val content1 = split[0]
        val key1 = Base64.decode(split[1], Base64.DEFAULT)
        val decrypt1 = decryptAES(content1, key1).split(":")
        val content2 = decrypt1[0]
        val key2 = Base64.decode(decrypt1[1], Base64.DEFAULT)
        val decrypt2 = decryptAES(content2, key2)
        val (chUrl) = Regex(""""chUrl": "(.*?)"""").find(decrypt2)!!.destructured
        return chUrl
    }

    private fun decryptAES(data: String, key: ByteArray): String {
        val charset = StandardCharsets.UTF_8
        val secretKeySpec = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(2, secretKeySpec, IvParameterSpec(key))
        return String(cipher.doFinal(Base64.decode(data, 0)), charset)
    }
}