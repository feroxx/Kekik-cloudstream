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

                        // 1. Master.m3u8 dosyasına kendimiz istek atıyoruz!
                        android.util.Log.d("DiziPal", "--> Master Playlist indiriliyor: $fileUrl")
                        val masterM3u8Content = app.get(
                            url = fileUrl,
                            referer = url,
                            headers = mapOf("Origin" to domain)
                        ).text

                        // 2. Master içeriğini parse edip çözünürlükleri ve l.php linklerini buluyoruz
                        // #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=1280x720...
                        // https://four.dplayer82.site/l.php?v=...
                        val regex = """#EXT-X-STREAM-INF.*?RESOLUTION=\d+x(\d+).*?\n(https?://[^\s]+)""".toRegex()
                        val matches = regex.findAll(masterM3u8Content)

                        var foundLinks = false
                        matches.forEach { match ->
                            foundLinks = true
                            val quality = match.groupValues[1] // Örn: 720, 1080
                            var streamUrl = match.groupValues[2] // l.php ile biten link

                            // ExoPlayer'ı kandırmak için bazen URL'nin sonuna sahte parametre eklemek işe yarar
                            // Eğer l.php ise ExoPlayer patlamasın diye sonuna "&type=.m3u8" veya "?type=.m3u8" ekliyoruz.
                            streamUrl += if (streamUrl.contains("?")) "&type=.m3u8" else "?type=.m3u8"

                            val qualityInt = quality.toIntOrNull() ?: Qualities.Unknown.value

                            android.util.Log.d("DiziPal", "--> Çözülen Kalite: ${quality}p | Link: $streamUrl")

                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "${name} ${quality}p", // DPlayer 720p gibi görünecek
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    headers = mapOf("Referer" to url)
                                    Qualities.Unknown.value
                                }
                            )
                        }

                        // Fallback: Eğer regex çözünürlükleri bulamazsa, orijinal master linkini ver
                        if (!foundLinks) {
                            android.util.Log.w("DiziPal", "--> DİKKAT: Master m3u8 parse edilemedi, orijinal link gönderiliyor.")
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    name,
                                    fileUrl + if (fileUrl.contains("?")) "&type=.m3u8" else "?type=.m3u8",
                                    type = ExtractorLinkType.M3U8
                                )  {
                                    headers = mapOf("Referer" to url)
                                    Qualities.Unknown.value
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DiziPal", "--> DPlayer Extractor Hata: ${e.message}")
                }
            }
        }
    }
