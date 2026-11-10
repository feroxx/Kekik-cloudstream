package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*


class CloseLoadExtractor : ExtractorApi() {
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val name = "CloseLoad"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "getUrl çağrıldı, url: $url")

        val response = app.get(url, referer = referer ?: mainUrl)
        val rawHtml = response.text
        Log.d(name, "Raw HTML uzunluğu: ${rawHtml.length}")

        var videoUrl: String? = null
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "closeload.filmmakinesi.to"

        val unpackedJs = EmbedDecoder.unpackPackerJs(rawHtml)
        if (unpackedJs != null) {
            Log.d(name, "JS unpack edildi, uzunluk: ${unpackedJs.length}")
        } else {
            Log.w(name, "Packed JS bulunamadı veya unpack edilemedi")
        }

        videoUrl = EmbedDecoder.extractVideoUrl(rawHtml, unpackedJs, host)
        Log.d(name, "Dinamik çözülen URL: $videoUrl")

        if (videoUrl.isNullOrBlank()) {
            val jsonLdMatch = Regex(""""contentUrl"\s*:\s*"([^"]+)"""").find(rawHtml)
            videoUrl = jsonLdMatch?.groupValues?.get(1)?.replace("\\/", "/")
            Log.d(name, "Fallback JSON-LD contentUrl: $videoUrl")
        }
        if (videoUrl.isNullOrBlank()) {
            val directMatch = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(rawHtml)
            videoUrl = directMatch?.groupValues?.get(1)?.replace("\\/", "/")
            Log.d(name, "Fallback direkt m3u8: $videoUrl")
        }

        if (videoUrl.isNullOrBlank()) {
            Log.e(name, "Video URL bulunamadı!")
            return
        }

        parseSubtitles(rawHtml, subtitleCallback)

        Log.d(name, "Master fetch ediliyor: $videoUrl")
        val masterResponse = app.get(videoUrl, referer = url, headers = mapOf(
            "Accept" to "*/*",
            "Origin" to mainUrl
        ))
        Log.d(name, "Master status: ${masterResponse.code}")

        if (masterResponse.code != 200) {
            Log.e(name, "Master 404/500!")
            return
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Accept" to "*/*",
                    "Origin" to mainUrl
                )
            }
        )
        Log.d(name, "ExtractorLink eklendi: $videoUrl")
    }

    private fun parseSubtitles(
        rawHtml: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val tracksMatch = Regex("""tracks:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(rawHtml)
        tracksMatch?.groupValues?.get(1)?.let { tracksStr ->
            val matches = Regex(""""file"\s*:\s*"([^"]+)".*?"label"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                .findAll(tracksStr).toList()
            Log.d(name, "Bulunan altyazı sayısı: ${matches.size}")

            matches.forEachIndexed { index, match ->
                val subUrl = match.groupValues[1].replace("\\/", "/")
                val subLabel = match.groupValues[2]
                val lang = when {
                    subLabel.contains("Turkish", ignoreCase = true) -> "Türkçe"
                    subLabel.contains("Forced", ignoreCase = true) -> "Forced"
                    subLabel.contains("English", ignoreCase = true) -> "İngilizce"
                    else -> return@forEachIndexed
                }
                Log.d(name, "Altyazı #$index - lang: '$lang', label: '$subLabel'")
                subtitleCallback.invoke(SubtitleFile(lang, subUrl))
            }
        }
    }
}
