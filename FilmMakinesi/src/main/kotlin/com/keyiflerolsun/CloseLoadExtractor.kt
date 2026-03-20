package com.keyiflerolsun

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CloseLoad : ExtractorApi() {
    override val name = "CloseLoad"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    @OptIn(Prerelease::class)
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers2 = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0",
            "Referer" to "https://closeload.filmmakinesi.to/",
            "Origin" to "https://closeload.filmmakinesi.to"
        )

        try {
            val response = app.get(url, referer = mainUrl, headers = headers2)
            val html = response.text

            // WebView ile JS deşifre işlemi
            // 'app.context' Cloudstream'in sağladığı global context'tir.
            val realUrl = decryptWithWebView(com.lagradost.cloudstream3.CloudStreamApp.context, html)

            if (!realUrl.isNullOrBlank() && realUrl.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = realUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.P1080.value
                        headers = mapOf(
                            "Referer" to "https://closeload.filmmakinesi.to/",
                            "User-Agent" to headers2["User-Agent"]!!
                        )
                    }
                )
            } else {
                Log.e("Kekik_${this.name}", "Real URL deşifre edilemedi.")
            }

            processSubtitles(response.document, subtitleCallback)

        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Hata: ${e.message}")
        }
    }

private suspend fun decryptWithWebView(context: Context?, html: String): String? = withContext(Dispatchers.Main) {
    try {
        val webView = context?.let { WebView(it) }
        webView?.settings?.javaScriptEnabled = true

        val safeHtml = html.replace("`", "\\`").replace("$", "\\$")

        val jsToExecute = """
            (function() {
                try {
                    var htmlContent = `${safeHtml}`;
                    // 1. Packer bloğunu yakala
                    var scriptMatch = htmlContent.match(/eval\(function\(p,a,c,k,e,d\).+?\.split\('\|'\)\)\)/);
                    
                    if (scriptMatch) {
                        var rawScript = scriptMatch[0];
                        // 2. Packer'ı çöz ve içeriği 'unpacked' değişkenine at
                        var unpacked = eval(rawScript.replace('eval(', '('));
                        
                        // 3. Unpacked kodu çalıştır (Fonksiyonlar ve değişkenler tanımlansın)
                        eval(unpacked);
                        
                        // 4. Dinamik Değişken Bulucu:
                        // Player'ın kaynak (src) atandığı ".2a({2a:VAR_NAME," kısmından VAR_NAME'i çekiyoruz.
                        // Son scriptinde bu "3i" olarak görünüyor.
                        var varNameMatch = unpacked.match(/\.2a\s*\(\s*\{\s*2a\s*:\s*([a-zA-Z0-9]+)/);
                        var targetVar = varNameMatch ? varNameMatch[1] : null;
                        
                        if (targetVar) {
                            var finalUrl = eval(targetVar);
                            if (finalUrl && finalUrl.indexOf('http') !== -1) {
                                return finalUrl;
                            }
                        }
                        
                        // Alternatif: Eğer yukarıdaki regex kaçarsa, doğrudan '3i' veya '2K' kontrolü yap
                        if (typeof 3i !== 'undefined') return 3i;
                        if (typeof 2K !== 'undefined') return 2K;

                    }
                } catch(e) { 
                    return "Error: " + e.message; 
                }
                return "Error: Script found but Variable extraction failed";
            })()
        """.trimIndent()

        suspendCoroutine { cont ->
            webView?.evaluateJavascript(jsToExecute) { result ->
                val cleanResult = result?.trim()?.removeSurrounding("\"")

                if (cleanResult == null || cleanResult == "null" || cleanResult.isEmpty() || cleanResult.startsWith("Error:")) {
                    Log.e("Kekik_Extractor", "Deşifre Başarısız: $cleanResult")
                    cont.resume(null)
                } else {
                    // hls8 (fake) geliyorsa logla, gerçek geliyorsa (hls12/13) devam et
                    Log.d("Kekik_Extractor", "Bulunan Link: $cleanResult")
                    cont.resume(cleanResult)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("Kekik_Extractor", "WebView Hatası: ${e.message}")
        null
    }
}

    private fun processSubtitles(document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track").forEach { track ->
            val rawSrc = track.attr("src").trim()
            val label = track.attr("label").ifBlank { track.attr("srclang").ifBlank { "Altyazı" } }

            if (rawSrc.isNotBlank()) {
                val fullUrl = if (rawSrc.startsWith("http")) rawSrc else mainUrl + rawSrc
                if (fullUrl.startsWith("http")) {
                    subtitleCallback(SubtitleFile(label, fullUrl))
                }
            }
        }
    }
}