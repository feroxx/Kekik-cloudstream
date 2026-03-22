// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class JetFilmizle : MainAPI() {
    override var mainUrl              = "https://jetfilmizle.net"
    override var name                 = "JetFilmizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        mainUrl to "Son Filmler",
        "${mainUrl}/saglayici/netflix"        to "Netflix",
        "${mainUrl}/gunun-kesleri"            to "Editörün Seçimi",
        "${mainUrl}/yerli-filmler"            to "Türk Filmleri",
        "${mainUrl}/diziler"                  to "Diziler",
        "${mainUrl}/kategoriler/nette-ilkler" to "Nette İlk Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val urlpage = if (page == 1) baseUrl else "$baseUrl/page/$page"
        val document = app.get(urlpage).document

        // HTML'de her bir içerik "div.film-card" sınıfına sahip bir kart içinde tutuluyor.
        val home = document.select("div.film-card").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // 1. URL'yi al: İlk <a> etiketinin href'i direkt olarak içeriğe gidiyor.
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null

        // 2. Başlığı al: h2, h3 diye tek tek aramak yerine doğrudan ".card-title a" sınıfını hedefliyoruz.
        var title = this.selectFirst(".card-title a")?.text()?.trim() ?: return null
        // İleride başlıklarda " izle" takısı gelirse diye güvenli bir şekilde temizleyelim.
        title = title.substringBeforeLast(" izle").trim()

        // 3. Afiş URL'sini al: ".film-poster img" içinde src olarak duruyor.
        // Ancak lazy-loading (sonradan yüklenme) durumlarına karşı önce data-src kontrolü yapmak best-practice'dir.
        val imgElement = this.selectFirst(".film-poster img")
        val posterUrl = fixUrlNull(
            imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgElement?.attr("src")
        )

        // 4. Senior Dokunuşu: İçerik film mi dizi mi? URL yapısından dinamik olarak tespit ediyoruz.
        // Örnek: https://jetfilmizle.net/dizi/ejderhalar-prensi
        val isTvSeries = href.contains("/dizi/", ignoreCase = true)
        val tvType = if (isTvSeries) TvType.TvSeries else TvType.Movie

        // Cloudstream'in yapısına uygun olarak sonucu dön.
        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post(
            "${mainUrl}/arama?q=",
            referer = "${mainUrl}/",
            data    = mapOf("s" to query)
        ).document

        return document.select("div.film-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // 1. Başlık: Artık h1.film-title içinde.
        // "ownText()" kullanarak içindeki orijinal adı tutan <span> etiketini dışarıda bırakıyoruz.
        val title = document.selectFirst("h1.film-title")?.ownText()?.trim()
            ?: document.selectFirst("h1.film-title")?.text()?.substringBefore("(")?.trim()
            ?: return null

        // 2. Afiş: Artık .film-poster sınıfına sahip.
        val imgElement = document.selectFirst("img.film-poster")
        val poster = fixUrlNull(
            imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgElement?.attr("src")
        )

        // 3. Yıl: Yeni HTML'de "Yapım: 2026" gibi net bir div yok.
        // Ancak Trakt.tv linkinin sonunda (örn: ...-2026) geçiyor. Oradan çekmek çok daha güvenli.
        // Eğer Trakt linki yoksa, fallback olarak metnin içinden ilk mantıklı yılı (Regex ile) arıyoruz.
        val traktUrl = document.selectFirst("a.trakt")?.attr("href")
        var year = traktUrl?.substringAfterLast("-")?.toIntOrNull()
        if (year == null) {
            val textMatch = Regex("""\b(19|20)\d{2}\b""").find(document.text())
            year = textMatch?.value?.toIntOrNull()
        }

        // 4. Açıklama: div.description-text içine taşınmış.
        val description = document.selectFirst("div.description-text")?.text()?.trim()

        // DİKKAT: Gönderdiğin HTML parçasında Etiketler, Oyuncular ve Benzer Filmler kısmı yok.
        // Bu yüzden buradaki seçicileri genelleyerek korudum. Eğer sayfada bu kısımlar da değiştiyse,
        // o bölümlerin HTML'ini gönderdiğinde burayı da nokta atışı güncelleyebiliriz.

        val tags = document.select("div.catss a, div.film-categories a").map { it.text().trim() }

        val actors = document.select("div.oyuncu, div.cast-item").mapNotNull {
            val name = it.selectFirst("div.name, span.actor-name")?.text()?.trim() ?: return@mapNotNull null
            val actorImg = it.selectFirst("img")
            val actorPoster = fixUrlNull(
                actorImg?.attr("data-src")?.takeIf { src -> src.isNotBlank() }
                    ?: actorImg?.attr("src")
            )
            Actor(name, actorPoster)
        }

        // Benzer filmler için bir önceki sorunda yazdığımız getMainPage yapısını buraya da entegre ettim.
        val recommendations = document.select("div#benzers article, div.film-card").mapNotNull {
            var recName = it.selectFirst(".card-title a")?.text()?.trim()
                ?: it.selectFirst("h2 a, h3 a")?.text()?.trim()
                ?: return@mapNotNull null

            recName = recName.substringBeforeLast(" izle").trim()

            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null

            val recImg = it.selectFirst("img")
            val recPosterUrl = fixUrlNull(
                recImg?.attr("data-src")?.takeIf { src -> src.isNotBlank() }
                    ?: recImg?.attr("src")
            )

            newMovieSearchResponse(recName, recHref, TvType.Movie) {
                this.posterUrl = recPosterUrl
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("JTF", "data » $data")
        val document = app.get(data).document

        val iframes    = mutableListOf<String>()
        val iframe = fixUrlNull(document.selectFirst("div#movie iframe")?.attr("data-litespeed-src")) ?: fixUrlNull(document.selectFirst("div#movie iframe")?.attr("src"))
        Log.d("JTF", "iframe » $iframe")
        if (iframe != null) {
            iframes.add(iframe)
        }

        document.select("a.download-btn[href]").forEach downloadLinkForEach@{ link ->
            val href = link.attr("href")
            if (!href.contains("pixeldrain.com")) return@downloadLinkForEach
            Log.d("JTF", "iframe » $iframe")
		
            val downloadLink = fixUrlNull(href) ?: return@downloadLinkForEach

            if (iframe != null) {
                iframes.add(downloadLink)
            }
        }

        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        for (iframe in iframes) {
            if (iframe.contains("d2rs")) {
                Log.d("JTF", "jetv » $iframe")
                val jetvDoc    = app.get(iframe).document
                val jetvIframe = fixUrlNull(jetvDoc.selectFirst("iframe")?.attr("src")) ?: continue
                Log.d("JTF", "jetvIframe » $jetvIframe")

                loadExtractor(jetvIframe, "${mainUrl}/", subtitleCallback, callback)
            } else if (iframe.contains("jetv.xyz")) {
                    Log.d("JTF", "jetv » $iframe")
                    val jetvDoc    = app.get(iframe).document
                    /*val jetvIframe = fixUrlNull(jetvDoc.selectFirst("iframe")?.attr("src")) ?: return@forEach
                    Log.d("JTF", "jetvIframe » $jetvIframe")
                    loadExtractor(jetvIframe, "${mainUrl}/", subtitleCallback, callback)*/
                    val script =
                        jetvDoc.select("script").find { it.data().contains("\"sources\": [") }
                            ?.data()
                            ?: ""
                    val source = script.substringAfter("\"sources\": [").substringBefore("]")
                        .addMarks("file").addMarks("type").addMarks("label").replace("\'", "\"")
                    Log.d("JTF", "source -> $source")
                    val son: Source = objectMapper.readValue(source)
                    callback.invoke(
                        newExtractorLink(
                            source = "Jetv" + " - " + son.label,
                            name = "Jetv" + " - " + son.label,
                            url = son.file,
                            ExtractorLinkType.M3U8
                        ) {
                            this. quality = Qualities.Unknown.value
                        }
                    )
                    } else {
                loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }
	    private fun String.addMarks(str: String): String {
        return this.replace(Regex("\"?$str\"?"), "\"$str\"")
    }
}
