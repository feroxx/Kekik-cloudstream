package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class CloseLoad : ExtractorApi() {
    override val name = "CloseLoad"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers2 = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0",
            "Referer" to "https://closeload.filmmakinesi.to/",
            "Origin" to "https://closeload.filmmakinesi.to"
        )
        
        try {
            val response = app.get(url, referer = mainUrl, headers = headers2)
            val document = response.document

            // JSON-LD'den video URL'sini çıkar
            extractFromJsonLd(document, callback)
            
            // Altyazıları işle
            processSubtitles(document, subtitleCallback)

        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Error: ${e.message}")
            throw ErrorLoadingException("Failed to extract video URL")
        }
    }

    private suspend fun extractFromJsonLd(document: Document, callback: (ExtractorLink) -> Unit) {
        val jsonLdScript = document.select("script[type=application/ld+json]").firstOrNull()
        if (jsonLdScript != null) {
            val jsonLd = jsonLdScript.data()
            val contentUrlRegex = "\"contentUrl\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            val match = contentUrlRegex.find(jsonLd)
            
            if (match != null) {
                val videoUrl = match.groupValues[1]
                if (videoUrl.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            quality = Qualities.Unknown.value
                            headers = mapOf("Referer" to "${mainUrl}/")
                        }
                    )
                }
            }
		}
    }

    private fun processSubtitles(document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track").forEach { track ->
            val rawSrc = track.attr("src").trim()
            val label = track.attr("label").ifBlank { track.attr("srclang").ifBlank { "Altyazı" } }
            
            if (rawSrc.isNotBlank()) {
                val fullUrl = if (rawSrc.startsWith("http")) rawSrc else mainUrl + rawSrc
                if (fullUrl.startsWith("http")) {
                    subtitleCallback(SubtitleFile(label, fullUrl))
                }
            }
        }
    }
}
