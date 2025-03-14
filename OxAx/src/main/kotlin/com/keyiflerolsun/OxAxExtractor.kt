// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.decodeUri
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri

class OxAxPlayer : ExtractorApi() {
    override var name            = "OxAxPlayer"
    override var mainUrl         = "http://oxax.tv"
    override val requiresReferer = true

    private fun base64Encode(str: String): String {
        return Base64.encodeToString(str.encodeUri().toByteArray(), Base64.DEFAULT)
    }

    private fun base64Decode(str: String): String {
        return String(Base64.decode(str, Base64.DEFAULT), Charsets.UTF_8).decodeUri()
    }

    private fun decodeAtob(base64Str: String): String {
        val b64Keys = mapOf(
            0 to "556G3",
            1 to "556G3D",
            2 to "556G3DQ",
            3 to "556G3DQ1",
            4 to "556G3DQ1V"
        )

        val fileSeparator = "F"

        var cleanb64 = base64Str.substring(2)
        for (i in 4 downTo 0) {
            val key = b64Keys[i]
            if (key != null) {
                cleanb64 = cleanb64.replace(fileSeparator + base64Encode(key), "")
            }
        }

        return try {
            base64Decode(cleanb64)
        } catch (e: Exception) {
            ""
        }
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val m3uLink:String?
        val extRef  = referer ?: ""
        val iSource = app.get(url, referer=extRef).text

        val kodk     = Regex("""var kodk="(.*?)"""").find(iSource)?.groupValues?.get(1) ?: throw ErrorLoadingException("kodk not found")
        val kos      = Regex("""var kos="(.*?)"""").find(iSource)?.groupValues?.get(1) ?: throw ErrorLoadingException("kos not found")
        val playerjs = Regex("""new Playerjs\("(.*?)"""").find(iSource)?.groupValues?.get(1) ?: throw ErrorLoadingException("playerjs not found")

        val decodedData = decodeAtob(playerjs)
        val (v1, v2)    = Regex("""\{v1\}(.*?)\{v2\}([a-zA-Z0-9]*)""").find(decodedData)?.destructured ?: throw ErrorLoadingException("v1 and v2 not found in decoded data")

        m3uLink = "${kodk}${v1}${kos}${v2}"
        Log.d("Kekik_${this.name}", "m3uLink » $m3uLink")

        callback.invoke(
            ExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = m3uLink,
                referer = url,
                quality = Qualities.Unknown.value,
                isM3u8  = true
            )
        )
    }
}