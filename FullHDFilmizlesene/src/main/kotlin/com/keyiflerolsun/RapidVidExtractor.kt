// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.Charset

open class RapidVid : ExtractorApi() {
    override val name = "RapidVid"
    override val mainUrl = "https://rapidvid.org"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: ""
        val videoReq = app.get(url, referer = extRef).text

        val p8Regex = Regex(
            """window\._p8\s*=\s*['"]([^'"]+)['"]"""
        )

        val p8 = p8Regex.find(videoReq)
            ?.groupValues
            ?.get(1)

        if (p8.isNullOrBlank()) {
            println("_p8 bulunamadı")
            return
        }

        val data = decodeP8(p8)

        val cm = data.optString("cm")
        val tm = data.optString("tm")
        val ct = data.optJSONArray("ct")

        if (ct != null) {
            for (i in 0 until ct.length()) {
                val caption = ct.getJSONObject(i)

                val url = caption.optString("file")
                val lang = caption.optString("label").trim()

                if (url.isNotBlank()) {
                    subtitleCallback(
                        newSubtitleFile(
                            lang = lang,
                            url = url
                        )
                    )
                }
            }
        }

        if (cm.isNotBlank()) {
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "Rapidvid - CM",
                    url = cm,
                    type = ExtractorLinkType.M3U8
                )
            )
        }

        if (tm.isNotBlank() && tm != cm) {
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "Rapidvid - TM",
                    url = tm,
                    type = ExtractorLinkType.M3U8
                )
            )
        }
    }

    private fun decodeP8(encoded: String): JSONObject {
        // 1. Ters çevir ve Base64 decode et
        val reversed = encoded.reversed()

        val firstBytes = Base64.decode(
            reversed,
            Base64.DEFAULT
        )

        // JavaScript atob karşılığı: Latin-1 karakterleri
        val first = String(
            firstBytes,
            Charset.forName("ISO-8859-1")
        )

        // 2. K9L anahtarına göre karakterleri geri kaydır
        val key = "K9L"

        val shifted = buildString {
            first.forEachIndexed { index, char ->
                val shift = (key[index % 3].code % 5) + 1
                append((char.code - shift).toChar())
            }
        }

        // 3. İkinci Base64 decode
        val jsonBytes = Base64.decode(
            shifted,
            Base64.DEFAULT
        )

        val json = String(jsonBytes, Charsets.UTF_8)

        return JSONObject(json)
    }
}