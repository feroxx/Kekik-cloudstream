//Bu eklenti @feroxxcs3 için @patr0n tarafından gelistirildi.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AsyaAnimeleri : MainAPI() {
    override var mainUrl              = "https://asyaanimeleri.top"
    override var name                 = "AsyaAnimeleri"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay        = 250L
    override var sequentialMainPageScrollDelay  = 250L

    override val mainPage = mainPageOf(
        "${mainUrl}/"                                  to "Son Eklenenler",
        "${mainUrl}/genres/aksiyon/"                    to "Aksiyon",
        "${mainUrl}/genres/fantastik/"                  to "Fantastik",
       //${mainUrl}/genres/macera/"                     to "Macera",
        "${mainUrl}/genres/dovus-sanatlari/"            to "Dövüş Sanatları",
        "${mainUrl}/genres/romantik/"                   to "Romantik",
        "${mainUrl}/genres/komedi/"                     to "Komedi",
        "${mainUrl}/genres/bilim-kurgu/"                to "Bilim-Kurgu",
        "${mainUrl}/genres/gizem/"                      to "Gizem",
        "${mainUrl}/genres/dram/"                       to "Dram",
        "${mainUrl}/genres/ecchi/"                      to "Ecchi",
        "${mainUrl}/genres/isekai/"                     to "Isekai",
        "${mainUrl}/genres/shounen/"                    to "Shounen",
        "${mainUrl}/genres/reenkarnasyon/"              to "Reenkarnasyon",
        "${mainUrl}/genres/super-guc/"                  to "Süper Güç",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/${page}/" else request.data
        val document = app.get(url).document

        val home = if (request.data == "${mainUrl}/") {
            
            document.select("div.listupd div.utimes").mapNotNull { it.toLatestResult() }
        } else {
            
            document.select("div.listupd article div.bsx").mapNotNull { it.toSearchResult() }
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toLatestResult(): SearchResponse? {
        val anchor    = this.selectFirst("a") ?: return null
        val title     = anchor.selectFirst("div.tt")?.text()?.trim() ?: return null
        val href      = fixUrlNull(anchor.attr("href")) ?: return null
        val posterUrl = fixUrlNull(anchor.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor    = this.selectFirst("a") ?: return null
        val title     = anchor.attr("title").ifEmpty {
            this.selectFirst("div.tt")?.text()?.trim()
        } ?: return null
        val href      = fixUrlNull(anchor.attr("href")) ?: return null
        val posterUrl = fixUrlNull(anchor.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } })

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("div.listupd article div.bsx").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(
            document.selectFirst("div.thumb img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        )
        val description = document.selectFirst("div.entry-content p")?.text()?.trim()
        val tags        = document.select("div.genxed a").map { it.text().trim() }

        val episodes = mutableListOf<Episode>()

        document.select("div.eplister ul li").forEach { epItem ->
            val epAnchor  = epItem.selectFirst("a") ?: return@forEach
            val epHref    = fixUrlNull(epAnchor.attr("href")) ?: return@forEach
            val epName    = epAnchor.selectFirst("div.epl-title")?.text()?.trim()
                ?: epAnchor.text().trim()
            val epNumText = epAnchor.selectFirst("div.epl-num")?.text()?.trim()
            val epNum     = epNumText?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

            episodes.add(newEpisode(epHref) {
                this.name    = epName
                this.episode = epNum
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.tags      = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("AAE", "data » $data")
        val document = app.get(data).document

        
        val mirrorOptions = document.select("select.mirror option")
        Log.d("AAE", "Mirror sayısı: ${mirrorOptions.size}")

        for (option in mirrorOptions) {
            val base64Value = option.attr("value")
            if (base64Value.isBlank()) continue

            try {
               
                val decodedHtml = String(Base64.decode(base64Value, Base64.DEFAULT))
                Log.d("AAE", "Decoded HTML » $decodedHtml")

                
                val iframeSrc = Regex("""src="([^"]+)"""").find(decodedHtml)?.groupValues?.get(1)
                if (iframeSrc.isNullOrBlank()) continue
                Log.d("AAE", "iframe src » $iframeSrc")

                loadExtractor(iframeSrc, "${mainUrl}/", subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("AAE", "Mirror çözme hatası: ${e.message}")
            }
        }

        
        document.select("div#pembed iframe, div.player-embed iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                Log.d("AAE", "Mevcut iframe » $iframeSrc")
                loadExtractor(iframeSrc, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }
}
