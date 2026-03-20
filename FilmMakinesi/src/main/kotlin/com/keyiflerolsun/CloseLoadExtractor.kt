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

        // JS içinde çakışma olmaması için HTML içeriğini güvenli hale getiriyoruz
        val safeHtml = html.replace("`", "\\`").replace("$", "\\$")

        val jsToExecute = """
            (function() {
                try {
                    var htmlContent = `${safeHtml}`;
                    // Packer (eval) bloğunu yakalayan regex
                    var scriptMatch = htmlContent.match(/eval\(function\(p,a,c,k,e,d\).+?\.split\('\|'\)\)\)/);
                    
                    if (scriptMatch) {
                        var rawScript = scriptMatch[0];
                        // eval fonksiyonunu unpack ediyoruz
                        var unpackedCode = eval(rawScript.replace('eval(', '('));
                        
                        // Senior Tip: Değişken adı değişebileceği için (2K değil 3i olmuş) 
                        // player başlatma satırından (myPlayer.src) gerçek değişken adını bulalım.
                        // JS'de: 9.2a({2a:3i, ...}) formatında. Buradaki 3i'yi yakalıyoruz.
                        var varNameMatch = unpackedCode.match(/\.2a\(\{2a:([a-zA-Z0-9]+)/);
                        var targetVar = varNameMatch ? varNameMatch[1] : "3i"; // Bulamazsa 3i'ye fallback yap
                        
                        // unpack edilmiş kodu execute et ki değişkenler (3i, 3n vb.) hafızaya yüklensin
                        eval(unpackedCode);
                        
                        // Değişkeni döndür
                        var result = eval(targetVar);
                        
                        if (typeof result !== 'undefined' && result !== null) {
                            return result;
                        }
                    }
                } catch(e) { 
                    return "Error: " + e.message; 
                }
                return "Error: Script or Variable not found";
            })()
        """.trimIndent()

        suspendCoroutine { cont ->
            webView?.evaluateJavascript(jsToExecute) { result ->
                val cleanResult = result?.trim()?.removeSurrounding("\"")

                if (cleanResult == null || cleanResult == "null" || cleanResult.isEmpty() || cleanResult.startsWith("Error:")) {
                    Log.e("Kekik_Extractor", "JS Deşifre Hatası: $cleanResult")
                    cont.resume(null)
                } else {
                    cont.resume(cleanResult)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("Kekik_Extractor", "WebView Başlatma Hatası: ${e.message}")
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