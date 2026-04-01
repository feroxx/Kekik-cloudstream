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
        val title = document.selectFirst("h1[itemprop=name], .t-baslik h1, .dizi-adi h1, h1.entry-title")?.text()?.trim()
            ?: return null

        // Poster Seçimi
        val poster = fixUrlNull(
            document.selectFirst("img[itemprop=image], .dizi-resim img, .post-thumbnail img")?.attr("data-src")
                ?: document.selectFirst("img[itemprop=image], .dizi-resim img, .post-thumbnail img")?.attr("src")
        )

        // Açıklama (Plot)
        val description = document.selectFirst("div[itemprop=description], .ozet, .aciklama, .entry-content p")?.text()?.trim()

        // Etiketler (Tags)
        val tags = document.select(".kategori a, .dizi-bilgi a[href*=kategori], .tur a").mapNotNull {
            it.text()?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct().take(5)

        // BÖLÜMLERİN EKLENMESİ
        val episodeElements = document.select(".bolum-listesi a, #bolumler a, ul.bolumler li a, .dizi-bolumleri a, a[href*=-bolum]")

        val episodes = episodeElements.mapNotNull { element ->
            val epUrl = fixUrlNull(element.attr("href")) ?: return@mapNotNull null

            // Fragman gibi videoları listeye bölüm olarak eklememek için filtreliyoruz
            if (epUrl.contains("fragman", ignoreCase = true)) return@mapNotNull null

            val epName = element.text().trim()

            // Bölüm numarasını isminden veya URL'den parse ediyoruz
            val epNumMatch = """(\d+)""".toRegex().find(epName)
                ?: """-(\d+)-?bolum""".toRegex().find(epUrl)
            val epNum = epNumMatch?.groupValues?.get(1)?.toIntOrNull()

            val isFinal = epUrl.contains("final", ignoreCase = true) || epName.contains("final", ignoreCase = true)
            val finalName = if (isFinal) "Final Bölümü" else "${epNum ?: "Bilinmeyen"}. Bölüm"

            // YENİ YAPI: newEpisode builder kullanımı
            newEpisode(epUrl) {
                this.name = finalName
                this.episode = epNum
            }
        }
            // Aynı linkten iki tane varsa temizle ve bölüm numarasına göre sırala
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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

        // Kullanıcı bir bölüme tıkladığında SADECE o bölümün sayfasını indiriyoruz
        val document = app.get(data).document

        // 1. Doğrudan sayfadaki iframe'leri bul
        val iframes = document.select("iframe")
        iframes.forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
            if (src.isNotEmpty()) {
                val fixedUrl = if (src.startsWith("//")) "https:$src" else src
                loadExtractor(fixedUrl, data, subtitleCallback, callback)
            }
        }

        // 2. Eğer Asyalog alternatif oynatıcıları data-url, data-src gibi attributelar ile gizlediyse onları bul
        val hiddenUrls = document.select("[data-url]").mapNotNull {
            it.attr("data-url").trim().takeIf { url -> url.isNotEmpty() }
        }.distinct()

        hiddenUrls.forEach { url ->
            val fixedUrl = fixUrlNull(url)?.let {
                if (it.startsWith("//")) "https:$it" else it
            }
            fixedUrl?.let {
                loadExtractor(it, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
