// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.Interceptor
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class RecTV : MainAPI() {
    override var mainUrl              = "https://m.prectv50.sbs"
    override var name                 = "RecTV"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.Live, TvType.TvSeries)

    private val swKey      = "4F5A9C3D9A86FA54EACEDDD635185/c3c5bd17-e37b-4b94-a944-8a3688a30452"
    private val hmacKey    = "3508611138826751fdf77beaa6f93eb93fd27e6a5acb910e7aad22665513dd6e"
    private val appVersion = "141"
    private val clientId   = "rectv-android"

    // ---- HMAC imzalama ----
    private fun sha256Hex(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private suspend fun getNonce(): String? {
        return try {
            val response = app.get(
                "$mainUrl/api/attest/nonce",
                headers = mapOf("User-Agent" to "googleusercontent")
            )
            AppUtils.tryParseJson<RecNonce>(response.text)?.nonce
        } catch (e: Exception) {
            Log.e("RCTV", "Failed to fetch nonce: ${e.message}")
            null
        }
    }

    private suspend fun signedHeaders(method: String, path: String, body: String = ""): Map<String, String> {
        val ts        = (System.currentTimeMillis() / 1000L).toString()
        val nonce     = UUID.randomUUID().toString()
        val bodyHash  = sha256Hex(body)
        val message   = "$method\n$path\n$ts\n$nonce\n$bodyHash"
        val signature = hmacSha256Hex(hmacKey, message)
        
        val headers = mutableMapOf(
            "User-Agent"     to "googleusercontent",
            "Referer"        to "https://twitter.com/",
            "X-Timestamp"    to ts,
            "X-Nonce"        to nonce,
            "X-Signature"    to signature,
            "X-App-Version"  to appVersion,
            "X-Client-Id"    to clientId
        )

        getNonce()?.let {
            headers["Authorization"] = "Bearer $it"
        }

        return headers
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/api/channel/by/filtres/0/0/SAYFA/${swKey}/"      to "Canlı",
        "${mainUrl}/api/movie/by/filtres/0/created/SAYFA/${swKey}/"  to "Son Filmler",
        "${mainUrl}/api/serie/by/filtres/0/created/SAYFA/${swKey}/"  to "Son Diziler",
        "${mainUrl}/api/movie/by/filtres/14/created/SAYFA/${swKey}/" to "Aile",
        "${mainUrl}/api/movie/by/filtres/1/created/SAYFA/${swKey}/"  to "Aksiyon",
        "${mainUrl}/api/movie/by/filtres/13/created/SAYFA/${swKey}/" to "Animasyon",
        "${mainUrl}/api/movie/by/filtres/19/created/SAYFA/${swKey}/" to "Belgesel",
        "${mainUrl}/api/movie/by/filtres/4/created/SAYFA/${swKey}/"  to "Bilim Kurgu",
        "${mainUrl}/api/movie/by/filtres/2/created/SAYFA/${swKey}/"  to "Dram",
        "${mainUrl}/api/movie/by/filtres/10/created/SAYFA/${swKey}/" to "Fantastik",
        "${mainUrl}/api/movie/by/filtres/3/created/SAYFA/${swKey}/"  to "Komedi",
        "${mainUrl}/api/movie/by/filtres/8/created/SAYFA/${swKey}/"  to "Korku",
        "${mainUrl}/api/movie/by/filtres/17/created/SAYFA/${swKey}/" to "Macera",
        "${mainUrl}/api/movie/by/filtres/5/created/SAYFA/${swKey}/"  to "Romantik"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        @Suppress("NAME_SHADOWING") val page = page - 1

        val url      = request.data.replace("SAYFA", "$page")
        val uri      = java.net.URI(url)
        val path     = uri.rawPath
        val headers  = signedHeaders("GET", path)
        val home     = app.get(url, headers = headers)

        val movies = AppUtils.tryParseJson<List<RecItem>>(home.text)!!.map { item ->
            val toDict = jacksonObjectMapper().writeValueAsString(item)

            if (item.label != "CANLI" && item.label != "Canlı") {
                newMovieSearchResponse(item.title, toDict, TvType.Movie) { this.posterUrl = item.image }
            } else {
                newLiveSearchResponse(item.title, toDict, TvType.Live) {
                    this.posterUrl = item.image
                }
            }
        }

        return newHomePageResponse(request.name, movies)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val path    = "/api/search/${query}/${swKey}/"
        val headers = signedHeaders("GET", path)
        val home    = app.get("${mainUrl}${path}", headers = headers)
        val veriler = AppUtils.tryParseJson<RecSearch>(home.text)

        val sonuclar = mutableListOf<SearchResponse>()

        veriler?.channels?.let { channels ->
            for (item in channels) {
                val toDict = jacksonObjectMapper().writeValueAsString(item)
                sonuclar.add(newMovieSearchResponse(item.title, toDict, TvType.Movie) { this.posterUrl = item.image })
            }
        }

        veriler?.posters?.let { posters ->
            for (item in posters) {
                val toDict = jacksonObjectMapper().writeValueAsString(item)
                sonuclar.add(newMovieSearchResponse(item.title, toDict, TvType.Movie) { this.posterUrl = item.image })
            }
        }

        return sonuclar
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val veri = AppUtils.tryParseJson<RecItem>(url) ?: return null

        if (veri.type == "serie") {
            val path     = "/api/season/by/serie/${veri.id}/${swKey}/"
            val headers  = signedHeaders("GET", path)
            val diziReq  = app.get("${mainUrl}${path}", headers = headers)
            val sezonlar = AppUtils.tryParseJson<List<RecDizi>>(diziReq.text) ?: return null

            val episodes = mutableMapOf<DubStatus, MutableList<Episode>>()

            val numberRegex = Regex("\\d+")

            for (sezon in sezonlar) {
                val seasonDubStatus = if (sezon.title.contains("altyazı", ignoreCase = true)) DubStatus.Subbed
                                      else if (sezon.title.contains("dublaj", ignoreCase = true)) DubStatus.Dubbed
                                      else DubStatus.None
                for (bolum in sezon.episodes) {
                    episodes.getOrPut(seasonDubStatus) { mutableListOf() }.add(newEpisode(bolum.sources.first().url) {
                        this.name        = bolum.title
                        this.season      = numberRegex.find(sezon.title)?.value?.toIntOrNull()
                        this.episode     = numberRegex.find(bolum.title)?.value?.toIntOrNull()
                        this.description = sezon.title.substringAfter(".S ")
                        this.posterUrl   = veri.image
                    })
                }
            }

            return newAnimeLoadResponse(name = veri.title, url = url, type = TvType.TvSeries, comingSoonIfNone = false) {
                this.episodes = episodes.mapValues { it.value.toList() }.toMutableMap()
                this.posterUrl = veri.image
                this.plot      = veri.description
                this.year      = veri.year
                this.tags      = veri.genres?.map { it.title }
            }
        }

        return if (veri.label != "CANLI" && veri.label != "Canlı") {
            newMovieLoadResponse(veri.title, url, TvType.Movie, url) {
                this.posterUrl = veri.image
                this.plot      = veri.description
                this.year      = veri.year
                this.tags      = veri.genres?.map { it.title }
            }
        } else {
            newLiveStreamLoadResponse(veri.title, url, url) {
                this.posterUrl = veri.image
                this.plot      = veri.description
                this.tags      = veri.genres?.map { it.title }
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (data.startsWith("http")) {
            Log.d("RCTV", "data » $data")
            callback.invoke(
                newExtractorLink(
                    source  = this.name,
                    name    = this.name,
                    url     = data,
                    type    = INFER_TYPE
                ) {
                    headers = mapOf("Referer" to "https://twitter.com/")
                    quality = Qualities.Unknown.value
                }
            )
            return true
        }

        val veri = AppUtils.tryParseJson<RecItem>(data) ?: return false

        for (source in veri.sources) {
            Log.d("RCTV", "source » $source")
            callback.invoke(
                newExtractorLink(
                    source  = this.name,
                    name    = "${this.name} - ${source.type}",
                    url     = source.url,
                    type    = if (source.type == "mp4") ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                ) {
                    referer = "https://twitter.com/"
                    quality = Qualities.Unknown.value
                }
            )
        }

        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val originalRequest = chain.request()
            val modifiedRequest = originalRequest.newBuilder()
                .removeHeader("If-None-Match")
                .header("User-Agent", "googleusercontent")
                .build()
            chain.proceed(modifiedRequest)
        }
    }
}
