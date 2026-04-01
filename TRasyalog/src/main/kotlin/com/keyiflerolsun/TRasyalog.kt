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

        // Başlık Seçimi
        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim()
            ?: document.selectFirst(".t-baslik")?.text()?.trim()
            ?: return null

        // Poster Seçimi
        val poster = fixUrlNull(
            document.selectFirst(".afis img")?.attr("data-src")
                ?: document.selectFirst(".afis img")?.attr("src")
        )

        // Açıklama (Plot)
        val description = document.selectFirst(".ozet")?.text()?.trim()

        // Etiketler (Tags)
        val tags = document.select(".kategori a").mapNotNull {
            it.text()?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct().take(5)

        val episodes = mutableListOf<Episode>()

        // YENİ MİMARİ: Sekme başlıklarını buluyoruz (ör: <li rel="bolum-1">1. Bölüm</li>)
        val tabHeaders = document.select("ul.sekme-baslik li")

        if (tabHeaders.isNotEmpty() && tabHeaders.first()?.attr("rel")?.startsWith("bolum") == true) {
            // Tab (Sekme) yapısı kullanılmışsa
            tabHeaders.forEach { tab ->
                val targetId = tab.attr("rel") // "bolum-1"
                if (targetId.isNullOrEmpty()) return@forEach

                val epName = tab.text().trim()
                val epNum = """(\d+)""".toRegex().find(epName)?.groupValues?.get(1)?.toIntOrNull()

                // URL'nin sonuna #bolum-1 gibi fragment ekleyerek loadLinks'e paslıyoruz
                val episodeDataUrl = "$url#$targetId"

                episodes.add(newEpisode(episodeDataUrl) {
                    this.name = epName
                    this.episode = epNum
                })
            }
        } else {
            // FALLBACK: Eski tip sayfalarda bölüm linkleri a href ile verilmişse
            document.select(".bolum-listesi a, #bolumler a, ul.bolumler li a, .dizi-bolumleri a, a[href*=-bolum]").forEach { element ->
                val epUrl = fixUrlNull(element.attr("href")) ?: return@forEach
                if (epUrl.contains("fragman", ignoreCase = true)) return@forEach

                val epName = element.text().trim()
                val epNumMatch = """(\d+)""".toRegex().find(epName) ?: """-(\d+)-?bolum""".toRegex().find(epUrl)
                val epNum = epNumMatch?.groupValues?.get(1)?.toIntOrNull()

                val isFinal = epUrl.contains("final", ignoreCase = true) || epName.contains("final", ignoreCase = true)
                val finalName = if (isFinal) "Final Bölümü" else "${epNum ?: "Bilinmeyen"}. Bölüm"

                episodes.add(newEpisode(epUrl) {
                    this.name = finalName
                    this.episode = epNum
                })
            }
        }

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
        Log.d("TRASYA", "Bölüm yükleniyor (URL): $data")

        // URL'den asıl sayfa linkini ve hedef tab ID'sini ayırıyoruz
        val urlParts = data.split("#")
        val pageUrl = urlParts[0]
        val targetTabId = if (urlParts.size > 1) urlParts[1] else null

        val document = app.get(pageUrl).document

        // Eğer hedef bir tab ID varsa (ör: bolum-1), sadece o div'in içindeki iframe'leri ara
        // Eğer yoksa (eski tip listeleme ise), tüm sayfada ara.
        val targetElement = if (targetTabId != null) {
            document.selectFirst("div#$targetTabId") ?: document
        } else {
            document
        }

        // 1. İframe'leri bul
        val iframes = targetElement.select("iframe")
        iframes.forEach { iframe ->
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }.trim()
            if (src.isNotEmpty()) {
                val fixedUrl = if (src.startsWith("//")) "https:$src" else src
                loadExtractor(fixedUrl, pageUrl, subtitleCallback, callback)
            }
        }

        // 2. Alternatif kaynakları (data-url gizli playerlar) bul
        val hiddenUrls = targetElement.select("[data-url]").mapNotNull {
            it.attr("data-url").trim().takeIf { url -> url.isNotEmpty() }
        }.distinct()

        hiddenUrls.forEach { url ->
            val fixedUrl = fixUrlNull(url)?.let {
                if (it.startsWith("//")) "https:$it" else it
            }
            fixedUrl?.let {
                loadExtractor(it, pageUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
