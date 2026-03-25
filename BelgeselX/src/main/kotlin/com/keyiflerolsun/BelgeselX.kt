// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import java.util.Locale
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class BelgeselX : MainAPI() {
    override var mainUrl              = "https://belgeselx.com"
    override var name                 = "BelgeselX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Documentary)
	
    override val mainPage = mainPageOf(
        "${mainUrl}/konu/turk-tarihi-belgeselleri" to "Türk Tarihi",
        "${mainUrl}/konu/tarih-belgeselleri"	   to "Tarih",
        "${mainUrl}/konu/seyehat-belgeselleri"	   to "Seyahat",
        "${mainUrl}/konu/seri-belgeseller"		   to "Seri",
        "${mainUrl}/konu/savas-belgeselleri"	   to "Savaş",
        "${mainUrl}/konu/sanat-belgeselleri"	   to "Sanat",
        "${mainUrl}/konu/psikoloji-belgeselleri"   to "Psikoloji",
        "${mainUrl}/konu/polisiye-belgeselleri"	   to "Polisiye",
        "${mainUrl}/konu/otomobil-belgeselleri"	   to "Otomobil",
        "${mainUrl}/konu/nazi-belgeselleri"		   to "Nazi",
        "${mainUrl}/konu/muhendislik-belgeselleri" to "Mühendislik",
        "${mainUrl}/konu/kultur-din-belgeselleri"  to "Kültür Din",
        "${mainUrl}/konu/kozmik-belgeseller"	   to "Kozmik",
        "${mainUrl}/konu/hayvan-belgeselleri"	   to "Hayvan",
        "${mainUrl}/konu/eski-tarih-belgeselleri"  to "Eski Tarih",
        "${mainUrl}/konu/egitim-belgeselleri"	   to "Eğitim",
        "${mainUrl}/konu/dunya-belgeselleri"	   to "Dünya",
        "${mainUrl}/konu/doga-belgeselleri"		   to "Doğa",
        "${mainUrl}/konu/bilim-belgeselleri"	   to "Bilim"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // 1. Sayfa dinamik olarak oluşturuluyor
        val url = if (page == 1) {
            request.data
        } else {
            val categorySlug = request.data.removeSuffix("/").substringAfterLast("/")

            "https://belgeselx.com/ajax_konukat.php?url=$categorySlug&page=$page"
        }

        val document = app.get(url, cacheTime = 60).document

        val home = document.select("div.px-grid > a.px-card").mapNotNull { it.toSearchResult() }

        val parsedItems = home.ifEmpty { document.select("a.px-card").mapNotNull { it.toSearchResult() } }

        return newHomePageResponse(request.name, parsedItems)
    }

    private fun String.toTitleCase(): String {
        val locale = Locale("tr", "TR")
        return this.split(" ").joinToString(" ") { word ->
            word.lowercase(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val title = this.selectFirst(".px-card-title")?.text()?.trim()?.toTitleCase() ?: return null

        val href = fixUrlNull(this.attr("href")) ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img.px-card-img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.Documentary) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cx = "016376594590146270301:iwmy65ijgrm" // ! Might change in the future

        val tokenResponse = app.get("https://cse.google.com/cse.js?cx=${cx}")
        val cseLibVersion = Regex("""cselibVersion": "(.*)"""").find(tokenResponse.text)?.groupValues?.get(1)
        val cseToken      = Regex("""cse_token": "(.*)"""").find(tokenResponse.text)?.groupValues?.get(1)
        val fexp      = Regex("""fexp": "[.*]"""").find(tokenResponse.text)?.groupValues?.get(1)

        val response = app.get("https://cse.google.com/cse/element/v1?rsz=filtered_cse&num=100&hl=tr&source=gcsc&cselibv=${cseLibVersion}&cx=${cx}&q=${query}&safe=off&cse_tok=${cseToken}&sort=&exp=cc%2Capo&fexp=${fexp}&callback=google.search.cse.api9969&rurl=https%3A%2F%2Fbelgeselx.com%2F")
        Log.d("BLX", "response » $response")
        val titles     = Regex(""""titleNoFormatting": "(.*)"""").findAll(response.text).map { it.groupValues[1] }.toList()
        val urls       = Regex(""""ogImage": "(.*)"""").findAll(response.text).map { it.groupValues[1] }.toList()
        val posterUrls = Regex(""""ogImage": "(.*)"""").findAll(response.text).map { it.groupValues[1] }.toList()

        val searchResponses = mutableListOf<TvSeriesSearchResponse>()

        for (i in titles.indices) {
            val title     = titles[i].split("İzle")[0].trim().toTitleCase()
            val url       = urls.getOrNull(i) ?: continue
            val posterUrl = posterUrls.getOrNull(i) ?: continue

        if (url.contains("diziresimleri")) {
            // URL'den dosya adını al ve .jpg uzantısını kaldır
            val fileName = url.substringAfterLast("/").replace(Regex("\\.(jpe?g|png|webp)$"), "")
            // Yeni URL'yi oluştur
            val modifiedUrl = "https://belgeselx.com/belgeseldizi/$fileName"
            searchResponses.add(newTvSeriesSearchResponse(title, modifiedUrl, TvType.Documentary) {
                this.posterUrl = posterUrl
            })
        } else {
            continue
        }
        }
        return searchResponses
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // 1. Üst Kısım Seçicileri (Head & Hero)
        val title = document.selectFirst("h1.px-hero-title")?.text()?.trim()?.toTitleCase() ?: return null
        val description = document.selectFirst("p.px-hero-desc")?.text()?.trim()

        // Poster genelde body'de yoksa head içindeki meta etiketinden alınır. En stabil yöntemdir.
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        // Kanal adını (HİSTORY HD vb.) etiket olarak alıyoruz.
        val tags = document.select("a.px-hero-channel span").mapNotNull { it.text().trim().toTitleCase() }

        // 2. Bölümleri (Episodes) Çekme ve Pars Etme
        val episodes = document.select("a.px-ep-card").mapNotNull { element ->
            val epName = element.selectFirst(".px-ep-title")?.text()?.trim() ?: return@mapNotNull null
            var epHref = fixUrlNull(element.attr("href")) ?: return@mapNotNull null

            // KRİTİK MÜDAHALE: onclick içindeki gerçek bölüm ID'sini yakalıyoruz. (örn: '1598' -> 1598)
            val onClickStr = element.attr("onclick")
            val epId = Regex("""'(\d+)'""").find(onClickStr)?.groupValues?.get(1)

            // epId'yi loadLinks tarafında işleyebilmek için querystring olarak ekliyoruz.
            if (epId != null) {
                epHref = "$epHref?epId=$epId"
            }

            // Sezon ve Bölüm numarasını "S3 · B3" formatından güvenli bir şekilde çıkarıyoruz.
            val sMeta = element.selectFirst(".px-ep-s")?.text()?.trim() ?: ""
            val epSeason  = Regex("""S(\d+)""").find(sMeta)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epEpisode = Regex("""B(\d+)""").find(sMeta)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name    = epName
                this.season  = epSeason
                this.episode = epEpisode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Documentary, episodes) {
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
        Log.d("BLX", "loadLinks data » $data")

        // 1. load fonksiyonunda URL'nin sonuna eklediğimiz epId'yi alıyoruz.
        // substringAfter ile epId= sonrasını alır, eğer epId yoksa data'nın kendisini döner.
        val episodeId = data.substringAfter("?epId=", "").substringBefore("&")

        Log.d("BLX", "Kullanılacak Bölüm ID: $episodeId")

        // 2. Çektiğimiz ID ile AJAX iframe URL’sini oluşturuyoruz.
        val iframeUrl = "https://belgeselx.com/video/data/new4.php?id=$episodeId"
        Log.d("BLX", "iframeUrl oluşturuldu » $iframeUrl")

        // 3. Referer başlığını temizleyerek atıyoruz. Site güvenlik duvarları "?epId=" olan bir URL'yi reddedebilir.
        val refererUrl = data.substringBefore("?epId=")
        val alternatifResp = app.get(iframeUrl, referer = refererUrl).text

        var linksFound = false

        // 4. new4.php içindeki video linklerini parse et
        // DÜZELTME: \s* ekleyerek boşluklara (file: "url" vs file:"url") karşı regex'i esnekleştirdik.
        Regex("""file\s*:\s*["']([^"']+)["']\s*,\s*label\s*:\s*["']([^"']+)["']""").findAll(alternatifResp).forEach {
            val videoUrl = it.groupValues[1]
            var qualityStr = it.groupValues[2]
            var sourceName = this.name

            if (qualityStr.equals("FULL", ignoreCase = true)) {
                qualityStr = "1080p"
                sourceName = "Google" // VIP/Premium izlenimi yaratmak için source adını değiştirebilirsin
            }

            // DÜZELTME: Eğer gelen link bir m3u8 (HLS) listesi ise, player'ın bunu doğru parse etmesi için type'ı M3U8 yapmalıyız.
            val linkType = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            // Callback ile video bilgilerini geri gönder
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = sourceName,
                    url = videoUrl,
                    type = linkType
                ) {
                    this.referer = refererUrl
                    this.quality = getQualityFromName(qualityStr)
                }
            )
            linksFound = true
        }

        return linksFound
    }
}
