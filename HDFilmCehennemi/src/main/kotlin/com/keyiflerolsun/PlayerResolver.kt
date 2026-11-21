package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor

/**
 * Oynatıcı (embed) sayfasını indirir, [PlayerDecoder] ile GERÇEK yayın
 * adresini çözer ve oynatılabilir bir [ExtractorLink] üretir.
 *
 * Canlı doğrulama (Ekim 2026, TR çıkışlı):
 *  - Close CDN  : srvN.cdnimagesNNNN.cyou/hls/<ad>.mp4/txt/master.txt
 *  - Rapid CDN  : sNNN.rapidrame.com/hls2/.../master.m3u8?t=...
 *  - İkisi de Referer'sız 404 döner; Referer = oynatıcı sayfası,
 *    Origin = oynatıcı sayfasının kökü ile 200 + #EXTM3U döner.
 */
internal object PlayerResolver {
    private const val TAG = "KitsugiPlayer"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    class Resolved(val link: ExtractorLink, val subtitles: List<SubtitleFile>)

    fun originOf(url: String): String? = try {
        val uri = java.net.URI(url)
        val host = uri.host
        if (host.isNullOrBlank()) {
            null
        } else {
            val port = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "${uri.scheme ?: "https"}://$host$port"
        }
    } catch (e: Exception) {
        null
    }

    private fun linkTypeFor(url: String): ExtractorLinkType {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return if (path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".webm")) {
            ExtractorLinkType.VIDEO
        } else {
            ExtractorLinkType.M3U8
        }
    }

    private fun subtitleLabel(track: PlayerDecoder.Track): String {
        val label = track.label.trim()
        val lang = track.language?.lowercase().orEmpty()
        return when {
            label.equals("forced", ignoreCase = true) -> "Türkçe (Forced)"
            label.equals("turkish", ignoreCase = true) || label.contains("rkçe", ignoreCase = true) ||
                (label.isBlank() && lang == "tr") -> "Türkçe"
            label.equals("english", ignoreCase = true) || label.contains("ngilizce", ignoreCase = true) ||
                (label.isBlank() && lang == "en") -> "İngilizce"
            label.isNotBlank() -> label
            lang.isNotBlank() -> lang
            else -> "Altyazı"
        }
    }

    /**
     * @param embedUrl    oynatıcı sayfası (closeload/rapid/rplayer/hdfilmcehennemi.mobi)
     * @param pageReferer oynatıcıyı gömen film/bölüm sayfası
     * @param displayName kaynak adı (ör. "Close · Altyazılı")
     */
    suspend fun resolve(
        embedUrl: String,
        pageReferer: String?,
        displayName: String,
        interceptor: Interceptor? = null
    ): Resolved? {
        val response = try {
            app.get(
                embedUrl,
                referer = pageReferer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                ),
                interceptor = interceptor,
                timeout = 20L
            )
        } catch (e: Exception) {
            Log.w(TAG, "$displayName: oynatıcı sayfası alınamadı ($embedUrl): ${e.message}")
            return null
        }
        if (!response.isSuccessful) {
            Log.w(TAG, "$displayName: oynatıcı sayfası HTTP ${response.code} ($embedUrl)")
            return null
        }
        // Yönlendirme sonrası adres: çözücü anahtarı location.hostname'e bağlı.
        val finalUrl = response.url.ifBlank { embedUrl }
        val decoded = try {
            PlayerDecoder.decode(response.text, finalUrl)
        } catch (e: Throwable) {
            Log.e(TAG, "$displayName: çözücü hatası: ${e.message}")
            null
        }
        if (decoded == null) {
            Log.w(TAG, "$displayName: yayın adresi çözülemedi ($finalUrl, ${response.text.length} bayt)")
            return null
        }
        val origin = originOf(finalUrl) ?: return null
        val link = newExtractorLink(
            source = displayName,
            name = displayName,
            url = decoded.file,
            type = linkTypeFor(decoded.file)
        ) {
            this.referer = finalUrl
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to finalUrl,
                "Origin" to origin,
                "Accept" to "*/*"
            )
        }
        val subtitles = decoded.tracks.map { newSubtitleFile(subtitleLabel(it), it.file) }
        Log.i(TAG, "$displayName -> ${decoded.file} [${decoded.method}, ${subtitles.size} altyazı]")
        return Resolved(link, subtitles)
    }
}
