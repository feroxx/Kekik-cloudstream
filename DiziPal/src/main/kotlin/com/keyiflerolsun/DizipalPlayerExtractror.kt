package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

// DiziPal sınıfının dışına (altına) ekle
class DizipalPlayer : ExtractorApi() {
    override var name = "DizipalPlayer"
    override var mainUrl = "https://four.dplayer82.site"
    override val requiresReferer = true

        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            val response = app.get(url, referer = referer).text
            val openPlayerRegex = """window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""".toRegex()
            val playlistId = openPlayerRegex.find(response)?.groupValues?.get(1)

            if (playlistId != null) {
                val domainRegex = """https?://[^/]+""".toRegex()
                val domain = domainRegex.find(url)?.value ?: "https://four.dplayer82.site"
                val apiUrl = "$domain/source2.php?v=$playlistId"

                val apiResponse = app.get(apiUrl, referer = url).text

                try {
                    val fileRegex = """"file"\s*:\s*"([^"]+)"""".toRegex()
                    val fileMatches = fileRegex.findAll(apiResponse)

                    fileMatches.forEach { matchResult ->
                        var fileUrl = matchResult.groupValues[1].replace("\\/", "/")

                        if (fileUrl.contains("m.php")) {
                            fileUrl = fileUrl.replace("m.php", "master.m3u8")
                        }

                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "DPlayer (Auto)",
                                    url = fileUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    headers = mapOf("Referer" to url)
                                    Qualities.Unknown.value
                                }
                            )
                        }
                } catch (e: Exception) {
                    android.util.Log.e("DiziPal", "--> DPlayer Extractor Hata: ${e.message}")
                }
            }
        }
    }
