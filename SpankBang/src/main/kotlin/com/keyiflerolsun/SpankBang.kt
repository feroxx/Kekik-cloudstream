// ! https://github.com/phisher98/CXXX/blob/master/spankbang/src/main/kotlin/com/Spankbang/spankbang.kt

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class SpankBang : MainAPI() {
    override var mainUrl              = "https://spankbang.com"
    override var name                 = "SpankBang"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/new_videos/"                to "New",
        "${mainUrl}/upcoming/"                  to "Upcoming",
        "${mainUrl}/trending_videos/"           to "Trend",
        "${mainUrl}/most_popular/"              to "Popular",
        "${mainUrl}/s/onlyfans/"                to "OnlyFans",
        "${mainUrl}/7i/channel/girlsway/"       to "Girlsway",
        "${mainUrl}/jg/channel/met+art+x/"      to "Met Art X",
        "${mainUrl}/je/channel/sex+art/"        to "Sex Art",
        "${mainUrl}/ja/channel/teen+erotica/"   to "Teen Erotica",
        "${mainUrl}/7u/channel/21+naturals/"    to "21 Naturals",
        "${mainUrl}/4w/channel/letsdoeit/"      to "LETSDOEIT",
        "${mainUrl}/np/channel/facials4k/"      to "FACIALS4K",
        "${mainUrl}/9b/channel/throated/"       to "Throated",
        "${mainUrl}/6l/channel/mylf/"           to "MYLF",
        "${mainUrl}/ce/channel/bratty+milf/"    to "Bratty MILF",
        "${mainUrl}/6c/channel/teamskeet/"      to "TeamSkeet",
        "${mainUrl}/j2/channel/familyxxx/"      to "Family XXX",
        "${mainUrl}/d6/channel/my+family+pies/" to "My Family Pies",
        "${mainUrl}/6d/channel/family+strokes/" to "Family Strokes",
        "${mainUrl}/j3/channel/hot+wife+xxx/"   to "Hot Wife XXX",
        "${mainUrl}/o4/channel/touch+my+wife/"  to "Touch My Wife",
        "${mainUrl}/1q/channel/daddy4k/"        to "Daddy4K",
        "${mainUrl}/co/channel/moms+teach+sex/" to "Moms Teach",
        "${mainUrl}/8f/channel/mom+swap/"       to "Mom Swap",
        "${mainUrl}/cf/channel/bratty+sis/"     to "Bratty Sis",
        "${mainUrl}/8b/channel/sis+swap/"       to "Sis Swap",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}/?o=popular&p=w&d=10").document
        val home     = document.select("div.main_results div.video-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = fixTitle(this.select("div.name-and-menu-wrapper a").text())
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.select("picture img").attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (say in 1..5) {
            val document = app.get("${mainUrl}/s/${query}/${say}/?o=new&d=10").document
            val results  = document.select("div.main_results div.video-item").mapNotNull { it.toSearchResult() }

            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title           = document.selectFirst("div#video h1")?.text()?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description     = document.selectFirst("a[href*='join']")?.text()?.trim() ?: title
        val year            = Regex(""""uploadDate":\s*"(\d{4})""").find(document.html())?.groupValues?.get(1)?.toIntOrNull()
        val tags            = document.select("div.searches a").map { it.text() }
        val rating          = document.selectFirst("span.rate")?.text()?.trim()?.substringBefore("%")?.toRatingInt()?.div(10)
        val duration        = document.selectFirst("meta[property=og:duration]")?.attr("content")?.toIntOrNull()?.div(60)
        val recommendations = document.select("section.user_uploads div.video-item").mapNotNull { it.toSearchResult() }
        val actors          = document.select("li.primary_actions_container").map {
            Actor(it.selectFirst("span.name")!!.text(), fixUrlNull(it.selectFirst("img")?.attr("src")))
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl       = poster
            this.plot            = description
            this.year            = year
            this.tags            = tags
            this.rating          = rating
            this.duration        = duration
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("SkBg", "data » $data")
        val document = app.get(data).document
        val videoUrl = Regex("""'m3u8': \['([^'\]]+)""").find(document.html())?.groupValues?.get(1) ?: return false
        Log.d("SkBg", "videoUrl » $videoUrl")

        callback.invoke(
            ExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = fixUrl(videoUrl),
                referer = data,
                quality = Qualities.Unknown.value,
                type    = INFER_TYPE
            )
        )

        return true
    }
}