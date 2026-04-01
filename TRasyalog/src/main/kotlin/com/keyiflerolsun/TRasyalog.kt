package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element


class TRasyalog : MainAPI() {
    override var mainUrl        = "https://asyalog.co"
    override var name           = "AsyaLog"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 500L
    override var sequentialMainPageScrollDelay = 500L

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler/ulke/guney-kore/" to "Kore Dizileri",
        "${mainUrl}/diziler/ulke/cin/" to "Çin Dizileri",
        "${mainUrl}/diziler/ulke/tayland/" to "Tayland Dizileri",
        "${mainUrl}/diziler/ulke/japonya/" to "Japon Diziler",
        "${mainUrl}/diziler/ulke/endonezya/" to "Endonezya Diziler",
        "${mainUrl}/devam-eden-diziler/" to "Devam eden Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/page/$page/").document
        // HATA DÜZELTİLDİ: t-baslik yerine asıl dizi kartlarını tutan frag-k sınıfı seçildi.
        val home = document.select("div.frag-k").mapNotNull { 
            it.toMainPageResult() 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        // HTML yapısına uygun olarak başlık, link ve poster çekimi
        val title = this.selectFirst("a.baslik span")?.text()?.trim()
            ?: this.selectFirst("a.resim")?.attr("title")?.trim() 
            ?: return null
        
        val href = fixUrlNull(this.selectFirst("a.resim")?.attr("href") 
            ?: this.selectFirst("a.baslik")?.attr("href")) 
            ?: return null

        val posterUrl = this.selectFirst("a.resim img")?.let { img ->
            fixUrlNull(
                img.attr("src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-src")
            )
        }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { 
            this.posterUrl = posterUrl 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.trim().replace(" ", "+")
        val document = app.get("${mainUrl}/?s=$encodedQuery").document
        // Arama sonuçlarında da büyük ihtimalle frag-k kullanılıyordur, alternatifleri de korudum.
        return document.select("div.frag-k, div.post-container, .sag-liste li").mapNotNull { 
            it.toMainPageResult() 
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(".afis h1")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst(".afis img")?.attr("src")
                ?: document.selectFirst(".afis img")?.attr("data-src")
        )

        val description = document.selectFirst(".aciklama")?.text()?.trim()
        val tags = document.select("[itemprop=name]").mapNotNull { it.text()?.trim() }.distinct()

        val episodes = mutableListOf<Episode>()
        val tabHeaders = document.select("ul.sekme-baslik li")

        if (tabHeaders.isNotEmpty() && tabHeaders.first()?.attr("rel")?.startsWith("bolum") == true) {
            tabHeaders.forEach { tab ->
                val targetId = tab.attr("rel") // Örn: "bolum-1-2"
                val epName = tab.text().trim() // Örn: "1-2. Bölüm"

                // "1-2" veya "1 - 2" gibi aralıkları yakalayan regex
                val rangeMatch = """(\d+)\s*-\s*(\d+)""".toRegex().find(epName)

                if (rangeMatch != null) {
                    val start = rangeMatch.groupValues[1].toInt()
                    val end = rangeMatch.groupValues[2].toInt()

                    for (i in start..end) {
                        // Her bölüm için eşsiz bir URL (data) oluşturuyoruz (?ep=X ekleyerek)
                        val episodeDataUrl = "$url#$targetId?ep=$i"
                        episodes.add(newEpisode(episodeDataUrl) {
                            this.name = "$i. Bölüm"
                            this.episode = i
                        })
                    }
                } else {
                    // Tekil bölüm durumu (Örn: "3. Bölüm")
                    val epNum = """(\d+)""".toRegex().find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    episodes.add(newEpisode("$url#$targetId") {
                        this.name = epName
                        this.episode = epNum
                    })
                }
            }
        } else {
            // Klasik href tabanlı liste yapısı (Fallback)
            document.select(".bolum-listesi a, #bolumler a, a[href*=-bolum]").forEach { element ->
                val epUrl = fixUrlNull(element.attr("href")) ?: return@forEach
                if (epUrl.contains("fragman", ignoreCase = true)) return@forEach
                val epName = element.text().trim()
                val epNum = """(\d+)""".toRegex().find(epName)?.groupValues?.get(1)?.toIntOrNull()

                episodes.add(newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                })
            }
        }

        // distinctBy artık ?ep= parametresi sayesinde 1. ve 2. bölümleri ayrı ayrı tutacak
        val sortedEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // URL'yi parçala: url#fragment?params
        // Örn: .../dizi#bolum-1-2?ep=1
        val fragmentParts = data.split("#")
        val pageUrl = fragmentParts[0]

        // Fragment kısmından hem tab ID'yi hem de varsa parametreyi ayırıyoruz
        val rawFragment = if (fragmentParts.size > 1) fragmentParts[1] else null
        val targetTabId = rawFragment?.substringBefore("?")

        val document = app.get(pageUrl).document

        // Hedef div'i seç (Örn: div#bolum-1-2)
        val targetElement = if (!targetTabId.isNullOrEmpty()) {
            document.selectFirst("div#$targetTabId") ?: document
        } else {
            document
        }

        // İframe ve data-url kaynaklarını tara
        targetElement.select("iframe").forEach { element ->
            val src = element.attr("src").ifBlank {
                element.attr("data-url").ifBlank { element.attr("data-src") }
            }.trim()

            if (src.isNotEmpty() && !src.startsWith("javascript")) {
                val fixedUrl = if (src.startsWith("//")) "https:$src" else src
                loadExtractor(fixedUrl, pageUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
